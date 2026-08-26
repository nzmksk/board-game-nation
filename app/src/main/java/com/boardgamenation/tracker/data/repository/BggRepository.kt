package com.boardgamenation.tracker.data.repository

import android.content.Context
import com.boardgamenation.tracker.BuildConfig
import com.boardgamenation.tracker.core.time.AppClock
import com.boardgamenation.tracker.core.time.DateUtils
import com.boardgamenation.tracker.data.bgg.BggApi
import com.boardgamenation.tracker.data.bgg.BggCollectionItem
import com.boardgamenation.tracker.data.bgg.BggError
import com.boardgamenation.tracker.data.bgg.BggRateLimiter
import com.boardgamenation.tracker.data.bgg.BggSearchResult
import com.boardgamenation.tracker.data.bgg.BggThing
import com.boardgamenation.tracker.data.bgg.BggXmlParser
import com.boardgamenation.tracker.data.db.dao.BggCacheDao
import com.boardgamenation.tracker.data.db.dao.GameDao
import com.boardgamenation.tracker.data.db.entity.BggThingCacheEntity
import com.boardgamenation.tracker.data.db.entity.GameEntity
import com.boardgamenation.tracker.di.IoDispatcher
import com.boardgamenation.tracker.domain.model.GameStatus
import com.boardgamenation.tracker.domain.model.TagKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Progress reported while a collection import runs. */
sealed interface BggImportProgress {
    data class Queued(val attempt: Int, val retryInSeconds: Int) : BggImportProgress
    data class Fetching(val message: String) : BggImportProgress
    data class Enriching(val done: Int, val total: Int) : BggImportProgress
    data class Complete(val items: List<BggCollectionItem>) : BggImportProgress
}

@Singleton
class BggRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: BggApi,
    private val parser: BggXmlParser,
    private val rateLimiter: BggRateLimiter,
    private val cacheDao: BggCacheDao,
    private val gameDao: GameDao,
    private val gameRepository: GameRepository,
    private val httpClient: OkHttpClient,
    private val clock: AppClock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Whether BGG features are available at all.
     *
     * False is a perfectly normal state, not an error: the app is designed so that
     * everything BGG would supply can be typed in by hand or imported from CSV.
     */
    val isConfigured: Boolean get() = BuildConfig.BGG_CONFIGURED

    suspend fun search(query: String): List<BggSearchResult> = withContext(io) {
        requireConfigured()
        val body = call { api.search(query.trim()) }
        parser.parseSearch(body)
    }

    /**
     * Fetches metadata, preferring the cache.
     *
     * Cached bodies are kept for 30 days and are never refetched unless [forceRefresh]
     * says so. Metadata about a published board game does not change often enough to
     * justify asking again every time a detail screen opens.
     */
    suspend fun things(ids: List<Long>, forceRefresh: Boolean = false): List<BggThing> =
        withContext(io) {
            requireConfigured()
            if (ids.isEmpty()) return@withContext emptyList()

            val notBefore = clock.nowMillis() - CACHE_TTL_MS
            val cached = mutableListOf<BggThing>()
            val missing = mutableListOf<Long>()

            ids.distinct().forEach { id ->
                val hit = if (forceRefresh) null else cacheDao.getFresh(id, notBefore)
                if (hit != null) {
                    cached += parser.parseThings(hit.xml)
                } else {
                    missing += id
                }
            }

            // Batched 20 at a time, which is what the endpoint accepts, so importing a
            // 200-game collection is ten requests rather than two hundred.
            missing.chunked(BATCH_SIZE).forEach { batch ->
                val body = call { api.things(batch.joinToString(",")) }
                val parsed = parser.parseThings(body)
                parsed.forEach { thing ->
                    cacheDao.put(
                        BggThingCacheEntity(
                            bggId = thing.bggId,
                            // The slice for one id keeps a cache entry independent of the
                            // batch it happened to arrive in.
                            xml = sliceForId(body, thing.bggId) ?: body,
                            fetchedAt = clock.nowMillis(),
                        ),
                    )
                }
                cached += parsed
            }
            cached.distinctBy { it.bggId }
        }

    /**
     * Downloads a user's collection.
     *
     * The endpoint queues: a 202 means the collection is being prepared and the request
     * should be repeated. Backoff starts at three seconds and doubles, up to eight
     * attempts, with progress surfaced so the user is not staring at a dead spinner.
     */
    suspend fun fetchCollection(
        username: String,
        ownedOnly: Boolean = true,
        onProgress: suspend (BggImportProgress) -> Unit = {},
    ): List<BggCollectionItem> = withContext(io) {
        requireConfigured()
        var delayMs = QUEUE_INITIAL_DELAY_MS

        repeat(QUEUE_MAX_ATTEMPTS) { attempt ->
            onProgress(BggImportProgress.Fetching(username))
            val response = rateLimiter.throttle {
                if (ownedOnly) api.collection(username.trim()) else api.collectionAll(username.trim())
            }

            when {
                response.code() == 202 -> {
                    onProgress(
                        BggImportProgress.Queued(
                            attempt = attempt + 1,
                            retryInSeconds = (delayMs / 1000).toInt(),
                        ),
                    )
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(QUEUE_MAX_DELAY_MS)
                }

                response.isSuccessful -> {
                    val body = response.body()?.string().orEmpty()
                    if (parser.isQueuedResponse(body)) {
                        onProgress(
                            BggImportProgress.Queued(
                                attempt = attempt + 1,
                                retryInSeconds = (delayMs / 1000).toInt(),
                            ),
                        )
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(QUEUE_MAX_DELAY_MS)
                    } else {
                        val items = parser.parseCollection(body)
                        onProgress(BggImportProgress.Complete(items))
                        return@withContext items
                    }
                }

                else -> throw errorFor(response)
            }
        }
        throw BggError.StillQueued
    }

    /**
     * Turns BGG metadata into collection rows.
     *
     * Existing games are matched on bgg_id and updated in place, so re-importing a
     * collection refreshes metadata rather than creating duplicates. Anything the user
     * has typed themselves — price, purchase notes, status — is left alone.
     */
    suspend fun importThings(
        things: List<BggThing>,
        status: GameStatus = GameStatus.OWNED,
        downloadImages: Boolean = true,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Int = withContext(io) {
        var imported = 0
        things.forEachIndexed { index, thing ->
            onProgress(index + 1, things.size)
            val existing = gameDao.getGameByBggId(thing.bggId)
            val thumbnail = if (downloadImages) {
                cacheThumbnail(thing.bggId, thing.thumbnailUrl) ?: existing?.thumbnailPath
            } else {
                existing?.thumbnailPath
            }

            val gameId = if (existing != null) {
                gameRepository.updateGame(
                    existing.copy(
                        title = thing.name,
                        yearPublished = thing.yearPublished ?: existing.yearPublished,
                        minPlayers = thing.minPlayers ?: existing.minPlayers,
                        maxPlayers = thing.maxPlayers ?: existing.maxPlayers,
                        bestPlayerCount = thing.bestPlayerCount ?: existing.bestPlayerCount,
                        minPlaytimeMinutes = thing.minPlaytimeMinutes ?: existing.minPlaytimeMinutes,
                        maxPlaytimeMinutes = thing.maxPlaytimeMinutes ?: existing.maxPlaytimeMinutes,
                        weight = thing.weight ?: existing.weight,
                        bggRating = thing.rating ?: existing.bggRating,
                        designers = thing.designers.takeIf { it.isNotEmpty() }?.joinToString(", ")
                            ?: existing.designers,
                        publisher = thing.publishers.firstOrNull() ?: existing.publisher,
                        thumbnailPath = thumbnail,
                        isExpansion = thing.isExpansion,
                    ),
                )
                existing.id
            } else {
                val now = clock.nowMillis()
                gameRepository.addGame(
                    GameEntity(
                        bggId = thing.bggId,
                        title = thing.name,
                        yearPublished = thing.yearPublished,
                        minPlayers = thing.minPlayers,
                        maxPlayers = thing.maxPlayers,
                        bestPlayerCount = thing.bestPlayerCount,
                        minPlaytimeMinutes = thing.minPlaytimeMinutes,
                        maxPlaytimeMinutes = thing.maxPlaytimeMinutes,
                        weight = thing.weight,
                        bggRating = thing.rating,
                        designers = thing.designers.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        publisher = thing.publishers.firstOrNull(),
                        thumbnailPath = thumbnail,
                        dateAdded = DateUtils.toIso(clock.today()),
                        status = status,
                        isExpansion = thing.isExpansion,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }

            val mechanicIds = gameRepository.resolveTags(thing.mechanics, TagKind.MECHANIC)
            val categoryIds = gameRepository.resolveTags(thing.categories, TagKind.CATEGORY)
            gameDao.replaceTags(gameId, mechanicIds + categoryIds)
            imported++
        }

        // Second pass: an expansion can only be linked once its base game exists locally.
        linkExpansions(things)
        imported
    }

    private suspend fun linkExpansions(things: List<BggThing>) {
        things.filter { it.isExpansion && it.expandsBggIds.isNotEmpty() }.forEach { thing ->
            val expansion = gameDao.getGameByBggId(thing.bggId) ?: return@forEach
            val base = thing.expandsBggIds.firstNotNullOfOrNull { gameDao.getGameByBggId(it) }
                ?: return@forEach
            gameRepository.updateGame(expansion.copy(baseGameId = base.id))
        }
    }

    /**
     * Fetches a thumbnail once and stores it in app-private storage.
     *
     * The list never renders from a remote url: at scroll time the only thing touched is
     * a local file, which is what keeps a 500-game collection scrolling smoothly and
     * working offline.
     */
    suspend fun cacheThumbnail(bggId: Long, url: String?): String? = withContext(io) {
        if (url.isNullOrBlank()) return@withContext null
        val directory = File(context.filesDir, THUMBNAIL_DIR).apply { mkdirs() }
        val target = File(directory, "$bggId.jpg")
        if (target.exists() && target.length() > 0) return@withContext target.absolutePath

        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target.absolutePath
        } catch (_: Exception) {
            // A missing cover is cosmetic. It must never fail an import.
            target.delete()
            null
        }
    }

    // --- plumbing -------------------------------------------------------------------

    private fun requireConfigured() {
        if (!isConfigured) throw BggError.NotConfigured
    }

    /**
     * One request, with throttling and retry.
     *
     * Retries on 429 and 5xx with exponential backoff and gives up after five attempts,
     * surfacing a retryable error. Nothing here ever swallows a failure and returns an
     * empty list, which would look identical to a genuinely empty result.
     */
    private suspend fun call(request: suspend () -> Response<ResponseBody>): String {
        var delayMs = RETRY_INITIAL_DELAY_MS
        var lastError: BggError? = null

        repeat(MAX_ATTEMPTS) {
            val response = try {
                rateLimiter.throttle { request() }
            } catch (e: Exception) {
                lastError = BggError.Network(e.message ?: e::class.simpleName.orEmpty())
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_DELAY_MS)
                return@repeat
            }

            if (response.isSuccessful) {
                val body = response.body()?.string().orEmpty()
                parser.errorMessage(body)?.let { message ->
                    if (!parser.isQueuedResponse(body)) throw BggError.Malformed(message)
                }
                return body
            }

            val error = errorFor(response)
            if (!error.retryable) throw error
            lastError = error
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_DELAY_MS)
        }

        throw lastError ?: BggError.Server(0)
    }

    private fun errorFor(response: Response<ResponseBody>): BggError = when (response.code()) {
        401, 403 -> BggError.Unauthorized
        429 -> BggError.RateLimited
        in 500..599 -> BggError.Server(response.code())
        else -> BggError.Server(response.code())
    }

    /** Extracts one item element from a batched response, for per-id caching. */
    private fun sliceForId(xml: String, id: Long): String? {
        val marker = "id=\"$id\""
        val at = xml.indexOf(marker).takeIf { it >= 0 } ?: return null
        val start = xml.lastIndexOf("<item", at).takeIf { it >= 0 } ?: return null
        val end = xml.indexOf("</item>", at).takeIf { it >= 0 } ?: return null
        return "<items>" + xml.substring(start, end + "</item>".length) + "</items>"
    }

    suspend fun clearCache() = withContext(io) { cacheDao.clear() }

    suspend fun cacheSize(): Int = withContext(io) { cacheDao.count() }

    companion object {
        private const val BATCH_SIZE = 20
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_INITIAL_DELAY_MS = 2_000L
        private const val RETRY_MAX_DELAY_MS = 32_000L
        private const val QUEUE_INITIAL_DELAY_MS = 3_000L
        private const val QUEUE_MAX_DELAY_MS = 60_000L
        private const val QUEUE_MAX_ATTEMPTS = 8
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val THUMBNAIL_DIR = "thumbnails"
    }
}
