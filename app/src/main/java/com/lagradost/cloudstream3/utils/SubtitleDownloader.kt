package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads and manages subtitles for downloaded content.
 *
 * Uses the same OpenSubtitles API key as [OpenSubtitlesApi] to avoid
 * registering duplicate applications with the OpenSubtitles service.
 * For full subtitle search + browse (with user authentication), use
 * [OpenSubtitlesApi] directly. This utility provides a lightweight
 * unauthenticated path for auto-downloading subtitles alongside
 * offline downloads.
 */
object SubtitleDownloader {

    private const val OPEN_SUBTITLES_API = "https://api.opensubtitles.com/api/v1"

    /**
     * Re-use the same API key as the main OpenSubtitles integration so
     * we register only one application with the service.
     */
    private val API_KEY: String = OpenSubtitlesApi.API_KEY
    private const val USER_AGENT = "Cloudstream3 v0.2"

    private val DEFAULT_HEADERS = mapOf(
        "Api-Key" to API_KEY,
        "Content-Type" to "application/json",
        "User-Agent" to USER_AGENT
    )

    @Serializable
    data class SubtitleResult(
        @SerialName("id") val id: String,
        @SerialName("attributes") val attributes: SubtitleAttributes
    )

    @Serializable
    data class SubtitleAttributes(
        @SerialName("subtitle_id") val subtitleId: String,
        @SerialName("language") val language: String,
        @SerialName("release") val release: String?,
        @SerialName("files") val files: List<SubtitleFile>?
    )

    @Serializable
    data class SubtitleFile(
        @SerialName("file_id") val fileId: Int,
        @SerialName("file_name") val fileName: String
    )

    @Serializable
    data class SubtitleDownloadLink(
        @SerialName("link") val link: String,
        @SerialName("file_name") val fileName: String,
        @SerialName("requests") val requests: Int
    )

    @Serializable
    data class SubtitleSearchResponse(
        @SerialName("data") val data: List<SubtitleResult>?,
        @SerialName("total_count") val totalCount: Int?
    )

    /**
     * Search for subtitles by IMDB ID.
     *
     * Uses the shared OpenSubtitles API key — does not require user login,
     * but is subject to stricter rate limits than authenticated requests.
     */
    suspend fun searchSubtitles(imdbId: String, language: String = "en"): List<SubtitleResult> {
        return try {
            val numericImdb = imdbId.removePrefix("tt")
            val response = app.get(
                url = "$OPEN_SUBTITLES_API/subtitles",
                headers = DEFAULT_HEADERS,
                params = mapOf(
                    "imdb_id" to numericImdb,
                    "languages" to language
                )
            )

            if (response.isSuccessful) {
                val result = tryParseJson<SubtitleSearchResponse>(response.text)
                result?.data ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Get download link for subtitle.
     *
     * Note: The OpenSubtitles /download endpoint requires authentication
     * for most users. When called without a Bearer token the API may
     * return a 401/403 — callers should handle this gracefully and fall
     * back to the full in-app subtitle browser ([OpenSubtitlesApi]).
     */
    suspend fun getDownloadLink(fileId: Int): String? {
        return try {
            val response = app.post(
                url = "$OPEN_SUBTITLES_API/download",
                headers = DEFAULT_HEADERS,
                json = mapOf("file_id" to fileId)
            )

            if (response.isSuccessful) {
                val result = tryParseJson<SubtitleDownloadLink>(response.text)
                result?.link
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download subtitle file for a downloaded item.
     *
     * Extracts the IMDB id from the download item's source URL or title,
     * searches OpenSubtitles, and saves the first matching subtitle to
     * the subtitles directory next to the downloaded video.
     */
    suspend fun downloadSubtitle(
        context: Context,
        downloadItem: DownloadItem,
        language: String = "en"
    ): File? {
        // Extract IMDB ID from download item source URL or episode metadata
        val imdbId = extractImdbId(downloadItem) ?: return null

        // Search for subtitles
        val subtitles = searchSubtitles(imdbId, language)
        if (subtitles.isEmpty()) return null

        // Get the first subtitle with files
        val subtitle = subtitles.firstOrNull { !it.attributes.files.isNullOrEmpty() } ?: return null
        val fileId = subtitle.attributes.files?.firstOrNull()?.fileId ?: return null

        // Get download link
        val downloadLink = getDownloadLink(fileId) ?: return null

        // Download the subtitle file
        return try {
            val response = app.get(downloadLink)
            if (response.isSuccessful) {
                val subtitleDir = File(context.getExternalFilesDir(null), "subtitles")
                if (!subtitleDir.exists()) subtitleDir.mkdirs()

                val itemId = downloadItem.ep.id
                val subtitleFile = File(subtitleDir, "${itemId}_$language.srt")
                FileOutputStream(subtitleFile).use { outputStream ->
                    outputStream.write(response.text.toByteArray())
                }

                subtitleFile
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract IMDB ID from download item source URL or metadata.
     */
    private fun extractImdbId(item: DownloadItem): String? {
        val patterns = listOf(
            Regex("tt\\d{7,8}"),
            Regex("imdb\\.com/title/(tt\\d+)"),
            Regex("imdb_id=(tt\\d+)")
        )

        // Check source URL
        item.source?.let { source ->
            for (pattern in patterns) {
                pattern.find(source)?.let { return it.value }
            }
        }

        // Check episode metadata name
        item.ep.name?.let { name ->
            for (pattern in patterns) {
                pattern.find(name)?.let { return it.value }
            }
        }

        // Check main name
        item.ep.mainName.let { mainName ->
            for (pattern in patterns) {
                pattern.find(mainName)?.let { return it.value }
            }
        }

        return null
    }
}
