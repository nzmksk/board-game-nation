package com.boardgamenation.tracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.ScoringMode

/**
 * The collection table. Expansions live here too, flagged by [isExpansion] and pointed
 * at their parent by [baseGameId], so every query that works on games works on
 * expansions without a second table.
 *
 * The unique index on [bggId] relies on SQLite treating NULLs as distinct, which gives
 * "unique where not null" without a partial index Room cannot express.
 */
@Entity(
    tableName = "games",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["base_game_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["bgg_id"], unique = true),
        Index(value = ["title"]),
        Index(value = ["status"]),
        Index(value = ["base_game_id"]),
    ],
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    @ColumnInfo(name = "bgg_id") val bggId: Long? = null,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "year_published") val yearPublished: Int? = null,
    @ColumnInfo(name = "min_players") val minPlayers: Int? = null,
    @ColumnInfo(name = "max_players") val maxPlayers: Int? = null,
    @ColumnInfo(name = "best_player_count") val bestPlayerCount: String? = null,
    @ColumnInfo(name = "min_playtime_minutes") val minPlaytimeMinutes: Int? = null,
    @ColumnInfo(name = "max_playtime_minutes") val maxPlaytimeMinutes: Int? = null,
    @ColumnInfo(name = "weight") val weight: Double? = null,
    @ColumnInfo(name = "bgg_rating") val bggRating: Double? = null,
    @ColumnInfo(name = "publisher") val publisher: String? = null,

    /** Local file path in app-private storage. Never a remote URL. */
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,

    /** ISO-8601 date (YYYY-MM-DD) the game entered the collection. */
    @ColumnInfo(name = "date_added") val dateAdded: String,
    @ColumnInfo(name = "price") val price: Double? = null,
    @ColumnInfo(name = "currency", defaultValue = "MYR") val currency: String = "MYR",
    @ColumnInfo(name = "purchase_note") val purchaseNote: String? = null,

    @ColumnInfo(name = "status") val status: GameStatus = GameStatus.OWNED,
    /** 1 (highest) to 5. Only meaningful while [status] is WISHLIST. */
    @ColumnInfo(name = "wishlist_priority") val wishlistPriority: Int? = null,

    /** False when the physical copy is out of the house. */
    @ColumnInfo(name = "in_possession", defaultValue = "1") val inPossession: Boolean = true,
    @ColumnInfo(name = "lent_to") val lentTo: String? = null,
    @ColumnInfo(name = "lent_date") val lentDate: String? = null,

    @ColumnInfo(name = "is_expansion", defaultValue = "0") val isExpansion: Boolean = false,
    @ColumnInfo(name = "base_game_id") val baseGameId: Long? = null,

    /** Remembered so the session form opens in the shape this game needs. */
    @ColumnInfo(name = "scoring_mode", defaultValue = "RANKED_SCORES")
    val scoringMode: ScoringMode = ScoringMode.RANKED_SCORES,

    /** For RANKED_SCORES: false means lowest score wins (golf scoring). */
    @ColumnInfo(name = "high_score_wins", defaultValue = "1") val highScoreWins: Boolean = true,

    /**
     * True for games that can end the instant a condition is met, before any end-of-game
     * scoring happens. Only gates whether the session form offers to record it; the
     * ending itself belongs to the play, not to the game.
     */
    @ColumnInfo(name = "sudden_death_possible", defaultValue = "0")
    val suddenDeathPossible: Boolean = false,

    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
