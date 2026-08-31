package com.boardgamenation.tracker.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.boardgamenation.tracker.data.db.entity.PlayerEntity
import com.boardgamenation.tracker.domain.model.GameStatus

/**
 * The collection list row. Play count, rating and cost-per-play are computed in SQL so
 * the list can sort by them without loading anything into memory.
 */
data class GameListItem(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "year_published") val yearPublished: Int?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "status") val status: GameStatus,
    @ColumnInfo(name = "min_players") val minPlayers: Int?,
    @ColumnInfo(name = "max_players") val maxPlayers: Int?,
    @ColumnInfo(name = "min_playtime_minutes") val minPlaytimeMinutes: Int?,
    @ColumnInfo(name = "max_playtime_minutes") val maxPlaytimeMinutes: Int?,
    @ColumnInfo(name = "weight") val weight: Double?,
    @ColumnInfo(name = "price") val price: Double?,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "in_possession") val inPossession: Boolean,
    @ColumnInfo(name = "is_expansion") val isExpansion: Boolean,
    @ColumnInfo(name = "wishlist_priority") val wishlistPriority: Int?,
    @ColumnInfo(name = "date_added") val dateAdded: String,
    @ColumnInfo(name = "lent_to") val lentTo: String?,
    @ColumnInfo(name = "lent_date") val lentDate: String?,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_played") val lastPlayed: String?,
    @ColumnInfo(name = "rating") val rating: Double?,
    @ColumnInfo(name = "cost_per_play") val costPerPlay: Double?,
)

/** Aggregate figures for one game, shown on its detail screen. */
data class GameAggregates(
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "total_minutes") val totalMinutes: Int,
    /** Excludes incomplete sessions, which would drag the average down misleadingly. */
    @ColumnInfo(name = "avg_minutes") val avgMinutes: Double?,
    @ColumnInfo(name = "avg_minutes_non_teaching") val avgMinutesNonTeaching: Double?,
    @ColumnInfo(name = "shortest_minutes") val shortestMinutes: Int?,
    @ColumnInfo(name = "longest_minutes") val longestMinutes: Int?,
    @ColumnInfo(name = "first_played") val firstPlayed: String?,
    @ColumnInfo(name = "last_played") val lastPlayed: String?,
    @ColumnInfo(name = "wins") val wins: Int,
    @ColumnInfo(name = "self_plays") val selfPlays: Int,
)

/** A session row as shown in lists, with the game name resolved. */
data class SessionListItem(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "game_title") val gameTitle: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "played_on") val playedOn: String,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "player_count") val playerCount: Int,
    @ColumnInfo(name = "location") val location: String?,
    @ColumnInfo(name = "is_cooperative") val isCooperative: Boolean,
    @ColumnInfo(name = "coop_won") val coopWon: Boolean,

    /** The configuration the game was played at, shown beside the result. */
    @ColumnInfo(name = "mode") val mode: String?,
    @ColumnInfo(name = "is_incomplete") val isIncomplete: Boolean,
    @ColumnInfo(name = "is_teaching_game") val isTeachingGame: Boolean,

    /** Non-null only for a play that ended early; shown as a badge on the row. */
    @ColumnInfo(name = "end_reason") val endReason: String?,

    @ColumnInfo(name = "winner_names") val winnerNames: String?,

    /** Whoever holds the first seat, or null on a play nobody recorded an order for. */
    @ColumnInfo(name = "first_player_name") val firstPlayerName: String?,
)

/** A participant joined to their player record, for session detail. */
data class SessionParticipant(
    @ColumnInfo(name = "session_player_id") val sessionPlayerId: Long,
    @ColumnInfo(name = "player_id") val playerId: Long,
    @ColumnInfo(name = "player_name") val playerName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String?,
    @ColumnInfo(name = "score") val score: Double?,
    @ColumnInfo(name = "placement") val placement: Int?,
    @ColumnInfo(name = "is_winner") val isWinner: Boolean,
    @ColumnInfo(name = "faction") val faction: String?,
    @ColumnInfo(name = "turn_order") val turnOrder: Int?,
    @ColumnInfo(name = "is_new_player") val isNewPlayer: Boolean,
    @ColumnInfo(name = "turn_time_ms") val turnTimeMs: Long?,
    @ColumnInfo(name = "bank_time_remaining_ms") val bankTimeRemainingMs: Long?,
)

/**
 * How one faction has fared in one game, across everybody who has played it.
 *
 * Deliberately not per player: the question this answers is whether the game is
 * balanced, so Halikarnassos winning 30% of the time is the figure, no matter who was
 * sitting behind it.
 */
data class FactionRecord(
    @ColumnInfo(name = "faction") val faction: String,

    /** Completed plays with this faction. Abandoned games have no result to count. */
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "wins") val wins: Int,
) {
    /** Whole percent, matching how every other win rate in the app is shown. */
    val winPercent: Int get() = if (plays > 0) wins * 100 / plays else 0
}

/** A generic label/value pair backing the chart cards. */
data class LabelledValue(
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "value") val value: Double,
)

data class PlayerStandingRow(
    @ColumnInfo(name = "player_id") val playerId: Long,
    @ColumnInfo(name = "player_name") val playerName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String?,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "wins") val wins: Int,
    @ColumnInfo(name = "avg_score") val avgScore: Double?,
)

/** Head-to-head record between the device owner and one opponent. */
data class HeadToHeadRow(
    @ColumnInfo(name = "opponent_id") val opponentId: Long,
    @ColumnInfo(name = "opponent_name") val opponentName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String?,
    @ColumnInfo(name = "shared_plays") val sharedPlays: Int,
    @ColumnInfo(name = "self_wins") val selfWins: Int,
    @ColumnInfo(name = "opponent_wins") val opponentWins: Int,
)

/**
 * A player's record with one game. The rate draws the bar; the sample size travels with
 * it, because 100% off one play and 100% off twelve are the same number and not the
 * same fact.
 */
data class GameWinRateRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "wins") val wins: Int,
    @ColumnInfo(name = "win_rate") val winRate: Double,
)

data class CostPerPlayRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "price") val price: Double,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "cost_per_play") val costPerPlay: Double,
)

/**
 * Actual average duration against the range BGG states. The divergence is one of the
 * more interesting things the data has to say, so it gets its own card.
 */
data class DurationVsExpectedRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "actual_avg") val actualAvg: Double,
    @ColumnInfo(name = "stated_avg") val statedAvg: Double,
    @ColumnInfo(name = "play_count") val playCount: Int,
)

/** A rating joined with the rubric that produced it. */
data class RatingWithRubric(
    @ColumnInfo(name = "rating_id") val ratingId: Long,
    @ColumnInfo(name = "rubric_id") val rubricId: Long,
    @ColumnInfo(name = "rubric_name") val rubricName: String,
    @ColumnInfo(name = "rated_on") val ratedOn: String,
    @ColumnInfo(name = "computed_score") val computedScore: Double,
    @ColumnInfo(name = "notes") val notes: String?,
)

data class CriterionScoreRow(
    @ColumnInfo(name = "criterion_id") val criterionId: Long,
    @ColumnInfo(name = "criterion_name") val criterionName: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "weight") val weight: Double,
    @ColumnInfo(name = "max_score") val maxScore: Double,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "score") val score: Double?,
)

/** An achievement definition plus its unlock row, when it has one. */
data class AchievementWithUnlock(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "target_value") val targetValue: Double?,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "rule_json") val ruleJson: String,
    @ColumnInfo(name = "unlocked_at") val unlockedAt: Long?,
    @ColumnInfo(name = "progress_value") val progressValue: Double?,
)

/** A timer seat joined to the player sitting in it. */
data class TimerSeatWithPlayer(
    @ColumnInfo(name = "seat_id") val seatId: Long,
    @ColumnInfo(name = "player_id") val playerId: Long,
    @ColumnInfo(name = "player_name") val playerName: String,
    @ColumnInfo(name = "color_hex") val colorHex: String?,
    @ColumnInfo(name = "seat_order") val seatOrder: Int,
    @ColumnInfo(name = "turn_remaining_ms") val turnRemainingMs: Long,
    @ColumnInfo(name = "bank_remaining_ms") val bankRemainingMs: Long,
    @ColumnInfo(name = "total_turn_time_ms") val totalTurnTimeMs: Long,
    @ColumnInfo(name = "turns_taken") val turnsTaken: Int,
    @ColumnInfo(name = "timed_out") val timedOut: Boolean,
    @ColumnInfo(name = "skipped") val skipped: Boolean,
)

data class PlayerRow(
    @Embedded val player: PlayerEntity,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "wins") val wins: Int,
)

/** A single scalar plus its label, used by the dashboard tiles. */
data class NamedTotal(
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "total") val total: Double,
)

/**
 * Row counts across every user-data table. The export/wipe/import round trip is only
 * correct if this comes back identical on both sides, so it is a value the app can
 * actually compare rather than something a human eyeballs.
 */
data class TableCountSummary(
    val games: Int,
    val tags: Int,
    val gameTags: Int,
    val players: Int,
    val sessions: Int,
    val sessionPlayers: Int,
    val sessionExpansions: Int,
    val rubrics: Int,
    val rubricCriteria: Int,
    val gameRatings: Int,
    val gameRatingScores: Int,
    val achievementUnlocks: Int,
) {
    val total: Int
        get() = games + tags + gameTags + players + sessions + sessionPlayers +
            sessionExpansions + rubrics + rubricCriteria + gameRatings +
            gameRatingScores + achievementUnlocks
}
