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
 *     private val MIGRATION_1_2 = Migration(1, 2) { db ->
 *         db.execSQL("ALTER TABLE games ADD COLUMN sleeved INTEGER NOT NULL DEFAULT 0")
 *     }
 *
 * then append it to [ALL].
 */
object Migrations {

    /**
     * Ordered oldest to newest. Room composes them, so a device three versions behind
     * walks the chain rather than needing a 1-to-4 migration of its own.
     */
    val ALL: Array<Migration> = arrayOf()
}
