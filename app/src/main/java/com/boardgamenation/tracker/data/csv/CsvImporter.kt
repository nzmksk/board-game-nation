package com.boardgamenation.tracker.data.csv

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.dao.AchievementDao
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.RubricDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.data.db.entity.AchievementUnlockEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.db.entity.SessionEntity
import com.boardgamenation.tracker.data.db.entity.SessionExpansionEntity
import com.boardgamenation.tracker.data.db.entity.SessionPlayerEntity
import com.boardgamenation.tracker.data.repository.DataMaintenanceRepository
import com.boardgamenation.tracker.di.IoDispatcher
import com.boardgamenation.tracker.domain.model.CoopOutcome
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.ScoringMode
import com.boardgamenation.tracker.domain.model.SessionEndCondition
import com.boardgamenation.tracker.domain.model.TagKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** How one file in the import set looks before anything is committed. */
data class FileSummary(
    val fileName: String,
    val totalRows: Int,
    val newRows: Int,
    val updatedRows: Int,
    val skippedRows: Int,
)

data class ImportPreview(
    val summaries: List<FileSummary>,
    val errors: List<CsvError>,
    val missingFiles: List<String>,
    val headerProblems: List<String>,
) {
    /** Missing columns are fatal; missing optional files and bad rows are not. */
    val canProceed: Boolean get() = headerProblems.isEmpty() && summaries.isNotEmpty()

    fun describe(): String = summaries
        .filter { it.totalRows > 0 }
        .joinToString("; ") { summary ->
            val label = summary.fileName.removeSuffix(".csv").replace('_', ' ')
            when {
                summary.updatedRows == 0 -> "${summary.totalRows} $label: all new"
                summary.newRows == 0 -> "${summary.totalRows} $label: all updated"
                else -> "${summary.totalRows} $label: ${summary.newRows} new, " +
                    "${summary.updatedRows} updated"
            }
        }
}

data class ImportResult(
    val rowsWritten: Map<String, Int>,
    val errors: List<CsvError>,
) {
    val totalRows: Int get() = rowsWritten.values.sum()
}

/**
 * Reads a CSV export back in.
 *
 * The contract from the spec, in order: validate headers before touching the database,
 * run the whole thing in one transaction, report per-row errors with line numbers
 * without aborting, and show a preview before committing anything.
 *
 * Replace mode restores primary keys verbatim, which is what makes an export/wipe/import
 * round trip reproduce the database exactly rather than approximately. Merge mode
 * ignores incoming ids entirely and matches on natural keys, because ids from another
 * database are meaningless here.
 */
@Singleton
class CsvImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val gameDao: GameDao,
    private val tagDao: TagDao,
    private val playerDao: PlayerDao,
    private val sessionDao: SessionDao,
    private val rubricDao: RubricDao,
    private val achievementDao: AchievementDao,
    private val maintenance: DataMaintenanceRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Reads the file set without writing anything. */
    suspend fun preview(uri: Uri, mode: ImportMode): ImportPreview =
        withContext(io) { preview(readSource(uri), mode) }

    suspend fun preview(files: Map<String, String>, mode: ImportMode): ImportPreview = withContext(io) {
        val headerProblems = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val summaries = mutableListOf<FileSummary>()
        val errors = mutableListOf<CsvError>()

        CsvSchema.IMPORT_ORDER.forEach { name ->
            val text = files[name]
            if (text == null) {
                missing += name
                return@forEach
            }
            val table = CsvParser.parse(text)
            val missingColumns = table.missingColumns(CsvSchema.requiredColumnsFor(name))
            if (missingColumns.isNotEmpty()) {
                headerProblems += "$name is missing: ${missingColumns.joinToString(", ")}"
                return@forEach
            }
            summaries += summarise(name, table, mode, errors)
        }

        ImportPreview(summaries, errors, missing, headerProblems)
    }

    /** Counts how the rows would land, so the user confirms against real numbers. */
    private suspend fun summarise(
        name: String,
        table: CsvTable,
        mode: ImportMode,
        errors: MutableList<CsvError>,
    ): FileSummary {
        if (mode == ImportMode.REPLACE) {
            return FileSummary(name, table.rows.size, table.rows.size, 0, 0)
        }
        var new = 0
        var updated = 0
        var skipped = 0
        table.rows.forEach { row ->
            try {
                val exists = when (name) {
                    CsvSchema.GAMES -> findGame(row) != null
                    CsvSchema.PLAYERS -> playerDao.findByName(row.requireString("name")) != null
                    CsvSchema.TAGS -> tagDao.find(
                        row.requireString("name"),
                        TagKind.fromStorage(row.string("kind")),
                    ) != null
                    CsvSchema.SESSIONS -> false
                    else -> false
                }
                if (exists) updated++ else new++
            } catch (e: CsvFieldException) {
                skipped++
                errors += CsvError(row.lineNumber, "$name: ${e.message}")
            }
        }
        return FileSummary(name, table.rows.size, new, updated, skipped)
    }

    /**
     * Commits the import. Everything happens inside one Room transaction, so a failure
     * halfway through a 5,000-row session file leaves the database exactly as it was
     * rather than half-imported.
     */
    suspend fun import(uri: Uri, mode: ImportMode): ImportResult =
        withContext(io) { import(readSource(uri), mode) }

    suspend fun import(files: Map<String, String>, mode: ImportMode): ImportResult = withContext(io) {
        val errors = mutableListOf<CsvError>()
        val written = mutableMapOf<String, Int>()

        // Headers are checked up front, outside the transaction: a malformed file should
        // never get as far as opening a write.
        val headerProblems = CsvSchema.IMPORT_ORDER.mapNotNull { name ->
            val text = files[name] ?: return@mapNotNull null
            val missing = CsvParser.parse(text)
                .missingColumns(CsvSchema.requiredColumnsFor(name))
            if (missing.isEmpty()) null else "$name is missing: ${missing.joinToString(", ")}"
        }
        require(headerProblems.isEmpty()) { headerProblems.joinToString("; ") }

        database.withTransaction {
            if (mode == ImportMode.REPLACE) maintenance.wipeUserData()

            val gameIds = importGames(files[CsvSchema.GAMES], mode, errors, written)
            val tagIds = importTags(files[CsvSchema.TAGS], mode, errors, written)
            importGameTags(files[CsvSchema.GAME_TAGS], gameIds, tagIds, errors, written)
            importLegacyDesigners(files[CsvSchema.GAMES], gameIds, errors)
            val playerIds = importPlayers(files[CsvSchema.PLAYERS], mode, errors, written)
            val sessionIds =
                importSessions(files[CsvSchema.SESSIONS], mode, gameIds, errors, written)
            importSessionPlayers(
                files[CsvSchema.SESSION_PLAYERS], sessionIds, playerIds, errors, written,
            )
            importSessionExpansions(
                files[CsvSchema.SESSION_EXPANSIONS], sessionIds, gameIds, errors, written,
            )
            val rubricIds = importRubrics(files[CsvSchema.RUBRICS], mode, errors, written)
            val criterionIds = importCriteria(
                files[CsvSchema.RUBRIC_CRITERIA], rubricIds, errors, written,
            )
            val ratingIds = importRatings(
                files[CsvSchema.GAME_RATINGS], gameIds, rubricIds, errors, written,
            )
            importRatingScores(
                files[CsvSchema.GAME_RATING_SCORES], ratingIds, criterionIds, errors, written,
            )
            importUnlocks(
                files[CsvSchema.ACHIEVEMENT_UNLOCKS], sessionIds, errors, written,
            )

            // Second pass: an expansion can name a base game that had not been inserted
            // yet when its own row was read.
            relinkBaseGames(files[CsvSchema.GAMES], gameIds, errors)
        }

        ImportResult(written, errors)
    }


    // --- per-table importers --------------------------------------------------------

    /** Returns a map of the id in the file to the id in this database. */
    private suspend fun importGames(
        text: String?,
        mode: ImportMode,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0

        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val entity = GameEntity(
                    id = if (mode == ImportMode.REPLACE) incomingId else 0L,
                    bggId = row.long("bgg_id"),
                    title = row.requireString("title"),
                    yearPublished = row.int("year_published"),
                    minPlayers = row.int("min_players"),
                    maxPlayers = row.int("max_players"),
                    bestPlayerCount = row.string("best_player_count"),
                    minPlaytimeMinutes = row.int("min_playtime_minutes"),
                    maxPlaytimeMinutes = row.int("max_playtime_minutes"),
                    weight = row.double("weight"),
                    bggRating = row.double("bgg_rating"),
                    publisher = row.string("publisher"),
                    thumbnailPath = row.string("thumbnail_path"),
                    dateAdded = row.requireString("date_added"),
                    price = row.double("price"),
                    currency = row.string("currency") ?: "MYR",
                    purchaseNote = row.string("purchase_note"),
                    status = GameStatus.fromStorage(row.string("status")),
                    wishlistPriority = row.int("wishlist_priority"),
                    inPossession = row.boolean("in_possession", default = true),
                    lentTo = row.string("lent_to"),
                    lentDate = row.string("lent_date"),
                    isExpansion = row.boolean("is_expansion"),
                    // Resolved in the second pass, once every game exists.
                    baseGameId = null,
                    scoringMode = ScoringMode.fromStorage(row.string("scoring_mode")),
                    highScoreWins = row.boolean("high_score_wins", default = true),
                    suddenDeathPossible = row.boolean("sudden_death_possible"),
                    notes = row.string("notes"),
                    createdAt = row.long("created_at") ?: 0L,
                    updatedAt = row.long("updated_at") ?: 0L,
                )

                val existing = if (mode == ImportMode.MERGE) findGame(row) else null
                val newId = if (existing != null) {
                    gameDao.update(entity.copy(id = existing.id, baseGameId = existing.baseGameId))
                    existing.id
                } else {
                    gameDao.insert(entity)
                }
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "games: ${e.message}")
            }
        }
        written[CsvSchema.GAMES] = count
        return ids
    }

    /** Matches on bgg_id first, falling back to title, exactly as the spec specifies. */
    private suspend fun findGame(row: CsvRow): GameEntity? {
        row.long("bgg_id")?.let { bggId ->
            gameDao.getGameByBggId(bggId)?.let { return it }
        }
        return row.string("title")?.let { gameDao.getGameByTitle(it) }
    }

    private suspend fun relinkBaseGames(
        text: String?,
        gameIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        table.rows.forEach { row ->
            try {
                val incomingBase = row.long("base_game_id") ?: return@forEach
                val incomingId = row.long("id") ?: return@forEach
                val localId = gameIds[incomingId] ?: return@forEach
                val localBase = gameIds[incomingBase]
                if (localBase == null) {
                    errors += CsvError(
                        row.lineNumber,
                        "games: base game $incomingBase was not in the file, link skipped",
                    )
                    return@forEach
                }
                gameDao.getGame(localId)?.let { gameDao.update(it.copy(baseGameId = localBase)) }
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "games: ${e.message}")
            }
        }
    }

    private suspend fun importTags(
        text: String?,
        mode: ImportMode,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0
        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val name = row.requireString("name")
                val kind = TagKind.fromStorage(row.string("kind"))
                val newId = if (mode == ImportMode.REPLACE) {
                    tagDao.insert(
                        com.boardgamenation.tracker.data.db.entity.TagEntity(
                            id = incomingId, name = name, kind = kind,
                        ),
                    ).takeIf { it > 0 } ?: incomingId
                } else {
                    tagDao.upsertByName(name, kind)
                }
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "tags: ${e.message}")
            }
        }
        written[CsvSchema.TAGS] = count
        return ids
    }

    private suspend fun importGameTags(
        text: String?,
        gameIds: Map<Long, Long>,
        tagIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        val links = mutableListOf<GameTagCrossRef>()
        table.rows.forEach { row ->
            try {
                val gameId = gameIds[row.long("game_id")]
                val tagId = tagIds[row.long("tag_id")]
                if (gameId == null || tagId == null) {
                    errors += CsvError(row.lineNumber, "game_tags: unknown game or tag, skipped")
                    return@forEach
                }
                links += GameTagCrossRef(gameId = gameId, tagId = tagId)
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "game_tags: ${e.message}")
            }
        }
        if (links.isNotEmpty()) tagDao.insertLinks(links)
        written[CsvSchema.GAME_TAGS] = links.size
    }

    /**
     * Rescues designers from an export written before they became tags.
     *
     * Games used to carry a single comma-joined `designers` column. An archive from that
     * era still has it, and dropping it silently would quietly lose a field from every
     * game in somebody's backup, so the names are split and re-created as DESIGNER tags.
     * A current export has no such column and this does nothing.
     *
     * Deliberately runs after `game_tags`: a replace-mode import restores tag ids from
     * the file verbatim, and upserting new tags before that would hand out low
     * autoincrement ids that collide with the ones still to be restored.
     */
    private suspend fun importLegacyDesigners(
        text: String?,
        gameIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        if (LEGACY_DESIGNERS_COLUMN !in table.headers) return

        val links = mutableListOf<GameTagCrossRef>()
        table.rows.forEach { row ->
            try {
                val gameId = gameIds[row.long("id")] ?: return@forEach
                row.string(LEGACY_DESIGNERS_COLUMN)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?.forEach { name ->
                        links += GameTagCrossRef(
                            gameId = gameId,
                            tagId = tagDao.upsertByName(name, TagKind.DESIGNER),
                        )
                    }
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "designers: ${e.message}")
            }
        }
        if (links.isNotEmpty()) tagDao.insertLinks(links)
    }

    private suspend fun importPlayers(
        text: String?,
        mode: ImportMode,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0
        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val name = row.requireString("name")
                val entity = PlayerEntity(
                    id = if (mode == ImportMode.REPLACE) incomingId else 0L,
                    name = name,
                    isSelf = row.boolean("is_self"),
                    colorHex = row.string("color_hex"),
                    notes = row.string("notes"),
                    archived = row.boolean("archived"),
                )
                val existing = if (mode == ImportMode.MERGE) playerDao.findByName(name) else null
                val newId = if (existing != null) {
                    playerDao.update(entity.copy(id = existing.id))
                    existing.id
                } else {
                    playerDao.insert(entity)
                }
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "players: ${e.message}")
            }
        }
        written[CsvSchema.PLAYERS] = count
        return ids
    }

    private suspend fun importSessions(
        text: String?,
        mode: ImportMode,
        gameIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0
        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val gameId = gameIds[row.long("game_id")]
                if (gameId == null) {
                    errors += CsvError(row.lineNumber, "sessions: unknown game, skipped")
                    return@forEach
                }
                val playedOn = row.requireString("played_on")
                val playerCount = row.int("player_count") ?: 0
                val entity = SessionEntity(
                    id = if (mode == ImportMode.REPLACE) incomingId else 0L,
                    gameId = gameId,
                    playedOn = playedOn,
                    startedAt = row.long("started_at"),
                    endedAt = row.long("ended_at"),
                    durationMinutes = row.int("duration_minutes") ?: 0,
                    playerCount = playerCount,
                    location = row.string("location"),
                    isCooperative = row.boolean("is_cooperative"),
                    coopOutcome = CoopOutcome.fromStorage(row.string("coop_outcome")),
                    mode = row.string("mode"),
                    endCondition = SessionEndCondition.fromStorage(row.string("end_condition")),
                    endReason = row.string("end_reason"),
                    isIncomplete = row.boolean("is_incomplete"),
                    isTeachingGame = row.boolean("is_teaching_game"),
                    isDraft = false,
                    pausedMs = row.long("paused_ms") ?: 0L,
                    photoUri = row.string("photo_uri"),
                    notes = row.string("notes"),
                    createdAt = row.long("created_at") ?: 0L,
                    updatedAt = row.long("updated_at") ?: 0L,
                )
                val existing = if (mode == ImportMode.MERGE) {
                    sessionDao.findByNaturalKey(gameId, playedOn, playerCount)
                } else {
                    null
                }
                val newId = if (existing != null) {
                    sessionDao.updateSession(entity.copy(id = existing.id))
                    // Participants are re-imported wholesale, so the old set must go.
                    sessionDao.clearParticipants(existing.id)
                    existing.id
                } else {
                    sessionDao.insertSession(entity)
                }
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "sessions: ${e.message}")
            }
        }
        written[CsvSchema.SESSIONS] = count
        return ids
    }

    private suspend fun importSessionPlayers(
        text: String?,
        sessionIds: Map<Long, Long>,
        playerIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        val rows = mutableListOf<SessionPlayerEntity>()
        table.rows.forEach { row ->
            try {
                val sessionId = sessionIds[row.long("session_id")]
                val playerId = playerIds[row.long("player_id")]
                if (sessionId == null || playerId == null) {
                    errors += CsvError(
                        row.lineNumber, "session_players: unknown session or player, skipped",
                    )
                    return@forEach
                }
                rows += SessionPlayerEntity(
                    sessionId = sessionId,
                    playerId = playerId,
                    score = row.double("score"),
                    placement = row.int("placement"),
                    isWinner = row.boolean("is_winner"),
                    faction = row.string("faction"),
                    isNewPlayer = row.boolean("is_new_player"),
                    turnTimeMs = row.long("turn_time_ms"),
                    bankTimeRemainingMs = row.long("bank_time_remaining_ms"),
                )
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "session_players: ${e.message}")
            }
        }
        if (rows.isNotEmpty()) sessionDao.insertParticipants(rows)
        written[CsvSchema.SESSION_PLAYERS] = rows.size
    }

    private suspend fun importSessionExpansions(
        text: String?,
        sessionIds: Map<Long, Long>,
        gameIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        val rows = mutableListOf<SessionExpansionEntity>()
        table.rows.forEach { row ->
            try {
                val sessionId = sessionIds[row.long("session_id")]
                val gameId = gameIds[row.long("game_id")]
                if (sessionId == null || gameId == null) {
                    errors += CsvError(
                        row.lineNumber, "session_expansions: unknown session or game, skipped",
                    )
                    return@forEach
                }
                rows += SessionExpansionEntity(sessionId = sessionId, gameId = gameId)
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "session_expansions: ${e.message}")
            }
        }
        if (rows.isNotEmpty()) sessionDao.insertExpansions(rows)
        written[CsvSchema.SESSION_EXPANSIONS] = rows.size
    }

    private suspend fun importRubrics(
        text: String?,
        mode: ImportMode,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0
        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val name = row.requireString("name")
                val entity = RubricEntity(
                    id = if (mode == ImportMode.REPLACE) incomingId else 0L,
                    name = name,
                    description = row.string("description"),
                    archived = row.boolean("archived"),
                )
                val existing =
                    if (mode == ImportMode.MERGE) rubricDao.findRubricByName(name) else null
                val newId = if (existing != null) {
                    rubricDao.updateRubric(entity.copy(id = existing.id))
                    existing.id
                } else {
                    rubricDao.insertRubric(entity)
                }
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "rubrics: ${e.message}")
            }
        }
        written[CsvSchema.RUBRICS] = count
        return ids
    }

    private suspend fun importCriteria(
        text: String?,
        rubricIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0
        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val rubricId = rubricIds[row.long("rubric_id")]
                if (rubricId == null) {
                    errors += CsvError(row.lineNumber, "rubric_criteria: unknown rubric, skipped")
                    return@forEach
                }
                val newId = rubricDao.insertCriterion(
                    RubricCriterionEntity(
                        rubricId = rubricId,
                        name = row.requireString("name"),
                        description = row.string("description"),
                        weight = row.double("weight") ?: 1.0,
                        maxScore = row.double("max_score") ?: 10.0,
                        sortOrder = row.int("sort_order") ?: 0,
                    ),
                )
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "rubric_criteria: ${e.message}")
            }
        }
        written[CsvSchema.RUBRIC_CRITERIA] = count
        return ids
    }

    private suspend fun importRatings(
        text: String?,
        gameIds: Map<Long, Long>,
        rubricIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()
        val table = text?.let { CsvParser.parse(it) } ?: return ids
        var count = 0
        table.rows.forEach { row ->
            try {
                val incomingId = row.long("id") ?: 0L
                val gameId = gameIds[row.long("game_id")]
                val rubricId = rubricIds[row.long("rubric_id")]
                if (gameId == null || rubricId == null) {
                    errors += CsvError(
                        row.lineNumber, "game_ratings: unknown game or rubric, skipped",
                    )
                    return@forEach
                }
                val newId = rubricDao.insertRating(
                    GameRatingEntity(
                        gameId = gameId,
                        rubricId = rubricId,
                        ratedOn = row.requireString("rated_on"),
                        computedScore = row.double("computed_score") ?: 0.0,
                        notes = row.string("notes"),
                    ),
                )
                ids[incomingId] = newId
                count++
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "game_ratings: ${e.message}")
            }
        }
        written[CsvSchema.GAME_RATINGS] = count
        return ids
    }

    private suspend fun importRatingScores(
        text: String?,
        ratingIds: Map<Long, Long>,
        criterionIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        val rows = mutableListOf<GameRatingScoreEntity>()
        table.rows.forEach { row ->
            try {
                val ratingId = ratingIds[row.long("game_rating_id")]
                val criterionId = criterionIds[row.long("criterion_id")]
                if (ratingId == null || criterionId == null) {
                    errors += CsvError(
                        row.lineNumber, "game_rating_scores: unknown rating or criterion, skipped",
                    )
                    return@forEach
                }
                rows += GameRatingScoreEntity(
                    gameRatingId = ratingId,
                    criterionId = criterionId,
                    score = row.double("score") ?: 0.0,
                )
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "game_rating_scores: ${e.message}")
            }
        }
        if (rows.isNotEmpty()) rubricDao.insertScores(rows)
        written[CsvSchema.GAME_RATING_SCORES] = rows.size
    }

    private suspend fun importUnlocks(
        text: String?,
        sessionIds: Map<Long, Long>,
        errors: MutableList<CsvError>,
        written: MutableMap<String, Int>,
    ) {
        val table = text?.let { CsvParser.parse(it) } ?: return
        val rows = mutableListOf<AchievementUnlockEntity>()
        table.rows.forEach { row ->
            try {
                val code = row.requireString("achievement_code")
                val definition = achievementDao.findByCode(code)
                if (definition == null) {
                    // A code this build does not know about, probably from a newer
                    // version. Recorded rather than silently dropped.
                    errors += CsvError(
                        row.lineNumber, "achievement_unlocks: unknown achievement '$code', skipped",
                    )
                    return@forEach
                }
                rows += AchievementUnlockEntity(
                    achievementId = definition.id,
                    unlockedAt = row.long("unlocked_at") ?: 0L,
                    progressValue = row.double("progress_value") ?: 0.0,
                    sessionId = row.long("session_id")?.let { sessionIds[it] },
                )
            } catch (e: Exception) {
                errors += CsvError(row.lineNumber, "achievement_unlocks: ${e.message}")
            }
        }
        if (rows.isNotEmpty()) achievementDao.insertUnlocks(rows)
        written[CsvSchema.ACHIEVEMENT_UNLOCKS] = rows.size
    }

    // --- source reading -------------------------------------------------------------

    /**
     * Accepts either a folder of CSVs or a zip of the same, so a backup can be restored
     * from whichever form it was saved in without the user having to unpack anything.
     */
    private fun readSource(uri: Uri): Map<String, String> {
        val asTree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (asTree != null && asTree.isDirectory) {
            return asTree.listFiles()
                .filter { it.isFile && it.name?.endsWith(".csv") == true }
                .mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    val text = context.contentResolver.openInputStream(file.uri)
                        ?.use { it.readBytes().decodeToString() }
                    text?.let { name to it }
                }
                .toMap()
        }
        return readZip(uri)
    }

    private fun readZip(uri: Uri): Map<String, String> {
        val out = mutableMapOf<String, String>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".csv")) {
                        out[entry.name.substringAfterLast('/')] = zip.readBytes().decodeToString()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return out
    }

    private companion object {
        /** Only ever read, never written: the column no longer exists. */
        const val LEGACY_DESIGNERS_COLUMN = "designers"
    }
}
