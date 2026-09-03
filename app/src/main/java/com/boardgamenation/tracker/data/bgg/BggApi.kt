package com.boardgamenation.tracker.data.bgg

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The three XML API2 endpoints this app uses.
 *
 * Responses come back as raw bodies rather than typed models: BGG returns XML, and the
 * parsing is done by [BggXmlParser] with a pull parser. Retrofit is here for the HTTP
 * plumbing, not for deserialisation.
 */
interface BggApi {

    @GET("search")
    suspend fun search(@Query("query") query: String, @Query("type") type: String = "boardgame,boardgameexpansion"): Response<ResponseBody>

    /** Up to 20 ids per call, comma separated. */
    @GET("thing")
    suspend fun things(@Query("id") ids: String, @Query("stats") stats: Int = 1): Response<ResponseBody>

    /**
     * Queues on BGG's side. A 202 means "ask again shortly" rather than an error, and is
     * handled with backoff in [BggRepository].
     */
    @GET("collection")
    suspend fun collection(
        @Query("username") username: String,
        @Query("own") own: Int = 1,
        @Query("stats") stats: Int = 1,
        @Query("subtype") subtype: String = "boardgame"
    ): Response<ResponseBody>

    @GET("collection")
    suspend fun collectionAll(@Query("username") username: String, @Query("stats") stats: Int = 1): Response<ResponseBody>
}

/**
 * Serialises every BGG request through a single permit and enforces a minimum gap
 * between them.
 *
 * Community consensus puts the ceiling somewhere around two requests a second, but a
 * personal collection tracker has no reason to go anywhere near that. Two seconds
 * between calls costs nothing on a one-off import and keeps this app a good citizen of
 * a service it depends on and does not pay for.
 */
@Singleton
class BggRateLimiter @Inject constructor() {

    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun <T> throttle(block: suspend () -> T): T = mutex.withLock {
        val now = System.currentTimeMillis()
        val since = now - lastRequestAt
        if (lastRequestAt != 0L && since < MIN_GAP_MS) {
            delay(MIN_GAP_MS - since)
        }
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    companion object {
        const val MIN_GAP_MS = 2_000L
    }
}
