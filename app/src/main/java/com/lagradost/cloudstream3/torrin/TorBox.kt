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
 * - `POST /{v}/api/torrents/createtorrent`   body: magnet=... (form)
 *    -> `{data: {torrent_id, queued_id, hash}}`
 * - `GET  /{v}/api/torrents/mylist?id_={id}&bypass_cache=true`
 *    -> `{data: [{id, name, hash, download_state, download_finished, files: [...]}]}`
 * - `GET  /{v}/api/torrents/requestdl?token=***&torrent_id=...&file_id=...&redirect=false`
 *    -> `{data: "<cdn url>"}`
 * - Auth: `Authorization: Bearer *** key` (requestdl takes `token=` query param)
 */
object TorBox {

    private const val POLL_INTERVAL_MS = 3_000L

    private val FILE_HINT_PATTERN = Regex("[&?]cs_file=(\\d+)")
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

    @Serializable
    data class TbDlResponse(
        val data: String = "",
        val success: Boolean = false,
        val detail: String? = null,
    )

    /** True when the user enabled TorBox and provided an API key. */
    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(DebridPreferences.KEY_TORBOX_ENABLED, false) &&
            getApiKey(context).isNotBlank()
    }

    private fun getApiKey(context: Context): String =
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
     * Resolves a magnet link through TorBox and returns a playable direct-link
     * [ExtractorLink], or `null` when TorBox is not usable or the torrent
     * could not be completed in time. Never throws.
     */
    suspend fun transformLink(context: Context, link: ExtractorLink): ExtractorLink? {
        if (link.type != ExtractorLinkType.MAGNET) return null
        if (!isEnabled(context)) return null
        val apiKey = getApiKey(context)
        val baseUrl = getBaseUrl()
        val timeoutMs = getTimeoutSeconds(context) * 1000L

        return try {
            val (cleanMagnet, preferredFile) = parseFileHint(link.url)
            val infoHash = INFO_HASH_PATTERN.find(cleanMagnet)
                ?.groupValues?.get(1)?.uppercase(Locale.ROOT)
                ?: run {
                    logError("no info hash in magnet: ${link.url}")
                    return null
                }
            val created = createTorrent(baseUrl, apiKey, cleanMagnet)
                ?: return null
            if (created.torrent_id <= 0 && (created.queued_id ?: 0.0) <= 0) {
                logError("createtorrent returned no torrent id (hash $infoHash)")
                return null
            }
            val torrentId = created.torrent_id.takeIf { it > 0 } ?: created.queued_id!!
            val finished = waitForCompletion(baseUrl, apiKey, torrentId, infoHash, timeoutMs)
                ?: return null
            buildStreamLink(apiKey, finished, preferredFile)
        } catch (t: Throwable) {
            logError("failed to resolve ${link.url}", t)
            null
        }
    }

    /**
     * Plugins may append a `cs_file=<index>` hint to a magnet to ask for a
     * specific file of a multi-file release (e.g. one episode of a season
     * pack). The hint is stripped before submission and used for file
     * selection after the download completes.
     */
    private fun parseFileHint(magnet: String): Pair<String, Int?> {
        val match = FILE_HINT_PATTERN.find(magnet) ?: return magnet to null
        var clean = magnet.replace(match.value, "")
        if (clean.endsWith("&")) clean = clean.dropLast(1)
        if (clean.endsWith("?")) clean = clean.dropLast(1)
        return clean to match.groupValues[1].toInt()
    }

    private fun logError(message: String) {
        Log.w("TorBox", message)
    }

    private fun logError(message: String, throwable: Throwable) {
        Log.w("TorBox", message, throwable)
    }

    private suspend fun createTorrent(
        baseUrl: String,
        apiKey : String,
        magnet: String
    ): TbCreateData? {
        val response = app.post(
            url = "$baseUrl/torrents/createtorrent",
            headers = authHeaders(apiKey),
            data = mapOf("magnet" to magnet),
        )
        if (!response.isSuccessful) {
            logError("createtorrent failed with code ${response.code}: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<TbCreateResponse>()
        if (parsed == null) {
            logError("createtorrent returned an unparsable body: ${response.text}")
            return null
        }
        if (!parsed.success) {
            logError("createtorrent rejected: ${parsed.detail}")
            return null
        }
        return parsed.data
    }

    private suspend fun getTorrent(
        baseUrl: String,
        apiKey : String,
        torrentId: Double
    ): TbTorrent? {
        val id = if (torrentId % 1.0 == 0.0) torrentId.toLong().toString() else torrentId.toString()
        val response = app.get(
            url = "$baseUrl/torrents/mylist?id_=$id&bypass_cache=true",
            headers = authHeaders(apiKey),
        )
        if (!response.isSuccessful) {
            logError("mylist failed with code ${response.code}: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<TbListResponse>() ?: return null
        return parsed.data?.firstOrNull { it.id == torrentId }
    }

    /**
     * Polls the torrent until its download completes, it fails, or the
     * timeout is reached. Returns `null` when the torrent can no longer
     * produce a stream.
     */
    private suspend fun waitForCompletion(
        baseUrl: String,
        apiKey : String,
        torrentId: Double,
        infoHash: String,
        timeoutMs: Long,
    ): TbTorrent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState: String? = null
        while (true) {
            val torrent = getTorrent(baseUrl, apiKey, torrentId)
            if (torrent != null) {
                if (torrent.download_finished) return torrent
                if (torrent.download_state.lowercase(Locale.ROOT) in FAILED_STATES) {
                    logError("torrent $torrentId (hash $infoHash) ended with state '${torrent.download_state}'")
                    return null
                }
                if (torrent.download_state != lastState) {
                    Log.i(
                        "TorBox",
                        "torrent $torrentId (hash $infoHash) state '${torrent.download_state}' progress ${torrent.progress}"
                    )
                    lastState = torrent.download_state
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                logError("torrent $torrentId timed out after ${timeoutMs}ms (state '${lastState ?: "unknown"}')")
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
        apiKey : String,
        torrent: TbTorrent,
        preferredFile: Int?
    ): ExtractorLink? {
        val files = torrent.files.orEmpty()
        if (files.isEmpty()) {
            logError("torrent ${torrent.id} completed but has no files")
            return null
        }
        val best = selectFile(files, preferredFile)
            ?: run {
                logError("no video-like file in torrent ${torrent.id}")
                return null
            }
        val url = requestDownloadLink(apiKey, torrent.id, best.id)
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
        apiKey : String,
        torrentId: Double,
        fileId: Double
    ): String? {
        val response = app.get(
            url = buildString {
                append(DebridPreferences.DEFAULT_TORBOX_BASE_URL)
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
            logError("requestdl failed with code ${response.code}: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<TbDlResponse>()
        if (parsed == null || !parsed.success) {
            logError("requestdl rejected: ${parsed?.detail}")
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
