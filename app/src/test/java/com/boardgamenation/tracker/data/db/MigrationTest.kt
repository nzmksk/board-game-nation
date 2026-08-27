package com.boardgamenation.tracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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
import java.io.File

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

    // --- sudden death ---------------------------------------------------------------

    @Test
    fun `the new columns arrive and the games survive`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan")
            insertGame(db, id = 2, title = "Azul")
        }

        val db = openMigrated()

        assertEquals(2, db.gameDao().getAllGames().size)
        assertEquals("Catan", db.gameDao().getGame(1)!!.title)
        assertTrue(columnsOf(db, "games").contains("sudden_death_possible"))
        assertFalse("existing games default to off", db.gameDao().getGame(1)!!.suddenDeathPossible)
    }

    @Test
    fun `existing sessions come through as ordinary endings`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan")
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 90, 3, 0, 0)
                """.trimIndent(),
            )
        }

        val db = openMigrated()
        val session = db.sessionDao().getSession(1)!!

        assertNull("a pre-existing play was scored normally", session.endCondition)
        assertNull(session.endReason)
        assertEquals("2026-01-05", session.playedOn)
    }

    // --- integrity ------------------------------------------------------------------

    @Test
    fun `rebuilding games leaves no dangling references`() = runTest {
        seedV1 { db ->
            insertGame(db, id = 1, title = "Catan")
            insertGame(db, id = 2, title = "Catan: Seafarers", baseGameId = 1)
            db.execSQL(
                """
                INSERT INTO sessions
                    (id, game_id, played_on, duration_minutes, player_count, created_at, updated_at)
                VALUES (1, 1, '2026-01-05', 90, 3, 0, 0)
                """.trimIndent(),
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

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        block(helper.writableDatabase)
        helper.close()
    }

    /**
     * Opens the seeded file through Room, which runs the chain and then verifies the
     * result against the schema it expects. That verification is the real assertion
     * here: a migration that leaves the schema subtly wrong fails on this line.
     */
    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also {
                database = it
                it.openHelper.writableDatabase
            }

    private fun insertGame(
        db: SupportSQLiteDatabase,
        id: Long,
        title: String,
        baseGameId: Long? = null,
    ) = db.execSQL(
        """
        INSERT INTO games (id, title, date_added, status, base_game_id,
                           created_at, updated_at)
        VALUES ($id, '$title', '2026-01-01', 'OWNED', ${baseGameId ?: "NULL"}, 0, 0)
        """.trimIndent(),
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
