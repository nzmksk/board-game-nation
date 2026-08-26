package com.boardgamenation.tracker.data.csv

import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.data.db.entity.AchievementUnlockEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingEntity
import com.boardgamenation.tracker.data.db.entity.GameRatingScoreEntity
import com.boardgamenation.tracker.data.db.entity.GameTagCrossRef
import com.boardgamenation.tracker.data.db.entity.RubricCriterionEntity
import com.boardgamenation.tracker.data.db.entity.RubricEntity
import com.boardgamenation.tracker.data.db.entity.SessionExpansionEntity
import com.boardgamenation.tracker.data.db.projection.TableCountSummary
import com.boardgamenation.tracker.data.repository.DataMaintenanceRepository
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The acceptance criterion the whole backup story rests on: a full export, then a wipe,
 * then a full import must reproduce the database — identical row counts across every
 * table, identical computed statistics, identical achievement unlocks.
 *
 * The export and import are exercised through their in-memory entry points rather than
 * through the Storage Access Framework, so this is the same code the Settings screen
 * runs with the file plumbing removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CsvRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var exporter: CsvExporter
    private lateinit var importer: CsvImporter
    private lateinit var maintenance: DataMaintenanceRepository

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        val dispatcher = UnconfinedTestDispatcher()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        maintenance = DataMaintenanceRepository(
            database = db,
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            achievementDao = db.achievementDao(),
            timerDao = db.timerDao(),
            bggCacheDao = db.bggCacheDao(),
            io = dispatcher,
        )
        exporter = CsvExporter(
            context = context,
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            achievementDao = db.achievementDao(),
            clock = DatabaseTestFixture.clock,
            io = dispatcher,
        )
        importer = CsvImporter(
            context = context,
            database = db,
            gameDao = db.gameDao(),
            tagDao = db.tagDao(),
            playerDao = db.playerDao(),
            sessionDao = db.sessionDao(),
            rubricDao = db.rubricDao(),
            achievementDao = db.achievementDao(),
            maintenance = maintenance,
            io = dispatcher,
        )
    }

    @After
    fun tearDown() = db.close()

    /** A collection with something in every table, so nothing is untested by omission. */
    private suspend fun populate() {
        val catan = db.gameDao().insert(
            DatabaseTestFixture.game("Catan", bggId = 13, price = 120.0),
        )
        val seafarers = db.gameDao().insert(
            DatabaseTestFixture.game(
                "Catan: Seafarers",
                bggId = 325,
                isExpansion = true,
                baseGameId = catan,
            ),
        )
        val wingspan = db.gameDao().insert(
            DatabaseTestFixture.game("Wingspan, Oceania", price = 90.0),
        )
        db.gameDao().insert(
            DatabaseTestFixture.game("Wanted \"badly\"", status = GameStatus.WISHLIST),
        )

        val trading = db.tagDao().upsertByName("Trading", TagKind.MECHANIC)
        val economic = db.tagDao().upsertByName("Economic", TagKind.CATEGORY)
        db.tagDao().insertLinks(
            listOf(
                GameTagCrossRef(catan, trading),
                GameTagCrossRef(catan, economic),
                GameTagCrossRef(wingspan, economic),
            ),
        )

        val me = db.playerDao().insert(DatabaseTestFixture.player("Muhammad", isSelf = true))
        val ben = db.playerDao().insert(DatabaseTestFixture.player("Ben"))
        val aina = db.playerDao().insert(DatabaseTestFixture.player("Aina"))

        listOf("2026-01-05", "2026-01-12", "2026-02-03").forEachIndexed { index, date ->
            val sessionId = db.sessionDao().insertSession(
                DatabaseTestFixture.session(
                    gameId = catan,
                    playedOn = date,
                    durationMinutes = 75 + index * 10,
                    playerCount = 3,
                ),
            )
            db.sessionDao().insertParticipants(
                listOf(
                    DatabaseTestFixture.participant(sessionId, me, 9.0 + index, index == 0, 1),
                    DatabaseTestFixture.participant(sessionId, ben, 7.0, index != 0, 2),
                    DatabaseTestFixture.participant(sessionId, aina, 5.0, false, 3),
                ),
            )
            if (index == 2) {
                db.sessionDao().insertExpansions(
                    listOf(SessionExpansionEntity(sessionId = sessionId, gameId = seafarers)),
                )
            }
        }

        db.sessionDao().insertSession(
            DatabaseTestFixture.session(wingspan, "2026-02-10", durationMinutes = 55),
        )

        val rubricId = db.rubricDao().insertRubric(
            RubricEntity(name = "Strategy", description = "Decisions, mostly"),
        )
        val depth = db.rubricDao().insertCriterion(
            RubricCriterionEntity(rubricId = rubricId, name = "Depth", weight = 1.5, sortOrder = 0),
        )
        val replay = db.rubricDao().insertCriterion(
            RubricCriterionEntity(rubricId = rubricId, name = "Replayability", sortOrder = 1),
        )
        val ratingId = db.rubricDao().insertRating(
            GameRatingEntity(
                gameId = catan,
                rubricId = rubricId,
                ratedOn = "2026-02-15",
                computedScore = 7.4,
                notes = "Holds up",
            ),
        )
        db.rubricDao().insertScores(
            listOf(
                GameRatingScoreEntity(gameRatingId = ratingId, criterionId = depth, score = 7.0),
                GameRatingScoreEntity(gameRatingId = ratingId, criterionId = replay, score = 8.0),
            ),
        )

        db.achievementDao().insertDefinitions(
            listOf(
                AchievementEntity(
                    code = "first_play",
                    name = "First Blood",
                    description = "Log your first play.",
                    icon = "Casino",
                    category = "Milestones",
                    ruleJson = """{"type":"COUNT_THRESHOLD","metric":"TOTAL_PLAYS","target":1}""",
                ),
            ),
        )
        val achievementId = db.achievementDao().findByCode("first_play")!!.id
        db.achievementDao().insertUnlock(
            AchievementUnlockEntity(
                achievementId = achievementId,
                unlockedAt = DatabaseTestFixture.NOW,
                progressValue = 4.0,
            ),
        )
    }

    /** The figures the acceptance criterion calls "computed statistics". */
    private suspend fun statistics(): Map<String, Any?> = mapOf(
        "totalPlays" to db.statsDao().observeTotalPlays().first(),
        "totalMinutes" to db.statsDao().observeTotalMinutes().first(),
        "distinctGames" to db.statsDao().observeDistinctGamesPlayed().first(),
        "collectionValue" to db.statsDao().observeCollectionValue().first(),
        "hIndex" to db.statsDao().observeHIndex().first(),
        "ownedBase" to db.statsDao().observeOwnedBaseGameCount().first(),
        "ownedExpansions" to db.statsDao().observeOwnedExpansionCount().first(),
        "unratedOwned" to db.statsDao().observeUnratedOwnedCount().first(),
        "costPerPlay" to db.statsDao().observeOverallCostPerPlay().first(),
        "standings" to db.statsDao().observeStandings(null).first()
            .map { listOf(it.playerName, it.plays, it.wins) },
        "mostPlayed" to db.statsDao().observeMostPlayed(10).first()
            .map { it.label to it.value },
    )

    private suspend fun unlockCodes(): List<String> {
        val definitions = db.achievementDao().getAllDefinitions().associateBy { it.id }
        return db.achievementDao().getAllUnlocks()
            .mapNotNull { definitions[it.achievementId]?.code }
            .sorted()
    }

    @Test
    fun `export then wipe then import reproduces the database`() = runTest {
        populate()

        val before: TableCountSummary = maintenance.tableCounts()
        val statsBefore = statistics()
        val unlocksBefore = unlockCodes()
        val files = exporter.buildFiles()

        maintenance.wipeUserData()
        assertEquals(0, maintenance.tableCounts().total)

        val result = importer.import(files, ImportMode.REPLACE)
        assertTrue("import reported errors: ${result.errors}", result.errors.isEmpty())

        assertEquals(before, maintenance.tableCounts())
        assertEquals(statsBefore, statistics())
        assertEquals(unlocksBefore, unlockCodes())
    }

    @Test
    fun `replace mode restores primary keys verbatim`() = runTest {
        populate()
        val gamesBefore = db.gameDao().getAllGames().map { it.id to it.title }.sortedBy { it.first }
        val files = exporter.buildFiles()

        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val gamesAfter = db.gameDao().getAllGames().map { it.id to it.title }.sortedBy { it.first }
        assertEquals(gamesBefore, gamesAfter)
    }

    @Test
    fun `expansion links survive the round trip`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        val expansion = db.gameDao().getGameByTitle("Catan: Seafarers")!!
        val base = db.gameDao().getGameByTitle("Catan")!!
        assertEquals(base.id, expansion.baseGameId)
    }

    @Test
    fun `awkward text survives quoting and parsing`() = runTest {
        populate()
        val files = exporter.buildFiles()
        maintenance.wipeUserData()
        importer.import(files, ImportMode.REPLACE)

        // A comma in one title and an embedded quote in another: the two cases most
        // likely to corrupt a naive CSV.
        assertNotNull(db.gameDao().getGameByTitle("Wingspan, Oceania"))
        assertNotNull(db.gameDao().getGameByTitle("Wanted \"badly\""))
    }

    @Test
    fun `every export file carries a byte order mark and CRLF endings`() = runTest {
        populate()
        val games = exporter.buildFiles().getValue(CsvSchema.GAMES)
        assertTrue(games.startsWith(Csv.BOM))
        assertTrue(games.contains(Csv.CRLF))
    }

    @Test
    fun `the manifest records the schema version`() = runTest {
        populate()
        val manifest = CsvParser.parse(exporter.buildFiles().getValue(CsvSchema.MANIFEST))
        val version = manifest.rows.first { it.string("key") == "schema_version" }.string("value")
        assertEquals(AppDatabase.VERSION.toString(), version)
    }

    /** Importing the same export twice must not double the collection. */
    @Test
    fun `merge mode matches on natural keys instead of duplicating`() = runTest {
        populate()
        val files = exporter.buildFiles()
        val before = maintenance.tableCounts()

        importer.import(files, ImportMode.MERGE)

        val after = maintenance.tableCounts()
        assertEquals(before.games, after.games)
        assertEquals(before.players, after.players)
        assertEquals(before.tags, after.tags)
        assertEquals(before.sessions, after.sessions)
    }

    @Test
    fun `the preview counts rows without writing anything`() = runTest {
        populate()
        val files = exporter.buildFiles()
        val before = maintenance.tableCounts()

        val preview = importer.preview(files, ImportMode.MERGE)

        assertTrue(preview.canProceed)
        assertTrue(preview.headerProblems.isEmpty())
        assertEquals(before, maintenance.tableCounts())

        val games = preview.summaries.first { it.fileName == CsvSchema.GAMES }
        assertEquals(before.games, games.totalRows)
        // Everything already exists, so a merge would update rather than insert.
        assertEquals(before.games, games.updatedRows)
    }

    @Test
    fun `a file missing a required column is rejected before the database is touched`() = runTest {
        populate()
        val before = maintenance.tableCounts()
        val broken = exporter.buildFiles().toMutableMap()
        broken[CsvSchema.GAMES] = "id,bgg_id" + Csv.CRLF + "1,13" + Csv.CRLF

        val preview = importer.preview(broken, ImportMode.REPLACE)
        assertTrue(preview.headerProblems.isNotEmpty())

        val failure = runCatching { importer.import(broken, ImportMode.REPLACE) }
        assertTrue(failure.isFailure)
        // The wipe must not have happened.
        assertEquals(before, maintenance.tableCounts())
    }

    /** One bad row is reported with its line number; the rest of the file still lands. */
    @Test
    fun `a malformed row is skipped and reported rather than aborting the import`() = runTest {
        populate()
        val files = exporter.buildFiles().toMutableMap()
        val games = files.getValue(CsvSchema.GAMES)
        files[CsvSchema.GAMES] = games.replace(",2026-01-01,120,", ",2026-01-01,not-a-price,")

        maintenance.wipeUserData()
        val result = importer.import(files, ImportMode.REPLACE)

        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.first().line > 0)
        // Three of the four games still arrived.
        assertEquals(3, db.gameDao().count())
    }
}
