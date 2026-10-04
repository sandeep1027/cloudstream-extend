package com.lagradost.cloudstream3.torrin

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.DebridPreferences
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * TorBox (https://torbox.app) debrid integration.
 *
 * Submits the magnet of an [ExtractorLink] to the TorBox API, polls the
 * torrent until its download completes (or it is served from cache), then
 * requests a CDN download link and returns a new [ExtractorLink] pointing at
 * it so the video plays directly.
 *
 * API reference (TorBox-App/torbox-sdk-*):
 * - `POST /{v}/api/torrents/createtorrent`   body: multipart/form-data with magnet=...
 *    -> `{data: {torrent_id, queued_id, hash}}`
 * - `GET  /{v}/api/torrents/mylist?id={id}&bypass_cache=true`
 *    -> `{data: {id, name, hash, download_state, download_finished, files: [...]}}`  (single object when id= is passed)
 * - `GET  /{v}/api/torrents/requestdl?token=***&torrent_id=...&file_id=...&redirect=false`
 *    -> `{data: "<cdn url>"}`
 * - `GET  /{v}/api/torrents/checkcached?hash=...` -> check if torrent is already cached
 * - `GET  /{v}/api/user/me` -> account info, used by the connection test
 * - Auth: `Authorization: Bearer *** key` (requestdl takes `token=` query param)
 */
object TorBox {

    /** Cache namespace, so a link resolved here is never handed to another service. */
    private const val SOURCE = "TorBox"

    private const val POLL_INTERVAL_MS = 3_000L
    private const val MAX_RETRIES = 3
    private const val RETRY_BASE_DELAY_MS = 1_000L

    private val FILE_HINT_PATTERN = Regex("""[&?]cs_file=(\d+)""")
    // Strips every client-side cs_* hint (cs_file, cs_debrid, ...) so only
    // the raw magnet reaches the debrid API.
    private val CS_HINT_PATTERN = Regex("""[&?]cs_[A-Za-z0-9]+=[^&]*""")
    private val INFO_HASH_PATTERN =
        Regex("urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})")

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts"
    )

    // States in which the torrent will never produce a stream.
    private val FAILED_STATES = setOf("stalled (no seeds)", "paused", "removed")

    @Serializable
    data class TbFile(
        val id: Double = 0.0,
        val name: String = "",
        val size: Double = 0.0,
        val mimetype: String = "",
    )

    @Serializable
    data class TbTorrent(
        val id: Double = 0.0,
        val name: String = "",
        val hash: String = "",
        val download_state: String = "",
        val download_finished: Boolean = false,
        val download_present: Boolean = false,
        val progress: Double = 0.0,
        val files: List<TbFile>? = null,
    )

    @Serializable
    data class TbCreateData(
        val torrent_id: Double = 0.0,
        val queued_id: Double? = null,
        val hash: String = "",
    )

    @Serializable
    data class TbCreateResponse(
        val data: TbCreateData? = null,
        val success: Boolean = false,
        val detail: String? = null,
    )

    @Serializable
    data class TbListResponse(
        val data: List<TbTorrent>? = null,
        val success: Boolean = false,
        val detail: String? = null,
    )

    // Response when mylist is called with ?id= (returns single object, not list)
    @Serializable
    data class TbSingleResponse(
        val data: TbTorrent? = null,
        val success: Boolean = false,
        val detail: String? = null,
    )

    @Serializable
    data class TbDlResponse(
        val data: String = "",
        val success: Boolean = false,
        val detail: String? = null,
    )

    // Cache check response
    @Serializable
    data class TbCacheCheckData(
        val hash: String = "",
        val cached: Boolean = false,
        val files: List<TbFile>? = null,
        val instant: Boolean = false,
    )

    @Serializable
    data class TbCacheCheckResponse(
        val data: Map<String, TbCacheCheckData>? = null,
        val success: Boolean = false,
        val detail: String? = null,
    )

    // User info response for test connection
    @Serializable
    data class TbUserData(
        val email: String = "",
        val premium: Int = 0,
        val expires_at: String? = null,
        val current_plan: String? = null,
        val total_used: Double = 0.0,
        val total_data: Double = 0.0,
    )

    @Serializable
    data class TbUserResponse(
        val data: TbUserData? = null,
        val success: Boolean = false,
        val detail: String? = null,
    )

    /** True when the user enabled TorBox and provided an API key. */
    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(DebridPreferences.KEY_TORBOX_ENABLED, false) &&
            getApiKey(context).isNotBlank()
    }

    fun getApiKey(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(DebridPreferences.KEY_TORBOX_API_KEY, null)
            ?.trim()
            .orEmpty()

    private fun getBaseUrl(): String = DebridPreferences.DEFAULT_TORBOX_BASE_URL

    private fun getTimeoutSeconds(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(
                DebridPreferences.KEY_TORBOX_TIMEOUT_SECONDS,
                DebridPreferences.DEFAULT_TIMEOUT_SECONDS
            ).toLong().coerceIn(10L, 600L)

    private fun authHeaders(apiKey : String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey"
    )

    /**
     * Tests the TorBox API connection by fetching user info.
     * Returns a Pair of (success, message).
     */
    suspend fun testConnection(context: Context): Pair<Boolean, String> {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) return false to "No API key configured"

        val baseUrl = getBaseUrl()
        return try {
            val response = app.get(
                url = "$baseUrl/user/me",
                headers = authHeaders(apiKey),
            )
            if (!response.isSuccessful) {
                return false to "Connection failed: HTTP ${response.code}"
            }
            val parsed = response.parsedSafe<TbUserResponse>()
            if (parsed == null || !parsed.success || parsed.data == null) {
                return false to "Invalid response from server"
            }
            val user = parsed.data
            val premium = if (user.premium > 0) "Premium" else "Free"
            val plan = user.current_plan ?: "Unknown"
            val expires = user.expires_at?.takeIf { it.isNotBlank() } ?: "N/A"
            true to "Connected!\nPlan: $premium ($plan)\nExpires: $expires"
        } catch (t: Throwable) {
            DebridLogger.torboxW(context, "testConnection failed", t)
            false to "Connection error: ${t.message}"
        }
    }

/**
 * Pre-checks whether TorBox already holds this torrent, which is what
 * `GET /torrents/checkcached` reports (TorBox's route for Real-Debrid's
 * `/torrents/instantAvailability/{hash}`). Returns the cached entry when it is
 * held, null otherwise.
 */
    private suspend fun checkCached(
        context: Context,
        baseUrl: String,
        apiKey: String,
        infoHash: String
    ): TbCacheCheckData? {
        return try {
            val response = app.get(
                url = "$baseUrl/torrents/checkcached?hash=$infoHash",
                headers = authHeaders(apiKey),
            )
            if (!response.isSuccessful) return null
            val parsed = response.parsedSafe<TbCacheCheckResponse>() ?: return null
            if (!parsed.success) return null
            // Response is a map keyed by hash
            return parsed.data?.values?.firstOrNull { it.cached || it.instant }
        } catch (t: Throwable) {
            DebridLogger.torboxW(context, "checkCached failed for $infoHash", t)
            null
        }
    }

    /**
     * Resolves a magnet link through TorBox and returns a playable direct-link
     * [ExtractorLink], or `null` when TorBox is not usable or the torrent
     * could not be completed in time. Never throws.
     */
    suspend fun transformLink(context: Context, link: ExtractorLink): ExtractorLink? {
        if (link.type != ExtractorLinkType.MAGNET) return null
        if (!isEnabled(context)) return null

        val startTime = System.currentTimeMillis()
        val apiKey = getApiKey(context)
        val baseUrl = getBaseUrl()
        val timeoutMs = getTimeoutSeconds(context) * 1000L

        return try {
            val (cleanMagnet, preferredFile) = parseFileHint(link.url)
            val infoHash = INFO_HASH_PATTERN.find(cleanMagnet)
                ?.groupValues?.get(1)?.uppercase(Locale.ROOT)
                ?: run {
                    DebridLogger.torboxW(context, "no info hash in magnet: ${link.url}")
                    return null
                }

            // 1. Check local cache first
            DebridCache.get(infoHash, SOURCE)?.let { cached ->
                DebridLogger.cacheD(context, "Cache hit for $infoHash")
                DebridLogger.logDuration(context, "TorBox", "Cache hit", startTime)
                return cached
            }
            DebridLogger.cacheD(context, "Cache miss for $infoHash")

            // 2. Check whether TorBox already holds this torrent
            DebridLogger.torboxD(context, "Checking cache availability for $infoHash")
            val cachedOnServer = checkCached(context, baseUrl, apiKey, infoHash)
            val servedFromCache = cachedOnServer != null
            if (servedFromCache) {
                DebridLogger.torboxD(
                    context,
                    "TorBox already has $infoHash (instant=${cachedOnServer.instant})"
                )
            }

            // 3. Submit to TorBox. For a torrent it already holds this returns the
            // existing id and reports it finished, so there is nothing to wait for.
            val created = createTorrentWithRetry(context, baseUrl, apiKey, cleanMagnet)
                ?: return null
            if (created.torrent_id <= 0 && (created.queued_id ?: 0.0) <= 0) {
                DebridLogger.torboxW(context, "createtorrent returned no torrent id (hash $infoHash)")
                return null
            }
            val torrentId = created.torrent_id.takeIf { it > 0 } ?: created.queued_id!!
            DebridLogger.torboxD(context, "Created torrent $torrentId for hash $infoHash")

            // 4. Wait for completion. A cached torrent comes back finished on the
            // first poll, so ask once and only start looping if it is not.
            val finished = if (servedFromCache) {
                val torrent = getTorrent(context, baseUrl, apiKey, torrentId)
                when {
                    torrent == null -> {
                        DebridLogger.torboxW(
                            context,
                            "cached torrent $torrentId (hash $infoHash) could not be read back"
                        )
                        return null
                    }
                    torrent.download_finished -> torrent
                    torrent.download_state.lowercase(Locale.ROOT) in FAILED_STATES -> {
                        DebridLogger.torboxW(
                            context,
                            "cached torrent $torrentId (hash $infoHash) reported state '${torrent.download_state}'"
                        )
                        return null
                    }
                    // The cache check said it had it but the download is still
                    // running; fall through to the normal wait rather than failing.
                    else -> {
                        DebridLogger.torboxD(
                            context,
                            "cached torrent $torrentId is still downloading, waiting"
                        )
                        waitForCompletion(context, baseUrl, apiKey, torrentId, infoHash, timeoutMs)
                            ?: return null
                    }
                }
            } else {
                waitForCompletion(context, baseUrl, apiKey, torrentId, infoHash, timeoutMs)
                    ?: return null
            }

            // 5. Get stream link
            val streamLink = buildStreamLink(context, baseUrl, apiKey, finished, preferredFile)
                ?: return null

            // 6. Cache the result
            DebridCache.put(infoHash, SOURCE, streamLink)
            DebridLogger.logDuration(
                context,
                "TorBox",
                if (servedFromCache) "Cached resolution" else "Full resolution",
                startTime
            )

            streamLink
        } catch (t: Throwable) {
            DebridLogger.torboxW(context, "failed to resolve ${link.url}", t)
            null
        }
    }

    /**
     * Plugins may append a `cs_file=<index>` hint to a magnet to ask for a
     * specific file of a multi-file release (e.g. one episode of a season
     * pack). Client-side hints (`cs_file`, `cs_debrid`, ...) are all
     * stripped before submission; the file index is kept for file
     * selection after the download completes.
     */
    private fun parseFileHint(magnet: String): Pair<String, Int?> {
        val match = FILE_HINT_PATTERN.find(magnet)
        var clean = CS_HINT_PATTERN.replace(magnet, "")
        if (clean.endsWith("&")) clean = clean.dropLast(1)
        if (clean.endsWith("?")) clean = clean.dropLast(1)
        if (match == null) return clean to null
        return clean to match.groupValues[1].toInt()
    }

    /**
     * Creates a torrent with retry logic for transient failures.
     */
    private suspend fun createTorrentWithRetry(
        context: Context,
        baseUrl: String,
        apiKey: String,
        magnet: String
    ): TbCreateData? {
        var lastError: String? = null
        for (attempt in 1..MAX_RETRIES) {
            val result = createTorrent(context, baseUrl, apiKey, magnet)
            if (result != null) return result

            lastError = "Attempt $attempt/$MAX_RETRIES failed"
            DebridLogger.torboxD(context, lastError)

            if (attempt < MAX_RETRIES) {
                // Exponential backoff: 1s, 2s, 4s
                val delay = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                delay(delay)
            }
        }
        DebridLogger.torboxW(context, "createTorrent failed after $MAX_RETRIES attempts: $lastError")
        return null
    }

    private suspend fun createTorrent(
        context: Context,
        baseUrl: String,
        apiKey: String,
        magnet: String
    ): TbCreateData? {
        val response = app.post(
            url = "$baseUrl/torrents/createtorrent",
            headers = authHeaders(apiKey),
            data = mapOf("magnet" to magnet),
        )
        if (!response.isSuccessful) {
            DebridLogger.torboxW(context, "createtorrent failed with code ${response.code}: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<TbCreateResponse>()
        if (parsed == null) {
            DebridLogger.torboxW(context, "createtorrent returned an unparsable body: ${response.text}")
            return null
        }
        if (!parsed.success) {
            DebridLogger.torboxW(context, "createtorrent rejected: ${parsed.detail}")
            return null
        }
        return parsed.data
    }

    private suspend fun getTorrent(
        context: Context,
        baseUrl: String,
        apiKey : String,
        torrentId: Double
    ): TbTorrent? {
        val id = if (torrentId % 1.0 == 0.0) torrentId.toLong().toString() else torrentId.toString()
        val response = app.get(
            url = "$baseUrl/torrents/mylist?id=$id&bypass_cache=true",
            headers = authHeaders(apiKey),
        )
        if (!response.isSuccessful) {
            DebridLogger.torboxW(context, "mylist failed with code ${response.code}: ${response.text}")
            return null
        }
        // When ?id= is passed, the API returns a single object, not a list
        val parsed = response.parsedSafe<TbSingleResponse>() ?: return null
        return parsed.data
    }

    /**
     * Polls the torrent until its download completes, it fails, or the
     * timeout is reached. Returns `null` when the torrent can no longer
     * produce a stream.
     */
    private suspend fun waitForCompletion(
        context: Context,
        baseUrl: String,
        apiKey : String,
        torrentId: Double,
        infoHash: String,
        timeoutMs: Long,
    ): TbTorrent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState: String? = null
        while (true) {
            val torrent = getTorrent(context, baseUrl, apiKey, torrentId)
            if (torrent != null) {
                if (torrent.download_finished) return torrent
                if (torrent.download_state.lowercase(Locale.ROOT) in FAILED_STATES) {
                    DebridLogger.torboxW(context, "torrent $torrentId (hash $infoHash) ended with state '${torrent.download_state}'")
                    return null
                }
                if (torrent.download_state != lastState) {
                    DebridLogger.torboxI(context, "torrent $torrentId (hash $infoHash) state '${torrent.download_state}' progress ${torrent.progress}")
                    lastState = torrent.download_state
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                DebridLogger.torboxW(context, "torrent $torrentId timed out after ${timeoutMs}ms (state '${lastState ?: "unknown"}')")
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Requests the CDN link for the selected file of a finished torrent and
     * wraps it into a playable [ExtractorLink].
     */
    private suspend fun buildStreamLink(
        context: Context,
        baseUrl: String,
        apiKey : String,
        torrent: TbTorrent,
        preferredFile: Int?
    ): ExtractorLink? {
        val files = torrent.files.orEmpty()
        if (files.isEmpty()) {
            DebridLogger.torboxW(context, "torrent ${torrent.id} completed but has no files")
            return null
        }
        val best = selectFile(files, preferredFile)
            ?: run {
                DebridLogger.torboxW(context, "no video-like file in torrent ${torrent.id}")
                return null
            }
        val url = requestDownloadLink(context, baseUrl, apiKey, torrent.id, best.id)
            ?: return null
        if (url.isBlank()) return null
        val name = best.name.ifBlank { torrent.name.ifBlank { "TorBox" } }
        return newExtractorLink(
            source = "TorBox",
            name = name,
            url = url,
            type = ExtractorLinkType.VIDEO,
        )
    }

    /**
     * Requests the CDN link for a file of a finished torrent. TorBox
     * authenticates this route with the `token=` query parameter and opens
     * the link for a few hours once the download starts.
     */
    private suspend fun requestDownloadLink(
        context: Context,
        baseUrl: String,
        apiKey : String,
        torrentId: Double,
        fileId: Double
    ): String? {
        val response = app.get(
            url = buildString {
                append(baseUrl.trimEnd('/'))
                append("/torrents/requestdl")
                append("?token=***")
                append(apiKey)
                append("&torrent_id=")
                append(idQuery(torrentId))
                append("&file_id=")
                append(idQuery(fileId))
                append("&redirect=false")
            }
        )
        if (!response.isSuccessful) {
            DebridLogger.torboxW(context, "requestdl failed with code ${response.code}: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<TbDlResponse>()
        if (parsed == null || !parsed.success) {
            DebridLogger.torboxW(context, "requestdl rejected: ${parsed?.detail}")
            return null
        }
        return parsed.data
    }

    /** TorBox ids are floats in JSON; integral values read cleaner as longs. */
    private fun idQuery(id: Double): String =
        if (id % 1.0 == 0.0) id.toLong().toString() else id.toString()

    /** Picks the file to stream: the hinted index first (episode selection),
     * then the largest video-like file, then the largest file of all. */
    private fun selectFile(files: List<TbFile>, preferred: Int?): TbFile? {
        if (files.isEmpty()) return null
        files.getOrNull(preferred ?: -1)?.let { return it }
        return files
            .filter {
                it.mimetype.startsWith("video") ||
                    it.name.substringAfterLast('.').lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.size }
            ?: files.maxByOrNull { it.size }
    }
}
