package com.boardgamenation.tracker.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Guards the rule that every schema change ships a migration.
 *
 * `fallbackToDestructiveMigration` is never called anywhere in this project, so a version
 * bump without a matching migration is a crash on somebody's phone rather than a caught
 * mistake. This test turns that into a build failure instead: bump [AppDatabase.VERSION]
 * without adding to [Migrations.ALL] and it fails here, before anything ships.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationChainTest {

    private val schemaDirectory = File("schemas/${AppDatabase::class.qualifiedName}")

    @Test
    fun `the current schema is exported and committed`() {
        val current = File(schemaDirectory, "${AppDatabase.VERSION}.json")
        assertTrue(
            "Missing exported schema ${current.path}. Room writes it on build; commit it.",
            current.exists(),
        )
    }

    /**
     * Room composes migrations, so a device several versions behind walks the chain. That
     * only works if the chain has no holes.
     */
    @Test
    fun `migrations form an unbroken chain up to the current version`() {
        val covered = Migrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        val missing = (1 until AppDatabase.VERSION).filterNot { version ->
            covered.any { (start, end) -> start == version && end == version + 1 }
        }
        assertTrue(
            "No migration from version(s) $missing. Every schema change needs one.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every migration has a schema on both sides of it`() {
        Migrations.ALL.forEach { migration ->
            listOf(migration.startVersion, migration.endVersion).forEach { version ->
                assertTrue(
                    "Migration ${migration.startVersion} to ${migration.endVersion} " +
                        "has no exported schema for version $version",
                    File(schemaDirectory, "$version.json").exists(),
                )
            }
        }
    }

    /** A sanity check that the exported schema is the one the code declares. */
    @Test
    fun `the exported schema matches the declared version and entity set`() {
        val file = File(schemaDirectory, "${AppDatabase.VERSION}.json")
        val database = JSONObject(file.readText()).getJSONObject("database")
        assertEquals(AppDatabase.VERSION, database.getInt("version"))

        val entities = database.getJSONArray("entities")
        val tables = (0 until entities.length())
            .map { entities.getJSONObject(it).getString("tableName") }
            .toSet()

        // Every table the CSV export names must actually exist, or a backup would come
        // back missing a table nobody noticed was gone.
        setOf(
            "games", "tags", "game_tags", "players", "sessions", "session_players",
            "session_expansions", "rubrics", "rubric_criteria", "game_ratings",
            "game_rating_scores", "achievements", "achievement_unlocks",
            "timer_presets", "timer_state", "timer_seats", "bgg_thing_cache",
        ).forEach { table ->
            assertTrue("Schema is missing table $table", table in tables)
        }
    }
}
