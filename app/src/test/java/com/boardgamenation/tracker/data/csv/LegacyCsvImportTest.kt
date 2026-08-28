package com.boardgamenation.tracker.data.csv

import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.data.db.AppDatabase
import com.boardgamenation.tracker.data.db.DatabaseTestFixture
import com.boardgamenation.tracker.data.repository.DataMaintenanceRepository
import com.boardgamenation.tracker.domain.model.ImportMode
import com.boardgamenation.tracker.domain.model.TagKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Importing a CSV archive written by an older version of the app.
 *
 * This is the other half of the backup story, next to `MigrationTest`. A CSV export taken
 * before this change still carries a `designers` column on games and knows nothing about
 * sudden-death endings, and somebody restoring one of those archives should not silently
 * lose a field from every game in their collection.
 *
 * The files here are written out by hand rather than produced by the exporter, because
 * the whole point is a shape the current exporter can no longer produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LegacyCsvImportTest {

    private lateinit var db: AppDatabase
    private lateinit var importer: CsvImporter

    @Before
    fun setUp() {
        db = DatabaseTestFixture.database()
        val dispatcher = UnconfinedTestDispatcher()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val maintenance = DataMaintenanceRepository(
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

    /**
     * A version 1 archive: `designers` present on games, no `sudden_death_possible`, and
     * sessions without `end_condition` or `end_reason`. Tag ids are deliberately high, so
     * a designer tag created during the import would collide if it were created before
     * these rows were restored.
     */
    private fun legacyFiles(): Map<String, String> = mapOf(
        CsvSchema.GAMES to """
            id,title,designers,date_added,status,scoring_mode,created_at,updated_at
            1,7 Wonders Duel,"Antoine Bauza, Bruno Cathala",2026-01-01,OWNED,RANKED_SCORES,0,0
            2,Cyclades,Bruno Cathala,2026-01-02,OWNED,RANKED_SCORES,0,0
            3,Prototype,,2026-01-03,OWNED,RANKED_SCORES,0,0
        """.trimIndent(),
        CsvSchema.TAGS to """
            id,name,kind
            40,Card Drafting,MECHANIC
            41,Ancient,CATEGORY
        """.trimIndent(),
        CsvSchema.GAME_TAGS to """
            game_id,tag_id
            1,40
            1,41
        """.trimIndent(),
        CsvSchema.PLAYERS to """
            id,name,is_self
            1,Hafiz,1
        """.trimIndent(),
        CsvSchema.SESSIONS to """
            id,game_id,played_on,duration_minutes,player_count,created_at,updated_at
            1,1,2026-01-10,30,2,0,0
        """.trimIndent(),
        CsvSchema.SESSION_PLAYERS to """
            id,session_id,player_id,score,placement,is_winner
            1,1,1,42,1,1
        """.trimIndent(),
    )

    @Test
    fun `a legacy archive imports without complaint`() = runTest {
        val result = importer.import(legacyFiles(), ImportMode.REPLACE)

        assertTrue("unexpected errors: ${result.errors}", result.errors.isEmpty())
        assertEquals(3, db.gameDao().getAllGames().size)
        assertEquals(1, db.sessionDao().count())
    }

    @Test
    fun `the old designers column is rescued into DESIGNER tags`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        val designers = db.tagDao().getAll().filter { it.kind == TagKind.DESIGNER }
        assertEquals(
            listOf("Antoine Bauza", "Bruno Cathala"),
            designers.map { it.name }.sorted(),
        )

        assertEquals(
            listOf("Antoine Bauza", "Bruno Cathala"),
            db.tagDao().observeForGame(1).first()
                .filter { it.kind == TagKind.DESIGNER }
                .map { it.name }
                .sorted(),
        )
        // A game with an empty designers cell gets nothing rather than a blank tag.
        assertTrue(db.tagDao().observeForGame(3).first().isEmpty())
    }

    /**
     * Replace mode restores tag ids from the file verbatim. Creating designer tags before
     * that happened would hand out ids 1 and 2, which is fine here but would collide the
     * moment an archive used low tag ids -- so the rescue runs after `game_tags`.
     */
    @Test
    fun `rescued designers do not disturb the tag ids the archive restored`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        assertEquals("Card Drafting", db.tagDao().getAll().single { it.id == 40L }.name)
        assertEquals("Ancient", db.tagDao().getAll().single { it.id == 41L }.name)

        val designerIds = db.tagDao().getAll()
            .filter { it.kind == TagKind.DESIGNER }
            .map { it.id }
        assertTrue(
            "designer ids $designerIds should not overwrite restored ids",
            designerIds.none { it == 40L || it == 41L },
        )
        // The mechanic and category links from the archive are still intact.
        assertEquals(
            listOf("Ancient", "Card Drafting"),
            db.tagDao().observeForGame(1).first()
                .filter { it.kind != TagKind.DESIGNER }
                .map { it.name }
                .sorted(),
        )
    }

    @Test
    fun `columns the old archive never had fall back to their defaults`() = runTest {
        importer.import(legacyFiles(), ImportMode.REPLACE)

        assertFalse(
            "sudden_death_possible was absent, so it should be false",
            db.gameDao().getGame(1)!!.suddenDeathPossible,
        )
        val session = db.sessionDao().getSession(1)!!
        assertNull("no end_condition column in the archive", session.endCondition)
        assertNull(session.endReason)
    }
}
