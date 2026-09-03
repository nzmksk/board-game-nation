package com.boardgamenation.tracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.boardgamenation.tracker.domain.model.TagKind
import com.boardgamenation.tracker.domain.model.TimerMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
 * Runs the migration chain against a real database built at the old schema.
 *
 * This is the test that stands behind restoring a backup taken before an update. A `.db`
 * backup is a byte copy stamped with its own schema version, and restoring one is exactly
 * this: an old file, opened by a newer app, migrated on the way in. If the migration
 * produced a schema Room did not recognise as the one it expects, opening the database
 * would throw, and no amount of editing the backup could fix it.
 *
 * The old schema is rebuilt from the committed `1.json` rather than from a hand-copied
 * DDL string, so it cannot drift away from what version 1 actually shipped.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DB_NAME)
    }

    // --- designers ------------------------------------------------------------------

    @Test
    fun `a comma-joined designers column becomes DESIGNER tags`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "7 Wonders Duel", designers = "'Antoine Bauza'")
            insertGame(db, id = 2, title = "7 Wonders", designers = "'Antoine Bauza'")
            insertGame(db, id = 3, title = "Cyclades", designers = "'Bruno Cathala, Ludovic Maublanc'")
        }

        val db = openMigrated()
        val tags = db.tagDao().getAll().filter { it.kind == TagKind.DESIGNER }

        assertEquals(
            listOf("Antoine Bauza", "Bruno Cathala", "Ludovic Maublanc"),
            tags.map { it.name }.sorted()
        )
        // Two games by Antoine Bauza converge on one tag row rather than two.
        assertEquals(1, tags.count { it.name == "Antoine Bauza" })

        assertEquals(
            listOf("Antoine Bauza"),
            db.tagDao().observeForGame(1).first().map { it.name }
        )
        assertEquals(
            listOf("Bruno Cathala", "Ludovic Maublanc"),
            db.tagDao().observeForGame(3).first().map { it.name }.sorted()
        )
    }

    @Test
    fun `awkward designer values do not produce empty or stray tags`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Null", designers = "NULL")
            insertGame(db, id = 2, title = "Empty", designers = "''")
            insertGame(db, id = 3, title = "Whitespace only", designers = "'   '")
            // Padding and an empty segment between two commas.
            insertGame(db, id = 4, title = "Messy", designers = "'  Uwe Rosenberg ,, Reiner Knizia  '")
        }

        val db = openMigrated()
        val designers = db.tagDao().getAll().filter { it.kind == TagKind.DESIGNER }

        assertEquals(listOf("Reiner Knizia", "Uwe Rosenberg"), designers.map { it.name }.sorted())
        assertTrue("no blank tag names", designers.none { it.name.isBlank() })
        listOf(1L, 2L, 3L).forEach { gameId ->
            assertTrue(
                "game $gameId should have no tags",
                db.tagDao().observeForGame(gameId).first().isEmpty()
            )
        }
        assertEquals(2, db.tagDao().observeForGame(4).first().size)
    }

    @Test
    fun `the designers column is gone and the games survive`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan", designers = "'Klaus Teuber'")
            insertGame(db, id = 2, title = "Azul", designers = "NULL")
        }

        val db = openMigrated()

        assertEquals(2, db.gameDao().getAllGames().size)
        assertEquals("Catan", db.gameDao().getGame(1)!!.title)
        assertFalse("designers column should be dropped", columnsOf(db, "games").contains("designers"))
        assertTrue(columnsOf(db, "games").contains("sudden_death_possible"))
    }

    /**
     * Dropping the table takes its `sqlite_sequence` row with it, and copying the rows
     * back only raises the counter as far as MAX(id). A collection whose highest-numbered
     * game had been deleted would otherwise start handing that id out a second time.
     */
    @Test
    fun `the autoincrement counter is not rewound by the table rebuild`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Kept", designers = "NULL")
            insertGame(db, id = 7, title = "Deleted later", designers = "NULL")
            db.execSQL("DELETE FROM games WHERE id = 7")
        }

        val db = openMigrated()
        val newId = db.gameDao().insert(DatabaseTestFixture.game("Brand new"))

        assertTrue("expected an id above 7 but got $newId", newId > 7)
    }

    // --- sudden death ---------------------------------------------------------------

    @Test
    fun `existing sessions come through as ordinary endings`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 90, 3, 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        val session = db.sessionDao().getSession(1)!!

        assertNull("a pre-existing play was scored normally", session.endCondition)
        assertNull(session.endReason)
        assertEquals("2026-01-05", session.playedOn)
    }

    // --- timer mode -----------------------------------------------------------------

    @Test
    fun `a stored clock comes through as the turn-based one it was`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Brass", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO timer_state
                    (id, game_id, run_state, active_seat, active_clock,
                     turn_seconds, bank_seconds, warning_threshold_seconds,
                     bank_exhausted_behaviour)
                VALUES (1, 1, 'PAUSED', 0, 'TURN', 60, 600, 10, 'FLAG_AND_OVERTIME')
                """.trimIndent()
            )
        }

        val db = openMigrated()
        val columns = columnsOf(db, "timer_state")
        assertTrue("mode column added", "mode" in columns)
        assertTrue("table_time_ms column added", "table_time_ms" in columns)

        val state = db.timerDao().getState()!!
        assertEquals(TimerMode.TURN_BASED, state.mode)
        assertEquals("time belonged to a seat, never to the table", 0L, state.tableTimeMs)
    }

    // --- session mode ---------------------------------------------------------------

    @Test
    fun `existing sessions come through with no recorded mode`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Pandemic", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 45, 2, 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()

        assertTrue("mode column added", "mode" in columnsOf(db, "sessions"))
        assertNull("nobody recorded a mode before the column existed", db.sessionDao().getSession(1)!!.mode)
    }

    @Test
    fun `a mode survives being written after the migration`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Bomb Busters", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 30, 4, 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        val session = db.sessionDao().getSession(1)!!
        db.sessionDao().updateSession(session.copy(mode = "Level 12"))

        assertEquals("Level 12", db.sessionDao().getSession(1)!!.mode)
    }

    // --- turn order -----------------------------------------------------------------

    /**
     * Nobody recorded a turn order before the column existed, and the migration must not
     * pretend otherwise. A default of 1 would have handed every historical row a first
     * player, which is the one thing that would quietly corrupt a first-player win rate.
     */
    @Test
    fun `players on an existing play come through with no turn order`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 90, 2, 0, 0)
                """.trimIndent()
            )
            db.execSQL("INSERT INTO players (id, name) VALUES (1, 'Hafiz'), (2, 'Aina')")
            db.execSQL(
                """
                INSERT INTO session_players (id, session_id, player_id, score, placement, is_winner)
                VALUES (1, 1, 1, 12.0, 1, 1), (2, 1, 2, 9.0, 2, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        val rows = db.sessionDao().getAllSessionPlayers()

        assertTrue(columnsOf(db, "session_players").contains("turn_order"))
        assertEquals(2, rows.size)
        assertTrue(
            "an old play has no first player until someone says so",
            rows.all { it.turnOrder == null }
        )
        // The rest of the row is untouched, which is the other half of "additive".
        assertEquals(12.0, rows.first { it.playerId == 1L }.score!!, 0.0)
    }

    // --- teams ----------------------------------------------------------------------

    @Test
    fun `existing participants come through on no team at all`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Secret Hitler", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 45, 7, 0, 0)
                """.trimIndent()
            )
            db.execSQL("INSERT INTO players (id, name) VALUES (1, 'Aina')")
            db.execSQL(
                """
                INSERT INTO session_players (id, session_id, player_id, is_winner, faction)
                VALUES (1, 1, 1, 1, 'Hitler')
                """.trimIndent()
            )
        }

        val db = openMigrated()

        assertTrue("team column added", "team" in columnsOf(db, "session_players"))
        val participant = db.sessionDao().getParticipants(1).single()
        assertNull("a win used to belong to a player, not a side", participant.team)
        assertEquals("Hitler", participant.faction)
        assertTrue(participant.isWinner)
    }

    // --- seating --------------------------------------------------------------------

    /**
     * The seating has to arrive empty rather than assumed. Row order is the only thing
     * a backfill could have numbered these players by, and row order is not where they
     * sat -- it would have published a neighbour for every play in the database and
     * looked exactly like a recorded one.
     */
    @Test
    fun `players on an existing play come through unseated`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "7 Wonders", designers = "NULL")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 60, 3, 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO players (id, name) VALUES (1, 'Hafiz'), (2, 'Aina'), (3, 'Ben')"
            )
            db.execSQL(
                """
                INSERT INTO session_players (id, session_id, player_id, score, placement, is_winner)
                VALUES (1, 1, 1, 58.0, 1, 1), (2, 1, 2, 51.0, 2, 0), (3, 1, 3, 44.0, 3, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        val rows = db.sessionDao().getAllSessionPlayers()

        assertTrue("seat column added", "seat" in columnsOf(db, "session_players"))
        assertEquals(3, rows.size)
        assertTrue(
            "an old play seats nobody until someone says where they sat",
            rows.all { it.seat == null }
        )
        // The rest of the row is untouched, which is the other half of "additive".
        assertEquals(58.0, rows.first { it.playerId == 1L }.score!!, 0.0)
    }

    // --- integrity ------------------------------------------------------------------

    @Test
    fun `rebuilding games leaves no dangling references`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan", designers = "'Klaus Teuber'")
            insertGame(db, id = 2, title = "Catan: Seafarers", designers = "NULL", baseGameId = 1)
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 90, 3, 0, 0)
                """.trimIndent()
            )
        }

        val db = openMigrated()
        val raw = db.openHelper.writableDatabase

        raw.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals("dangling foreign keys after migration", 0, cursor.count)
        }
        assertEquals(1L, db.gameDao().getGame(2)!!.baseGameId)
    }

    // --- plumbing -------------------------------------------------------------------

    /** Builds a database at schema version 1 and hands it to [block] to fill. */
    private fun seedV1(block: (SupportSQLiteDatabase) -> Unit) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            version1Ddl().forEach(db::execSQL)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
        block(helper.writableDatabase)
        helper.close()
    }

    /**
     * Opens the seeded file through Room, which runs the chain and then verifies the
     * result against the schema it expects. That verification is the real assertion
     * here: a migration that leaves the schema subtly wrong fails on this line.
     */
    private fun openMigrated(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
        .addMigrations(*Migrations.ALL)
        .allowMainThreadQueries()
        .build()
        .also {
            database = it
            it.openHelper.writableDatabase
        }

    private fun insertGame(db: SupportSQLiteDatabase, id: Long, title: String, designers: String, baseGameId: Long? = null) = db.execSQL(
        """
        INSERT INTO games (id, title, designers, date_added, status, base_game_id,
                           created_at, updated_at)
        VALUES ($id, '$title', $designers, '2026-01-01', 'OWNED', ${baseGameId ?: "NULL"}, 0, 0)
        """.trimIndent()
    )

    private fun columnsOf(db: AppDatabase, table: String): List<String> =
        db.openHelper.writableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    /** The version 1 schema, read from the JSON Room exported and the repository commits. */
    private fun version1Ddl(): List<String> {
        val file = File("schemas/${AppDatabase::class.qualifiedName}/1.json")
        val database = JSONObject(file.readText()).getJSONObject("database")
        val statements = mutableListOf<String>()
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            statements += entity.getString("createSql").withTable(table)
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                statements += indices.getJSONObject(j).getString("createSql").withTable(table)
            }
        }
        return statements
    }

    /** `createSql` already backticks the placeholder, so only the name goes in. */
    private fun String.withTable(table: String) = replace("\${TABLE_NAME}", table)

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
