package com.boardgamenation.tracker.domain.achievement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape of every achievement rule.
 *
 * This is one flat record with optional fields rather than a polymorphic hierarchy on
 * purpose: the whole point is that adding an achievement means editing a bundled JSON
 * asset, not writing Kotlin. A flat schema stays forgiving of hand-edited JSON, and an
 * unknown [type] degrades to "never unlocks" instead of crashing at startup.
 */
@Serializable
data class AchievementRule(
    val type: RuleType,

    /** Which number the rule watches, for the counting rule types. */
    val metric: Metric = Metric.NONE,

    /** The number progress is compared against. */
    val target: Double = 0.0,

    /** RATIO only: how many plays before a win rate means anything. */
    val minPlays: Int = 0,

    /** STREAK and TIME_WINDOW: the unit of time being counted. */
    val period: Period = Period.NONE,

    /** ATTRIBUTE: which property of a single session or game must clear [target]. */
    val attribute: Attribute = Attribute.NONE,

    /** COLLECTION: which whole-collection condition must hold. */
    val scope: Scope = Scope.NONE,

    /** Flips the comparison for rules where smaller is better, such as cost-per-play. */
    val comparison: Comparison = Comparison.AT_LEAST,
)

@Serializable
enum class RuleType {
    /** A running total clears a threshold. */
    @SerialName("COUNT_THRESHOLD") COUNT_THRESHOLD,

    /** Any single game has been played N times. */
    @SerialName("PER_GAME_THRESHOLD") PER_GAME_THRESHOLD,

    /** Variety rather than volume: N distinct games, or N distinct mechanics. */
    @SerialName("BREADTH") BREADTH,

    /** N consecutive days or weeks with a play. */
    @SerialName("STREAK") STREAK,

    /** N plays inside one day, week, or month. */
    @SerialName("TIME_WINDOW") TIME_WINDOW,

    /** A single session or game clears a threshold on one property. */
    @SerialName("ATTRIBUTE") ATTRIBUTE,

    /** A win or loss rate over a meaningful number of plays. */
    @SerialName("RATIO") RATIO,

    /** A property of the collection as a whole. */
    @SerialName("COLLECTION") COLLECTION,

    /** Anything this build does not recognise. Never unlocks, never crashes. */
    @SerialName("UNKNOWN") UNKNOWN,
}

@Serializable
enum class Metric {
    @SerialName("NONE") NONE,
    @SerialName("TOTAL_PLAYS") TOTAL_PLAYS,
    @SerialName("TOTAL_HOURS") TOTAL_HOURS,
    @SerialName("GAMES_OWNED") GAMES_OWNED,
    @SerialName("DISTINCT_GAMES_PLAYED") DISTINCT_GAMES_PLAYED,
    @SerialName("DISTINCT_MECHANICS_PLAYED") DISTINCT_MECHANICS_PLAYED,
    @SerialName("GAMES_TAUGHT") GAMES_TAUGHT,
    @SerialName("GAMES_RATED") GAMES_RATED,
    @SerialName("DISTINCT_PLAYERS") DISTINCT_PLAYERS,
    @SerialName("WIN_STREAK") WIN_STREAK,
    @SerialName("LOSS_STREAK") LOSS_STREAK,
}

@Serializable
enum class Period {
    @SerialName("NONE") NONE,
    @SerialName("DAY") DAY,
    @SerialName("WEEK") WEEK,
    @SerialName("MONTH") MONTH,
}

@Serializable
enum class Attribute {
    @SerialName("NONE") NONE,
    @SerialName("SESSION_PLAYER_COUNT") SESSION_PLAYER_COUNT,
    @SerialName("SESSION_DURATION_HOURS") SESSION_DURATION_HOURS,
    @SerialName("GAME_WEIGHT") GAME_WEIGHT,
}

@Serializable
enum class Scope {
    @SerialName("NONE") NONE,

    /** Every owned game has been played at least once. */
    @SerialName("NO_UNPLAYED_GAMES") NO_UNPLAYED_GAMES,

    /** Some game has driven its cost-per-play below the target. */
    @SerialName("COST_PER_PLAY_UNDER") COST_PER_PLAY_UNDER,

    /** Every owned game carrying some one mechanic has been played. */
    @SerialName("MECHANIC_COMPLETED") MECHANIC_COMPLETED,
}

@Serializable
enum class Comparison {
    @SerialName("AT_LEAST") AT_LEAST,
    @SerialName("AT_MOST") AT_MOST,
}

/** One achievement exactly as it appears in the bundled JSON asset. */
@Serializable
data class AchievementDefinition(
    val code: String,
    val name: String,
    val description: String,
    val icon: String = "EmojiEvents",
    val category: String = "General",
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
    val rule: AchievementRule,
)
