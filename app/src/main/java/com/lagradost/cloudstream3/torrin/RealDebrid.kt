package com.lagradost.cloudstream3.torrin

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.DebridPreferences
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Real-Debrid (https://real-debrid.com) debrid integration.
 *
 * Submits the magnet of an [ExtractorLink] to the Real-Debrid API,
 * waits for the torrent to be added and processed, then unrestricts
 * the link to get a direct HTTPS stream URL.
 *
 * API reference (https://api.real-debrid.com):
 * - `POST /rest/1.0/torrents/addMagnet`   body: magnet=... -> {id, uri}
 * - `GET  /rest/1.0/torrents/info/{id}`   -> {id, status, links, files}
 * - `POST /rest/1.0/unrestrict/link`      body: link=... -> {download, host, ...}
 * - Auth: `Authorization: Bearer *** key on every request
 */
object RealDebrid {

    /** Cache namespace, so a link resolved here is never handed to another service. */
    private const val SOURCE = "Real-Debrid"

    private const val POLL_INTERVAL_MS = 2_000L
    private const val MAX_RETRIES = 3
    private const val RETRY_BASE_DELAY_MS = 1_000L
    private const val BASE_URL = "https://api.real-debrid.com"

    private val FILE_HINT_PATTERN = Regex("""[&?]cs_file=(\d+)""")
    private val CS_HINT_PATTERN = Regex("""[&?]cs_[A-Za-z0-9]+=[^&]*""")
    private val INFO_HASH_PATTERN =
        Regex("urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})")

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts"
    )

    // Status values from Real-Debrid API
    private const val STATUS_MAGNET_ERROR = "magnet_error"
    private const val STATUS_MAGNET_CONVERSION = "magnet_conversion"
    private const val STATUS_WAITING_FILES_SELECTION = "waiting_files_selection"
    private const val STATUS_QUEUED = "queued"
    private const val STATUS_DOWNLOADING = "downloading"
    private const val STATUS_DOWNLOADED = "downloaded"
    private const val STATUS_ERROR = "error"
    private const val STATUS_VIRUS = "virus"
    private const val STATUS_COMPRESSING = "compressing"
    private const val STATUS_UPLOADING = "uploading"
    private const val STATUS_DEAD = "dead"

    private val FAILED_STATUSES = setOf(
        STATUS_MAGNET_ERROR, STATUS_ERROR, STATUS_VIRUS, STATUS_DEAD
    )

    @Serializable
    data class RdAddMagnetResponse(
        val id: String = "",
        val uri: String = "",
    )

    @Serializable
    data class RdFile(
        val id: Int = 0,
        val path: String = "",
        val bytes: Long = 0,
        val selected: Int = 0,
    )

    @Serializable
    data class RdTorrentInfo(
        val id: String = "",
        val filename: String = "",
        val status: String = "",
        val progress: Int = 0,
        val links: List<String> = emptyList(),
        val files: List<RdFile> = emptyList(),
    )

    @Serializable
    data class RdUnrestrictResponse(
        val id: String = "",
        val filename: String = "",
        val download: String = "",
        val host: String = "",
        val host_icon: String? = null,
        val filesize: Long = 0,
    )

    @Serializable
    data class RdError(
        val error: Int = 0,
        val error_code: Int = 0,
        val message: String = "",
    )

    @Serializable
    data class RdUserResponse(
        val id: Int = 0,
        val email: String = "",
        val username: String = "",
        val points: Int = 0,
        val locale: String = "",
        val avatar: String? = null,
        val type: String = "",
        val premium: Int = 0,
        val expiration: String? = null,
    )

    /** True when the user enabled Real-Debrid and provided an API key. */
    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(DebridPreferences.KEY_REALDEBRID_ENABLED, false) &&
            getApiKey(context).isNotBlank()
    }

    fun getApiKey(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(DebridPreferences.KEY_REALDEBRID_API_KEY, null)
            ?.trim()
            .orEmpty()

    private fun getTimeoutSeconds(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(
                DebridPreferences.KEY_REALDEBRID_TIMEOUT_SECONDS,
                DebridPreferences.DEFAULT_TIMEOUT_SECONDS
            ).toLong().coerceIn(10L, 600L)

    private fun authHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey"
    )

    /**
     * Tests the Real-Debrid API connection by fetching user info.
     * Returns a Pair of (success, message).
     */
    suspend fun testConnection(context: Context): Pair<Boolean, String> {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) return false to "No API key configured"

        return try {
            val response = app.get(
                url = "$BASE_URL/rest/1.0/user",
                headers = authHeaders(apiKey),
            )
            if (!response.isSuccessful) {
                return false to "Connection failed: HTTP ${response.code}"
            }
            val parsed = response.parsedSafe<RdUserResponse>()
            if (parsed == null) {
                return false to "Invalid response from server"
            }
            val premium = if (parsed.premium > 0) "Premium" else "Free"
            val expires = parsed.expiration ?: "N/A"
            true to "Connected!\nUser: ${parsed.username}\nType: $premium\nExpires: $expires"
        } catch (t: Throwable) {
            DebridLogger.realDebridW(context, "testConnection failed", t)
            false to "Connection error: ${t.message}"
        }
    }

    /**
     * Resolves a magnet link through Real-Debrid and returns a playable direct-link
     * [ExtractorLink], or `null` when Real-Debrid is not usable or the torrent
     * could not be completed in time. Never throws.
     */
    suspend fun transformLink(context: Context, link: ExtractorLink): ExtractorLink? {
        if (link.type != ExtractorLinkType.MAGNET) return null
        if (!isEnabled(context)) return null

        val startTime = System.currentTimeMillis()
        val apiKey = getApiKey(context)
        val timeoutMs = getTimeoutSeconds(context) * 1000L

        return try {
            val (cleanMagnet, preferredFile) = parseFileHint(link.url)
            val infoHash = INFO_HASH_PATTERN.find(cleanMagnet)
                ?.groupValues?.get(1)?.uppercase(Locale.ROOT)

            // 1. Check local cache first
            if (infoHash != null) {
                DebridCache.get(infoHash, SOURCE)?.let { cached ->
                    DebridLogger.cacheD(context, "Cache hit for $infoHash")
                    DebridLogger.logDuration(context, "RealDebrid", "Cache hit", startTime)
                    return cached
                }
                DebridLogger.cacheD(context, "Cache miss for $infoHash")
            }

            // 2. Add magnet to Real-Debrid
            DebridLogger.realDebridD(context, "Adding magnet to Real-Debrid")
            val torrentId = addMagnetWithRetry(context, apiKey, cleanMagnet) ?: return null
            DebridLogger.realDebridD(context, "Torrent added with id: $torrentId")

            // 3. Wait for torrent to be ready
            val torrentInfo = waitForReady(context, apiKey, torrentId, timeoutMs) ?: return null

            // 4. Select files if needed
            if (torrentInfo.status == STATUS_WAITING_FILES_SELECTION) {
                selectFiles(context, apiKey, torrentId, torrentInfo, preferredFile)
            }

            // 5. Wait for download to complete
            val readyTorrent = waitForDownload(context, apiKey, torrentId, timeoutMs) ?: return null

            // 6. Unrestrict the link
            val streamLink = unrestrictLink(context, apiKey, readyTorrent, preferredFile)
                ?: return null

            // 7. Cache the result
            if (infoHash != null) {
                DebridCache.put(infoHash, SOURCE, streamLink)
            }
            DebridLogger.logDuration(context, "RealDebrid", "Full resolution", startTime)

            streamLink
        } catch (t: Throwable) {
            DebridLogger.realDebridW(context, "failed to resolve ${link.url}", t)
            null
        }
    }

    private fun parseFileHint(magnet: String): Pair<String, Int?> {
        val match = FILE_HINT_PATTERN.find(magnet)
        var clean = CS_HINT_PATTERN.replace(magnet, "")
        if (clean.endsWith("&")) clean = clean.dropLast(1)
        if (clean.endsWith("?")) clean = clean.dropLast(1)
        if (match == null) return clean to null
        return clean to match.groupValues[1].toInt()
    }

    private suspend fun addMagnetWithRetry(
        context: Context,
        apiKey: String,
        magnet: String
    ): String? {
        var lastError: String? = null
        for (attempt in 1..MAX_RETRIES) {
            val result = addMagnet(context, apiKey, magnet)
            if (result != null) return result

            lastError = "Attempt $attempt/$MAX_RETRIES failed"
            DebridLogger.realDebridD(context, lastError)

            if (attempt < MAX_RETRIES) {
                val delay = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                delay(delay)
            }
        }
        DebridLogger.realDebridW(context, "addMagnet failed after $MAX_RETRIES attempts: $lastError")
        return null
    }

    private suspend fun addMagnet(context: Context, apiKey: String, magnet: String): String? {
        val response = app.post(
            url = "$BASE_URL/rest/1.0/torrents/addMagnet",
            headers = authHeaders(apiKey),
            data = mapOf("magnet" to magnet),
        )
        if (!response.isSuccessful) {
            DebridLogger.realDebridW(context, "addMagnet failed with code ${response.code}: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<RdAddMagnetResponse>()
        if (parsed?.id.isNullOrBlank()) {
            DebridLogger.realDebridW(context, "addMagnet returned no id: ${response.text}")
            return null
        }
        return parsed.id
    }

    private suspend fun getTorrentInfo(apiKey: String, id: String): RdTorrentInfo? {
        val response = app.get(
            url = "$BASE_URL/rest/1.0/torrents/info/$id",
            headers = authHeaders(apiKey),
        )
        if (!response.isSuccessful) return null
        return response.parsedSafe<RdTorrentInfo>()
    }

    private suspend fun waitForReady(
        context: Context,
        apiKey: String,
        torrentId: String,
        timeoutMs: Long
    ): RdTorrentInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val info = getTorrentInfo(apiKey, torrentId) ?: return null
            DebridLogger.realDebridD(context, "Torrent $torrentId status: ${info.status}")

            when (info.status) {
                STATUS_WAITING_FILES_SELECTION, STATUS_DOWNLOADED -> return info
                in FAILED_STATUSES -> {
                    DebridLogger.realDebridW(context, "Torrent $torrentId failed with status: ${info.status}")
                    return null
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                DebridLogger.realDebridW(context, "Torrent $torrentId timed out")
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun selectFiles(
        context: Context,
        apiKey: String,
        torrentId: String,
        info: RdTorrentInfo,
        preferredFile: Int?
    ) {
        // Select the preferred file or the largest video file
        val filesToSelect = if (preferredFile != null && preferredFile < info.files.size) {
            listOf(preferredFile)
        } else {
            // Find largest video file
            val videoFiles = info.files.filter { file ->
                val ext = file.path.substringAfterLast('.').lowercase(Locale.ROOT)
                ext in VIDEO_EXTENSIONS
            }
            if (videoFiles.isNotEmpty()) {
                listOf(videoFiles.maxByOrNull { it.bytes }?.id ?: 1)
            } else {
                // Just select first file
                listOf(1)
            }
        }

        val filesParam = filesToSelect.joinToString(",")
        val response = app.post(
            url = "$BASE_URL/rest/1.0/torrents/selectFiles/$torrentId",
            headers = authHeaders(apiKey),
            data = mapOf("files" to filesParam),
        )
        if (!response.isSuccessful) {
            DebridLogger.realDebridW(context, "selectFiles failed: ${response.text}")
        }
    }

    private suspend fun waitForDownload(
        context: Context,
        apiKey: String,
        torrentId: String,
        timeoutMs: Long
    ): RdTorrentInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val info = getTorrentInfo(apiKey, torrentId) ?: return null
            DebridLogger.realDebridD(context, "Torrent $torrentId status: ${info.status}, progress: ${info.progress}%")

            when (info.status) {
                STATUS_DOWNLOADED -> return info
                in FAILED_STATUSES -> {
                    DebridLogger.realDebridW(context, "Torrent $torrentId failed with status: ${info.status}")
                    return null
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                DebridLogger.realDebridW(context, "Torrent $torrentId timed out waiting for download")
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun unrestrictLink(
        context: Context,
        apiKey: String,
        info: RdTorrentInfo,
        preferredFile: Int?
    ): ExtractorLink? {
        val links = info.links
        if (links.isEmpty()) {
            DebridLogger.realDebridW(context, "Torrent ${info.id} has no links")
            return null
        }

        // Try to unrestrict the first link
        val linkToUnrestrict = links.first()
        val response = app.post(
            url = "$BASE_URL/rest/1.0/unrestrict/link",
            headers = authHeaders(apiKey),
            data = mapOf("link" to linkToUnrestrict),
        )
        if (!response.isSuccessful) {
            DebridLogger.realDebridW(context, "unrestrict failed: ${response.text}")
            return null
        }
        val parsed = response.parsedSafe<RdUnrestrictResponse>()
        if (parsed == null || parsed.download.isBlank()) {
            DebridLogger.realDebridW(context, "unrestrict returned no download URL")
            return null
        }

        val name = parsed.filename.ifBlank { info.filename.ifBlank { "Real-Debrid" } }
        return newExtractorLink(
            source = "Real-Debrid",
            name = name,
            url = parsed.download,
            type = ExtractorLinkType.VIDEO,
        )
    }
}
