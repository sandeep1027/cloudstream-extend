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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Torrin (https://torrin.app) debrid integration.
 *
 * Submits the magnet of a [ExtractorLink] to the Torrin API and polls the
 * resulting job until it is complete, then returns a new [ExtractorLink]
 * pointing at the signed HTTPS stream URL so the video can be played
 * directly instead of streaming through the local torrent engine.
 *
 * API reference (torrin-app/torrin):
 * - `POST /api/jobs`          body: {"magnet": "magnet:?xt=..."} -> job (200 when cached, 202 when queued)
 * - `GET  /api/jobs/{id}`     poll until `status == "complete"`
 * - Auth: `Authorization: Bearer *** key on every request
 */
object Torrin {

    private const val POLL_INTERVAL_MS = 2_000L

    private val FILE_HINT_PATTERN = Regex("[&?]cs_file=(\\d+)")

    private const val STATUS_COMPLETE = "complete"
    private const val STATUS_FAILED = "failed"
    private const val STATUS_EVICTED = "evicted"

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts", "m3u8"
    )

    @Serializable
    data class TorrinFile(
        val index: Int = 0,
        val name: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class TorrinStreamUrl(
        @SerialName("file_name")
        val fileName: String = "",
        @SerialName("signed_url")
        val signedUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class TorrinJob(
        val id: String = "",
        @SerialName("info_hash")
        val infoHash: String = "",
        val name: String = "",
        val status: String = "",
        val error: String? = null,
        val files: List<TorrinFile>? = null,
        @SerialName("stream_urls")
        val streamUrls: List<TorrinStreamUrl>? = null,
    )

    /** True when the user enabled Torrin and provided an API key. */
    fun isEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(DebridPreferences.KEY_ENABLED, false) &&
            getApiKey(context).isNotBlank()
    }

    private fun getApiKey(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(DebridPreferences.KEY_API_KEY, null)
            ?.trim()
            .orEmpty()

    private fun getBaseUrl(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(DebridPreferences.KEY_BASE_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.trimEnd('/')
            ?: DebridPreferences.DEFAULT_BASE_URL

    private fun getTimeoutSeconds(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(
                DebridPreferences.KEY_TIMEOUT_SECONDS,
                DebridPreferences.DEFAULT_TIMEOUT_SECONDS
            ).toLong().coerceIn(10L, 600L)

    private fun authHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey"
    )

    /**
     * Resolves a magnet link through Torrin and returns a playable direct-link
     * [ExtractorLink], or `null` when Torrin is not usable or the job could not
     * be completed in time. Never throws.
     */
    suspend fun transformLink(context: Context, link: ExtractorLink): ExtractorLink? {
        if (link.type != ExtractorLinkType.MAGNET) return null
        if (!isEnabled(context)) return null
        val apiKey = getApiKey(context)
        val baseUrl = getBaseUrl(context)
        val timeoutMs = getTimeoutSeconds(context) * 1000L

        return try {
            val (cleanMagnet, preferredFile) = parseFileHint(link.url)
            val job = submitJob(baseUrl, apiKey, cleanMagnet) ?: return null
            val completed = waitForCompletion(baseUrl, apiKey, job, timeoutMs) ?: return null
            buildStreamLink(completed, preferredFile)
        } catch (t: Throwable) {
            logError("failed to resolve ${link.url}", t)
            null
        }
    }

    /**
     * Plugins may append a `cs_file=<index>` hint to a magnet to ask for a
     * specific file of a multi-file release (e.g. one episode of a season
     * pack). The hint is stripped before submission and used for file
     * selection after the job completes.
     */
    private fun parseFileHint(magnet: String): Pair<String, Int?> {
        val match = FILE_HINT_PATTERN.find(magnet) ?: return magnet to null
        val clean = magnet.replace(match.value, "")
        return clean.toPair(match.groupValues[1].toInt())
    }

    private fun String.toPair(index: Int): Pair<String, Int> {
        var out = this
        if (out.endsWith("&")) out = out.dropLast(1)
        if (out.endsWith("?")) out = out.dropLast(1)
        return out to index
    }

    private fun logError(message: String) {
        Log.w("Torrin", message)
    }

    private fun logError(message: String, throwable: Throwable) {
        Log.w("Torrin", message, throwable)
    }

    private suspend fun submitJob(baseUrl: String, apiKey: String, magnet: String): TorrinJob? {
        val response = app.post(
            url = "$baseUrl/api/jobs",
            json = mapOf("magnet" to magnet),
            headers = authHeaders(apiKey),
        )
        if (!response.isSuccessful) {
            logError("submit job failed with code ${response.code}: ${response.text}")
            return null
        }
        val job = response.parsedSafe<TorrinJob>()
        if (job?.id.isNullOrBlank()) {
            logError("submit job returned an unparsable body: ${response.text}")
            return null
        }
        return job
    }

    private suspend fun getJob(baseUrl: String, apiKey: String, id: String): TorrinJob? {
        val response = app.get(
            url = "$baseUrl/api/jobs/$id",
            headers = authHeaders(apiKey),
        )
        if (!response.isSuccessful) {
            logError("job status check failed with code ${response.code}: ${response.text}")
            return null
        }
        return response.parsedSafe<TorrinJob>()
    }

    /**
     * Polls the job until it completes, fails, or the timeout is reached.
     * Returns `null` when the job can no longer produce a stream.
     */
    private suspend fun waitForCompletion(
        baseUrl: String,
        apiKey: String,
        initialJob: TorrinJob,
        timeoutMs: Long,
    ): TorrinJob? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var job = initialJob
        while (true) {
            when (job.status.lowercase(Locale.ROOT)) {
                STATUS_COMPLETE -> return job
                STATUS_FAILED, STATUS_EVICTED -> {
                    logError("job ${job.id} ended with status '${job.status}': ${job.error}")
                    return null
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                logError("job ${job.id} timed out after ${timeoutMs}ms (status '${job.status}')")
                return null
            }
            delay(POLL_INTERVAL_MS)
            // Keep the last known job if a status check fails transiently.
            job = getJob(baseUrl, apiKey, job.id) ?: job
        }
    }

    /**
     * Builds a playable [ExtractorLink] from the stream URLs of a completed
     * job. When [preferredFile] names a file of the release it is streamed
     * first (episode selection for multi-file torrents); otherwise the
     * largest video-like file wins.
     */
    private suspend fun buildStreamLink(job: TorrinJob, preferredFile: Int?): ExtractorLink? {
        val streams = job.streamUrls.orEmpty()
            .filter { it.signedUrl.isNotBlank() }
        if (streams.isEmpty()) {
            logError("job ${job.id} completed but has no stream URLs")
            return null
        }

        val preferredName = preferredFile
            ?.let { idx -> job.files.orEmpty().firstOrNull { it.index == idx }?.name }
            ?.takeIf { it.isNotBlank() }
        val preferred = preferredName
            ?.let { name -> streams.firstOrNull { it.fileName.equals(name, ignoreCase = true) } }

        // Otherwise prefer the largest video-like file, falling back to the
        // largest stream.
        val best = preferred
            ?: streams
                .filter { it.fileName.substringAfterLast('.').lowercase(Locale.ROOT) in VIDEO_EXTENSIONS }
                .maxByOrNull { it.size }
            ?: streams.maxByOrNull { it.size }
            ?: streams.first()

        val extension = best.fileName.substringAfterLast('.').lowercase(Locale.ROOT)
        val streamType = if (extension == "m3u8" ||
            best.signedUrl.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT) == "m3u8"
        ) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        val name = best.fileName.ifBlank { job.name }.ifBlank { "Torrin" }
        return newExtractorLink(
            source = "Torrin",
            name = name,
            url = best.signedUrl,
            type = streamType,
        )
    }
}
