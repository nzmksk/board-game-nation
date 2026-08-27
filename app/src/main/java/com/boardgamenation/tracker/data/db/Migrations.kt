package com.boardgamenation.tracker.data.db

import androidx.room.migration.Migration

/**
 * Every schema change ships a migration here and a test in `MigrationTest` that opens a
 * real database at the old version, runs the migration, and asserts the data survived.
 *
 * There is exactly one rule in this file: never delete user data to make a schema fit.
 * `fallbackToDestructiveMigration` is not used anywhere in this project, so a missing
 * migration is a loud crash in development rather than a silent wipe on someone's phone.
 *
 * Adding one looks like:
 *
 *     private val MIGRATION_2_3 = Migration(2, 3) { db ->
 *         db.execSQL("ALTER TABLE games ADD COLUMN sleeved INTEGER NOT NULL DEFAULT 0")
 *     }
 *
 * then append it to [ALL].
 */
object Migrations {

    /**
     * Records how a play ended, for games that can finish the moment a condition is met.
     *
     * Purely additive, and deliberately so: a null `end_condition` means the ordinary
     * case of playing through to final scoring, which is exactly what every session
     * written before this migration did. Nothing needs backfilling.
     */
    private val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL(
            "ALTER TABLE games ADD COLUMN sudden_death_possible INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL("ALTER TABLE sessions ADD COLUMN end_condition TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN end_reason TEXT")
    }

    /**
     * Ordered oldest to newest. Room composes them, so a device three versions behind
     * walks the chain rather than needing a 1-to-4 migration of its own.
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
