package com.boardgamenation.tracker.domain.model

/** Playtime filter buckets, expressed against the game's stated maximum playtime. */
enum class PlaytimeBucket(val minMinutes: Int, val maxMinutes: Int) {
    UNDER_30(0, 29),
    THIRTY_TO_60(30, 60),
    SIXTY_TO_120(61, 120),
    OVER_120(121, Int.MAX_VALUE),
}

enum class CollectionSort {
    TITLE, DATE_ADDED, PLAY_COUNT, RATING, PRICE, COST_PER_PLAY, LAST_PLAYED, WEIGHT
}

/** How the collection list is displayed. */
enum class CollectionLayout { LIST, GRID }

/**
 * Every filter the collection list can apply. Held as one value so the screen state is
 * a single object and the SQL builder has one input.
 */
data class CollectionFilter(
    val search: String = "",
    val statuses: Set<GameStatus> = emptySet(),

    /** Games playable with exactly this many players. */
    val playerCount: Int? = null,
    val playtime: PlaytimeBucket? = null,

    /** Matches games carrying any of these tags. */
    val tagIds: Set<Long> = emptySet(),

    /** null means "don't care"; true only rated, false only unrated. */
    val rated: Boolean? = null,
    val inPossessionOnly: Boolean = false,
    val includeExpansions: Boolean = true,

    val sort: CollectionSort = CollectionSort.TITLE,
    val ascending: Boolean = true,
) {
    val isActive: Boolean
        get() = search.isNotBlank() || statuses.isNotEmpty() || playerCount != null ||
            playtime != null || tagIds.isNotEmpty() || rated != null ||
            inPossessionOnly || !includeExpansions

    /** How many chips are lit, for the "clear filters" affordance. */
    val activeCount: Int
        get() = listOf(
            statuses.isNotEmpty(),
            playerCount != null,
            playtime != null,
            tagIds.isNotEmpty(),
            rated != null,
            inPossessionOnly,
            !includeExpansions,
        ).count { it }
}
