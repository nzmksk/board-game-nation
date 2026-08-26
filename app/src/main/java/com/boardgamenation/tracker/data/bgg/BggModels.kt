package com.boardgamenation.tracker.data.bgg

/** A hit from the name search endpoint. Deliberately thin: search returns very little. */
data class BggSearchResult(
    val bggId: Long,
    val name: String,
    val yearPublished: Int?,
    val isExpansion: Boolean,
)

/** Full metadata for one thing. */
data class BggThing(
    val bggId: Long,
    val name: String,
    val yearPublished: Int? = null,
    val minPlayers: Int? = null,
    val maxPlayers: Int? = null,
    val minPlaytimeMinutes: Int? = null,
    val maxPlaytimeMinutes: Int? = null,
    val weight: Double? = null,
    val rating: Double? = null,
    val designers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val mechanics: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val imageUrl: String? = null,
    val isExpansion: Boolean = false,

    /** Derived from the suggested-players poll, e.g. "3–4". */
    val bestPlayerCount: String? = null,

    /** For expansions: the things this expands. */
    val expandsBggIds: List<Long> = emptyList(),
)

/** One row of a user's BGG collection. */
data class BggCollectionItem(
    val bggId: Long,
    val name: String,
    val yearPublished: Int?,
    val thumbnailUrl: String?,
    val owned: Boolean,
    val forTrade: Boolean,
    val wantToPlay: Boolean,
    val wishlist: Boolean,
    val wishlistPriority: Int?,
    val preordered: Boolean,
    val numPlays: Int,
    val isExpansion: Boolean,
)

/**
 * Why a BGG call failed, in terms the UI can act on rather than a raw exception.
 *
 * Nothing here fails silently: every case carries a message the settings or import screen
 * can show, and [retryable] says whether offering a retry button is honest.
 */
sealed class BggError(message: String, val retryable: Boolean) : Exception(message) {
    /** No token configured. Every BGG feature is switched off, by design, not by fault. */
    data object NotConfigured : BggError("BoardGameGeek is not configured", retryable = false)

    data object Unauthorized : BggError("BoardGameGeek rejected the token", retryable = false)

    data object RateLimited : BggError("BoardGameGeek is rate limiting requests", retryable = true)

    data class Network(val detail: String) :
        BggError("Could not reach BoardGameGeek: $detail", retryable = true)

    data class Server(val code: Int) :
        BggError("BoardGameGeek returned $code", retryable = true)

    /** The collection endpoint queues; it answered 202 too many times. */
    data object StillQueued :
        BggError("BoardGameGeek is still preparing that collection", retryable = true)

    data class Malformed(val detail: String) :
        BggError("Could not read BoardGameGeek's response: $detail", retryable = false)
}
