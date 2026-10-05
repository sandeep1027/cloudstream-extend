package com.lagradost.cloudstream3.torrin

import android.content.Context
import androidx.annotation.WorkerThread
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
 * TorBox's Web Downloads service: a hosted file link handed over from one of the
 * hosters TorBox supports, rather than a torrent.
 *
 * A plugin marks such a link with a query hint rather than needing a new link
 * type, which keeps the plugin API unchanged:
 *
 * + `?cs_webdl=1` resolve it, downloading it if TorBox does not have it yet
 * + `?cs_webdl=only_cached` resolve it only if TorBox already has it, otherwise
 *   fail immediately instead of spending bandwidth and time
 *
 * The hint is removed before the link reaches TorBox. Resolution mirrors the
 * torrent flow in [TorBox]: create, poll until the download finishes, then ask
 * for a CDN link.
 *
 * TorBox publishes no schema for these responses in its OpenAPI document (they
 * are `{}` there), so the shapes come from the official SDK's models
 * (`WebDownloadsDebridService`, `CreateWebDownloadOkResponse`,
 * `GetWebDownloadListOkResponse`, `RequestDownloadLinkOkResponse`) and the parts
 * that could be observed live are pinned by TorBoxApiShapeTest.
 */
object TorBoxWebDl {

    private const val SOURCE = "TorBoxWebDl"

    /** The hint a plugin puts on the link, and the values it accepts. */
    const val HINT_PARAM = "cs_webdl"
    private const val HINT_VALUE = "1"
    private const val HINT_ONLY_CACHED = "only_cached"

    private const val POLL_INTERVAL_MS = 3_000L

    private val HINT_PATTERN = Regex("""[?&]cs_webdl=[^&]*""")

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "mpg", "mpeg", "ts", "m2ts"
    )

    /** States in which the download will never produce a link. */
    private val FAILED_STATES = setOf("failed", "error", "stalled", "removed")

    @Serializable
    internal data class WebDlFile(
        @SerialName("id") val id: Double = 0.0,
        @SerialName("name") val name: String = "",
        @SerialName("size") val size: Double = 0.0,
        @SerialName("mimetype") val mimetype: String = "",
        @SerialName("short_name") val short_name: String = "",
    )

    @Serializable
    internal data class WebDlItem(
        @SerialName("id") val id: Double = 0.0,
        @SerialName("name") val name: String = "",
        @SerialName("hash") val hash: String = "",
        @SerialName("auth_id") val auth_id: String = "",
        @SerialName("download_state") val download_state: String = "",
        @SerialName("download_finished") val download_finished: Boolean = false,
        @SerialName("download_present") val download_present: Boolean = false,
        @SerialName("progress") val progress: Double = 0.0,
        @SerialName("error") val error: String? = null,
        @SerialName("files") val files: List<WebDlFile>? = null,
    )

    @Serializable
    internal data class CreateData(
        // The SDK types this as a string even though requestdl wants an integer,
        // so it is passed through untouched rather than converted.
        @SerialName("webdownload_id") val webdownload_id: String = "",
        @SerialName("hash") val hash: String = "",
        @SerialName("auth_id") val auth_id: String = "",
    )

    @Serializable
    internal data class CreateResponse(
        @SerialName("data") val data: CreateData? = null,
        @SerialName("success") val success: Boolean = false,
        @SerialName("detail") val detail: String? = null,
        @SerialName("error") val error: String? = null,
    )

    @Serializable
    internal data class ListResponse(
        @SerialName("data") val data: List<WebDlItem>? = null,
        @SerialName("success") val success: Boolean = false,
        @SerialName("detail") val detail: String? = null,
    )

    @Serializable
    internal data class DlResponse(
        @SerialName("data") val data: String = "",
        @SerialName("success") val success: Boolean = false,
        @SerialName("detail") val detail: String? = null,
    )

    /**
     * What a plugin asked for: the link with the hint removed, and whether to
     * refuse anything TorBox does not already hold.
     */
    data class Request(val link: String, val onlyIfCached: Boolean)

    /** True when [url] carries the web download hint. */
    fun hasHint(url: String): Boolean = HINT_PATTERN.containsMatchIn(url)

    /**
     * Removes the hint and repairs the query string around it. The pattern eats
     * the "?" or "&" that introduced the parameter, so a hint in first position
     * would otherwise leave "...&a=1" with no query at all.
     */
    private fun normaliseQuery(head: String): String {
        var stripped = HINT_PATTERN.replace(head, "")
        if (!stripped.contains('?')) {
            val ampersand = stripped.indexOf('&')
            if (ampersand >= 0) {
                stripped =
                    stripped.substring(0, ampersand) + "?" + stripped.substring(ampersand + 1)
            }
        }
        stripped = stripped.replace("&&", "&")
        val queryAt = stripped.indexOf('?')
        if (queryAt < 0) return stripped.trimEnd('&')
        val path = stripped.substring(0, queryAt)
        val query = stripped.substring(queryAt + 1).trimEnd('&')
        return if (query.isBlank()) path else "$path?$query"
    }

    /**
     * Splits the hint off [url]. Returns null when there is no hint, in which
     * case the link is not a web download and must be played as it is.
     */
    fun parseRequest(url: String): Request? {
        val match = HINT_PATTERN.find(url) ?: return null
        val value = match.value.substringAfter('=').trim().lowercase()

        // The fragment has to come off before the query is split, or an emptied
        // query would leave a bare "?" in front of it.
        val fragmentAt = url.indexOf('#')
        val head = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val clean = normaliseQuery(head)

        // An unknown value is still a request to use the web downloader, but it
        // must not silently mean "cached only".
        val onlyIfCached = value == HINT_ONLY_CACHED
        return Request(clean + fragment, onlyIfCached = onlyIfCached)
    }

    /** True when the user enabled TorBox and provided an API key. */
    fun isEnabled(context: Context): Boolean = TorBox.isEnabled(context)

    private fun getApiKey(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(DebridPreferences.KEY_TORBOX_API_KEY, null)
            ?.trim()
            .orEmpty()

    private fun getTimeoutMs(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(
                DebridPreferences.KEY_TORBOX_TIMEOUT_SECONDS,
                DebridPreferences.DEFAULT_TIMEOUT_SECONDS
            )
            .toLong().coerceIn(10L, 600L) * 1000L

    private fun baseUrl() = DebridPreferences.DEFAULT_TORBOX_BASE_URL

    private fun authHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")

    /**
     * Resolves a hinted link through TorBox's Web Downloads service and returns a
     * playable link, or null when it could not be resolved. Never throws.
     */
    @WorkerThread
    suspend fun transformLink(context: Context, link: ExtractorLink): ExtractorLink? {
        if (!isEnabled(context)) {
            DebridLogger.torboxW(context, "webdl requested but TorBox is not enabled")
            return null
        }
        val request = parseRequest(link.url) ?: return null
        if (request.link.isBlank()) return null

        val startTime = System.currentTimeMillis()
        val apiKey = getApiKey(context)
        val base = baseUrl()
        val timeoutMs = getTimeoutMs(context)

        return try {
            val created = create(context, base, apiKey, request) ?: return null
            if (created.webdownload_id.isBlank()) {
                DebridLogger.torboxW(context, "createwebdownload returned no webdownload_id")
                return null
            }
            DebridLogger.torboxD(
                context,
                "webdl ${created.webdownload_id} created (onlyIfCached=${request.onlyIfCached})"
            )

            val finished = waitForFinished(context, base, apiKey, created.webdownload_id, timeoutMs)
                ?: return null

            val best = selectFile(finished.files.orEmpty())
            if (best == null) {
                DebridLogger.torboxW(context, "webdl ${created.webdownload_id} has no usable file")
                return null
            }
            val url = requestDownloadLink(context, base, apiKey, created.webdownload_id, best)
                ?: return null

            val streamLink = newExtractorLink(
                source = SOURCE,
                name = best.name.ifBlank { finished.name.ifBlank { link.name } },
                url = url,
                type = ExtractorLinkType.VIDEO,
            )
            DebridCache.put(request.link, SOURCE, streamLink)
            DebridLogger.logDuration(context, "TorBox", "Webdl resolution", startTime)
            streamLink
        } catch (t: Throwable) {
            DebridLogger.torboxW(context, "webdl resolution failed for ${link.url}", t)
            null
        }
    }

    private suspend fun create(
        context: Context,
        baseUrl: String,
        apiKey: String,
        request: Request,
    ): CreateData? {
        val form = buildMap {
            put("link", request.link)
            put("name", request.link.substringAfterLast('/').substringBefore('?'))
            if (request.onlyIfCached) put("add_only_if_cached", "true")
        }
        val response = app.post(
            url = "$baseUrl/webdl/createwebdownload",
            headers = authHeaders(apiKey),
            data = form,
        )
        val parsed = response.parsedSafe<CreateResponse>()
        if (!response.isSuccessful) {
            // A cached-only miss comes back as HTTP 500 with
            // error=DOWNLOAD_NOT_CACHED, so the body is the only place the reason
            // is stated. Reading it first is the difference between a usable log
            // and "HTTP 500".
            DebridLogger.torboxW(
                context,
                "createwebdownload failed with code ${response.code}" +
                    (parsed?.error?.let { ": $it" } ?: "") +
                    (parsed?.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            )
            return null
        }
        if (parsed == null) {
            DebridLogger.torboxW(
                context,
                "createwebdownload returned an unparsable body: ${response.text}"
            )
            return null
        }
        if (!parsed.success) {
            DebridLogger.torboxW(context, "createwebdownload rejected: ${parsed.detail}")
            return null
        }
        return parsed.data
    }

    private suspend fun waitForFinished(
        context: Context,
        baseUrl: String,
        apiKey: String,
        webdownloadId: String,
        timeoutMs: Long,
    ): WebDlItem? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState: String? = null
        while (true) {
            // Unlike /torrents/mylist, this one always answers with a list.
            val item = getItems(context, baseUrl, apiKey, webdownloadId)?.firstOrNull()
            if (item != null) {
                if (item.download_finished) return item
                val state = item.download_state.lowercase(Locale.ROOT)
                if (state in FAILED_STATES || item.error?.isNotBlank() == true) {
                    DebridLogger.torboxW(
                        context,
                        "webdl $webdownloadId ended with state '${item.download_state}'" +
                            (item.error?.let { " ($it)" } ?: "")
                    )
                    return null
                }
                if (item.download_state != lastState) {
                    DebridLogger.torboxI(
                        context,
                        "webdl $webdownloadId state '${item.download_state}'" +
                            " progress ${item.progress}"
                    )
                    lastState = item.download_state
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                DebridLogger.torboxW(
                    context,
                    "webdl $webdownloadId timed out after ${timeoutMs}ms" +
                        " (state '${lastState ?: "unknown"}')"
                )
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun getItems(
        context: Context,
        baseUrl: String,
        apiKey: String,
        webdownloadId: String,
    ): List<WebDlItem>? {
        val response = app.get(
            url = "$baseUrl/webdl/mylist?id=$webdownloadId&bypass_cache=true",
            headers = authHeaders(apiKey),
        )
        if (!response.isSuccessful) {
            DebridLogger.torboxW(
                context,
                "webdl mylist failed with code ${response.code}: ${response.text}"
            )
            return null
        }
        val parsed = response.parsedSafe<ListResponse>() ?: return null
        if (!parsed.success) {
            DebridLogger.torboxW(context, "webdl mylist rejected: ${parsed.detail}")
            return null
        }
        return parsed.data
    }

    private suspend fun requestDownloadLink(
        context: Context,
        baseUrl: String,
        apiKey: String,
        webdownloadId: String,
        file: WebDlFile,
    ): String? {
        val response = app.get(
            url = buildString {
                append(baseUrl.trimEnd('/'))
                append("/webdl/requestdl")
                append("?token=")
                append(apiKey)
                append("&web_id=")
                append(webdownloadId)
                append("&file_id=")
                append(idQuery(file.id))
                append("&redirect=false")
            }
        )
        if (!response.isSuccessful) {
            DebridLogger.torboxW(
                context,
                "webdl requestdl failed with code ${response.code}: ${response.text}"
            )
            return null
        }
        val parsed = response.parsedSafe<DlResponse>()
        if (parsed == null || !parsed.success) {
            DebridLogger.torboxW(context, "webdl requestdl rejected: ${parsed?.detail}")
            return null
        }
        return parsed.data.takeIf { it.isNotBlank() }
    }

    private fun selectFile(files: List<WebDlFile>): WebDlFile? {
        if (files.isEmpty()) return null
        files.firstOrNull { it.short_name.isNotBlank() }?.let { return it }
        return files
            .filter {
                it.mimetype.startsWith("video") ||
                    it.name.substringAfterLast('.').lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
            }
            .maxByOrNull { it.size }
            ?: files.maxByOrNull { it.size }
    }

    private fun idQuery(id: Double): String =
        if (id % 1.0 == 0.0) id.toLong().toString() else id.toString()
}