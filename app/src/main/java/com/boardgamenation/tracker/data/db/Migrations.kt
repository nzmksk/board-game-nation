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
 *     private val MIGRATION_4_5 = Migration(4, 5) { db ->
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
     * Moves designers out of a comma-joined column and into the tag table.
     *
     * `games.designers` was one delimited string, a different shape from mechanics and
     * categories despite being the same kind of data, and nothing could filter or group
     * on it. The names are split into `tags` rows of a new `DESIGNER` kind and linked
     * through `game_tags`, after which the column goes away.
     *
     * The backfill runs before the drop and inside the one transaction Room wraps a
     * migration in, so either every designer survives the move or none of it happens.
     */
    private val MIGRATION_2_3 = Migration(2, 3) { db ->
        // Splitting a delimited column needs a recursive CTE; SQLite has had them since
        // 3.8.3, comfortably below the 3.18 that ships with minSdk 26. A trailing comma
        // is appended so the last (or only) name terminates like every other one.
        //
        // A designer whose own name contains a comma splits wrongly. That ambiguity was
        // baked into the comma-joined column and cannot be recovered here; getting out of
        // that representation is the whole point of the migration.
        db.execSQL(
            """
            CREATE TEMP TABLE designer_split AS
            WITH RECURSIVE split(game_id, name, rest) AS (
                SELECT id, '', designers || ','
                  FROM games
                 WHERE designers IS NOT NULL AND trim(designers) <> ''
                UNION ALL
                SELECT game_id,
                       trim(substr(rest, 1, instr(rest, ',') - 1)),
                       substr(rest, instr(rest, ',') + 1)
                  FROM split
                 WHERE rest <> ''
            )
            SELECT game_id, name FROM split WHERE name <> ''
            """.trimIndent(),
        )

        // The unique index on (name, kind) makes OR IGNORE the de-duplicator: two games
        // by the same designer converge on one tag row.
        db.execSQL(
            """
            INSERT OR IGNORE INTO tags (name, kind)
            SELECT DISTINCT name, 'DESIGNER' FROM designer_split
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO game_tags (game_id, tag_id)
            SELECT s.game_id, t.id
              FROM designer_split s
              JOIN tags t ON t.name = s.name AND t.kind = 'DESIGNER'
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE designer_split")

        // minSdk 26 ships SQLite 3.18, which predates ALTER TABLE DROP COLUMN (3.35), so
        // removing the column means the create/copy/drop/rename recipe.
        //
        // The AUTOINCREMENT counter is stashed first. Dropping the table takes its
        // sqlite_sequence row with it, and copying the rows back only raises the counter
        // as far as MAX(id) -- so a collection whose highest-numbered game had been
        // deleted would start handing that id out a second time.
        db.execSQL(
            "CREATE TEMP TABLE games_seq AS " +
                "SELECT seq FROM sqlite_sequence WHERE name = 'games'",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `games_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bgg_id` INTEGER,
                `title` TEXT NOT NULL,
                `year_published` INTEGER,
                `min_players` INTEGER,
                `max_players` INTEGER,
                `best_player_count` TEXT,
                `min_playtime_minutes` INTEGER,
                `max_playtime_minutes` INTEGER,
                `weight` REAL,
                `bgg_rating` REAL,
                `publisher` TEXT,
                `thumbnail_path` TEXT,
                `date_added` TEXT NOT NULL,
                `price` REAL,
                `currency` TEXT NOT NULL DEFAULT 'MYR',
                `purchase_note` TEXT,
                `status` TEXT NOT NULL,
                `wishlist_priority` INTEGER,
                `in_possession` INTEGER NOT NULL DEFAULT 1,
                `lent_to` TEXT,
                `lent_date` TEXT,
                `is_expansion` INTEGER NOT NULL DEFAULT 0,
                `base_game_id` INTEGER,
                `scoring_mode` TEXT NOT NULL DEFAULT 'RANKED_SCORES',
                `high_score_wins` INTEGER NOT NULL DEFAULT 1,
                `sudden_death_possible` INTEGER NOT NULL DEFAULT 0,
                `notes` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                FOREIGN KEY(`base_game_id`) REFERENCES `games`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `games_new` (
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `in_possession`, `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `sudden_death_possible`, `notes`,
                `created_at`, `updated_at`
            )
            SELECT
                `id`, `bgg_id`, `title`, `year_published`, `min_players`, `max_players`,
                `best_player_count`, `min_playtime_minutes`, `max_playtime_minutes`,
                `weight`, `bgg_rating`, `publisher`, `thumbnail_path`, `date_added`,
                `price`, `currency`, `purchase_note`, `status`, `wishlist_priority`,
                `in_possession`, `lent_to`, `lent_date`, `is_expansion`, `base_game_id`,
                `scoring_mode`, `high_score_wins`, `sudden_death_possible`, `notes`,
                `created_at`, `updated_at`
            FROM `games`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `games`")
        db.execSQL("ALTER TABLE `games_new` RENAME TO `games`")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_bgg_id` ON `games` (`bgg_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_title` ON `games` (`title`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_status` ON `games` (`status`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_games_base_game_id` ON `games` (`base_game_id`)",
        )

        // Put the counter back where it was, but never move it backwards.
        db.execSQL(
            """
            UPDATE sqlite_sequence
               SET seq = (SELECT seq FROM games_seq)
             WHERE name = 'games' AND (SELECT seq FROM games_seq) > seq
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO sqlite_sequence (name, seq)
            SELECT 'games', (SELECT seq FROM games_seq)
             WHERE (SELECT seq FROM games_seq) IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'games')
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE games_seq")
    }

    /**
     * Lets the live timer be a count-up clock as well as a dual countdown.
     *
     * Both columns are additive with defaults matching what every stored clock already
     * is: a turn-based one, with no table time because time belonged to a seat.
     */
    private val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL(
            "ALTER TABLE timer_state ADD COLUMN mode TEXT NOT NULL DEFAULT 'TURN_BASED'",
        )
        db.execSQL(
            "ALTER TABLE timer_state ADD COLUMN table_time_ms INTEGER NOT NULL DEFAULT 0",
        )
    }

    /**
     * Records the configuration a game was played at -- modules, level, scenario.
     *
     * Additive and nullable: a null `mode` means nobody recorded one, which is what
     * every session written before this migration is. Nothing needs backfilling.
     */
    private val MIGRATION_4_5 = Migration(4, 5) { db ->
        db.execSQL("ALTER TABLE sessions ADD COLUMN mode TEXT")
    }

    /**
     * Records who went first and the order the table took its turns in.
     *
     * Additive and nullable, so nothing needs backfilling. Null is the honest answer for
     * every play logged before the column existed: defaulting it to 1 would have handed
     * a first player to thousands of rows nobody recorded one for, and the first-player
     * statistics would then be measuring the default rather than the table.
     */
    private val MIGRATION_5_6 = Migration(5, 6) { db ->
        db.execSQL("ALTER TABLE session_players ADD COLUMN turn_order INTEGER")
    }

    /**
     * Ordered oldest to newest. Room composes them, so a device three versions behind
     * walks the chain rather than needing a 1-to-4 migration of its own.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )
}
