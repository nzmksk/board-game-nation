package com.boardgamenation.tracker.data.csv

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.dao.AchievementDao
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.dao.PlayerDao
import com.boardgamenation.tracker.data.db.dao.RubricDao
import com.boardgamenation.tracker.data.db.dao.SessionDao
import com.boardgamenation.tracker.data.db.dao.TagDao
import com.boardgamenation.tracker.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExportResult(
    val rowCounts: Map<String, Int>,
    val location: String,
) {
    val totalRows: Int get() = rowCounts.values.sum()
}

/**
 * Writes the whole database out as CSV.
 *
 * Everything goes through the Storage Access Framework: the user picks a directory or a
 * file, the app writes to the uri it is handed, and no broad storage permission is ever
 * requested.
 */
@Singleton
class CsvExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gameDao: GameDao,
    private val tagDao: TagDao,
    private val playerDao: PlayerDao,
    private val sessionDao: SessionDao,
    private val rubricDao: RubricDao,
    private val achievementDao: AchievementDao,
    private val clock: AppClock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Writes one CSV per table into a directory the user chose. */
    suspend fun exportToDirectory(treeUri: Uri): ExportResult = withContext(io) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("That folder is not writable")
        val stamp = timestamp()
        val folder = tree.createDirectory("board-game-nation-$stamp")
            ?: error("Could not create the export folder")

        val counts = mutableMapOf<String, Int>()
        buildTables().forEach { (name, table) ->
            val file = folder.createFile("text/csv", name)
                ?: error("Could not create $name")
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                counts[name] = writeTable(stream, table)
            } ?: error("Could not open $name for writing")
        }
        ExportResult(counts, folder.name ?: treeUri.toString())
    }

    /** Writes every CSV into a single timestamped zip, for one-file backups. */
    suspend fun exportToZip(documentUri: Uri): ExportResult = withContext(io) {
        val counts = mutableMapOf<String, Int>()
        context.contentResolver.openOutputStream(documentUri)?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                buildTables().forEach { (name, table) ->
                    zip.putNextEntry(ZipEntry(name))
                    // The zip stream must stay open for the next entry, so the writer is
                    // flushed rather than closed.
                    val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                    counts[name] = writeRows(writer, table)
                    writer.flush()
                    zip.closeEntry()
                }
            }
        } ?: error("Could not open the destination for writing")
        ExportResult(counts, documentUri.lastPathSegment ?: documentUri.toString())
    }

    private fun writeTable(stream: OutputStream, table: Table): Int {
        val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
        val count = writeRows(writer, table)
        writer.flush()
        return count
    }

    /**
     * The export as a map of file name to contents, with no storage involved.
     *
     * Both export paths funnel through the same table building; this exposes it so the
     * export/wipe/import round trip can be exercised as a plain unit test rather than
     * needing a document provider to write to.
     */
    suspend fun buildFiles(): Map<String, String> = withContext(io) {
        buildTables().associate { (name, table) ->
            val out = java.io.StringWriter()
            val writer = BufferedWriter(out)
            writeRows(writer, table)
            writer.flush()
            name to out.toString()
        }
    }

    private fun writeRows(writer: BufferedWriter, table: Table): Int {
        val csv = CsvWriter(writer)
        csv.writeHeader(table.columns)
        table.rows.forEach { csv.writeRow(it) }
        return table.rows.size
    }

    private class Table(val columns: List<String>, val rows: List<List<String?>>)

    /**
     * Reads every table once. Ordering is stable (by primary key) so two exports of an
     * unchanged database produce byte-identical files, which makes them diffable.
     */
    private suspend fun buildTables(): List<Pair<String, Table>> {
        val games = gameDao.getAllGames().sortedBy { it.id }
        val tags = tagDao.getAll().sortedBy { it.id }
        val gameTags = tagDao.getAllLinks().sortedWith(compareBy({ it.gameId }, { it.tagId }))
        val players = playerDao.getAll().sortedBy { it.id }
        val sessions = sessionDao.getAllSessions().sortedBy { it.id }
        val sessionPlayers = sessionDao.getAllSessionPlayers().sortedBy { it.id }
        val sessionExpansions = sessionDao.getAllSessionExpansions()
            .sortedWith(compareBy({ it.sessionId }, { it.gameId }))
        val rubrics = rubricDao.getAllRubrics().sortedBy { it.id }
        val criteria = rubricDao.getAllCriteria().sortedBy { it.id }
        val ratings = rubricDao.getAllRatings().sortedBy { it.id }
        val ratingScores = rubricDao.getAllScores().sortedBy { it.id }
        val definitions = achievementDao.getAllDefinitions().associateBy { it.id }
        val unlocks = achievementDao.getAllUnlocks().sortedBy { it.achievementId }

        return listOf(
            CsvSchema.GAMES to Table(
                CsvSchema.gameColumns,
                games.map { g ->
                    listOf(
                        g.id.toString(), Csv.formatLong(g.bggId), g.title,
                        Csv.formatInt(g.yearPublished), Csv.formatInt(g.minPlayers),
                        Csv.formatInt(g.maxPlayers), g.bestPlayerCount,
                        Csv.formatInt(g.minPlaytimeMinutes), Csv.formatInt(g.maxPlaytimeMinutes),
                        Csv.formatDouble(g.weight), Csv.formatDouble(g.bggRating),
                        g.publisher, g.thumbnailPath, g.dateAdded,
                        Csv.formatDouble(g.price), g.currency, g.purchaseNote,
                        g.status.name, Csv.formatInt(g.wishlistPriority),
                        Csv.formatBool(g.inPossession), g.lentTo, g.lentDate,
                        Csv.formatBool(g.isExpansion), Csv.formatLong(g.baseGameId),
                        g.scoringMode.name, Csv.formatBool(g.highScoreWins),
                        Csv.formatBool(g.suddenDeathPossible), g.notes,
                        g.createdAt.toString(), g.updatedAt.toString(),
                    )
                },
            ),
            CsvSchema.TAGS to Table(
                CsvSchema.tagColumns,
                tags.map { listOf(it.id.toString(), it.name, it.kind.name) },
            ),
            CsvSchema.GAME_TAGS to Table(
                CsvSchema.gameTagColumns,
                gameTags.map { listOf(it.gameId.toString(), it.tagId.toString()) },
            ),
            CsvSchema.PLAYERS to Table(
                CsvSchema.playerColumns,
                players.map {
                    listOf(
                        it.id.toString(), it.name, Csv.formatBool(it.isSelf),
                        it.colorHex, it.notes, Csv.formatBool(it.archived),
                    )
                },
            ),
            CsvSchema.SESSIONS to Table(
                CsvSchema.sessionColumns,
                sessions.map { s ->
                    listOf(
                        s.id.toString(), s.gameId.toString(), s.playedOn,
                        Csv.formatLong(s.startedAt), Csv.formatLong(s.endedAt),
                        s.durationMinutes.toString(), s.playerCount.toString(), s.location,
                        Csv.formatBool(s.isCooperative), s.coopOutcome?.name, s.mode,
                        s.endCondition?.name, s.endReason,
                        Csv.formatBool(s.isIncomplete), Csv.formatBool(s.isTeachingGame),
                        s.pausedMs.toString(), s.photoUri, s.notes,
                        s.createdAt.toString(), s.updatedAt.toString(),
                    )
                },
            ),
            CsvSchema.SESSION_PLAYERS to Table(
                CsvSchema.sessionPlayerColumns,
                sessionPlayers.map { sp ->
                    listOf(
                        sp.id.toString(), sp.sessionId.toString(), sp.playerId.toString(),
                        Csv.formatDouble(sp.score), Csv.formatInt(sp.placement),
                        Csv.formatBool(sp.isWinner), sp.faction,
                        Csv.formatBool(sp.isNewPlayer), Csv.formatLong(sp.turnTimeMs),
                        Csv.formatLong(sp.bankTimeRemainingMs),
                    )
                },
            ),
            CsvSchema.SESSION_EXPANSIONS to Table(
                CsvSchema.sessionExpansionColumns,
                sessionExpansions.map { listOf(it.sessionId.toString(), it.gameId.toString()) },
            ),
            CsvSchema.RUBRICS to Table(
                CsvSchema.rubricColumns,
                rubrics.map {
                    listOf(it.id.toString(), it.name, it.description, Csv.formatBool(it.archived))
                },
            ),
            CsvSchema.RUBRIC_CRITERIA to Table(
                CsvSchema.rubricCriterionColumns,
                criteria.map {
                    listOf(
                        it.id.toString(), it.rubricId.toString(), it.name, it.description,
                        Csv.formatDouble(it.weight), Csv.formatDouble(it.maxScore),
                        it.sortOrder.toString(),
                    )
                },
            ),
            CsvSchema.GAME_RATINGS to Table(
                CsvSchema.gameRatingColumns,
                ratings.map {
                    listOf(
                        it.id.toString(), it.gameId.toString(), it.rubricId.toString(),
                        it.ratedOn, Csv.formatDouble(it.computedScore), it.notes,
                    )
                },
            ),
            CsvSchema.GAME_RATING_SCORES to Table(
                CsvSchema.gameRatingScoreColumns,
                ratingScores.map {
                    listOf(
                        it.id.toString(), it.gameRatingId.toString(),
                        it.criterionId.toString(), Csv.formatDouble(it.score),
                    )
                },
            ),
            CsvSchema.ACHIEVEMENT_UNLOCKS to Table(
                CsvSchema.achievementUnlockColumns,
                unlocks.mapNotNull { unlock ->
                    val code = definitions[unlock.achievementId]?.code ?: return@mapNotNull null
                    listOf(
                        code, unlock.unlockedAt.toString(),
                        Csv.formatDouble(unlock.progressValue),
                        Csv.formatLong(unlock.sessionId),
                    )
                },
            ),
            CsvSchema.MANIFEST to Table(
                CsvSchema.manifestColumns,
                listOf(
                    listOf("schema_version", AppDatabase.VERSION.toString()),
                    listOf("exported_at", clock.nowMillis().toString()),
                    listOf("exported_at_iso", timestamp()),
                    listOf("app_database", AppDatabase.NAME),
                    listOf("games", games.size.toString()),
                    listOf("sessions", sessions.size.toString()),
                    listOf("players", players.size.toString()),
                ),
            ),
        )
    }

    private fun timestamp(): String = DateTimeFormatter
        .ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT)
        .withZone(clock.zone())
        .format(java.time.Instant.ofEpochMilli(clock.nowMillis()))

    /** Exposed so the backup job can name files consistently with manual exports. */
    fun suggestedZipName(): String = "board-game-nation-${timestamp()}.zip"
}
