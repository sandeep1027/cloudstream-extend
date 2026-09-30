package com.lagradost.cloudstream3.utils.trakt

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.providers.TraktApi
import com.lagradost.cloudstream3.syncproviders.providers.TraktApi.ScrobbleBody
import com.lagradost.cloudstream3.syncproviders.providers.TraktApi.ScrobbleIds
import com.lagradost.cloudstream3.syncproviders.providers.TraktApi.ScrobbleMedia
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dispatches realtime scrobble events to Trakt while the player is active.
 *
 * Lifecycle of a scrobble session:
 *   1. [onPlaybackStarted] is called once when a new episode / movie starts.
 *      Sends `scrobble/start` with the current position (near 0).
 *   2. [onPositionChanged] is called periodically by the player. We send
 *      `scrobble/start` again every [START_REFRESH_INTERVAL_MS] to keep Trakt's
 *      "now playing" status alive, and `scrobble/pause` when the user pauses.
 *   3. [onPlaybackStopped] is called when the player is closed or the episode
 *      ends. Sends `scrobble/stop` with the final progress — Trakt marks the
 *      item as watched if progress >= 80%.
 *
 * This class does not persist any user data; it is a pure dispatcher.
 *
 * Threading: all public methods are safe to call from any thread. Network
 * calls are dispatched to the IO coroutine scope.
 */
object TraktScrobbleManager {
    private const val TAG = "TraktScrobble"

    /** Resend "start" every N ms to keep Trakt's "now playing" alive. */
    private const val START_REFRESH_INTERVAL_MS = 10 * 60 * 1000L  // 10 minutes

    /** Trakt marks the item as watched when progress >= 80%. */
    private const val WATCHED_THRESHOLD = 80.0

    // Current session state
    private var currentSyncData: HashMap<String, String>? = null
    private var currentMedia: ScrobbleMedia? = null
    private var isEpisode: Boolean = false
    private var lastStartSentMs: Long = 0L
    private var lastProgress: Double = 0.0
    private var isPlaying: Boolean = false

    private val traktApi: TraktApi get() = AccountManager.traktApi

    private fun currentAuth(): AuthData? {
        val repo = AccountManager.allApis
            .filterIsInstance<com.lagradost.cloudstream3.syncproviders.PlainAuthRepo>()
            .firstOrNull { it.idPrefix == "trakt" }
        return repo?.authData()
    }

    /**
     * Call once when a new episode or movie starts playing.
     *
     * @param syncData The player's sync data map — should contain "imdb",
     *   "tmdb", and/or "trakt" keys when the content has known external IDs.
     *   These are populated by the tracker in [com.lagradost.cloudstream3.ui.result.ResultViewModel2].
     * @param title Title of the movie or show.
     * @param year  Release year (optional).
     * @param episodeMode true for TV episodes, false for movies.
     * @param episodeTitle Episode title (only for episodes).
     * @param season Season number (only for episodes).
     * @param episode Episode number (only for episodes).
     */
    fun onPlaybackStarted(
        context: Context?,
        syncData: HashMap<String, String>?,
        title: String?,
        year: Int? = null,
        episodeMode: Boolean = false,
        episodeTitle: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ) {
        if (!traktApi.isConfigured()) return
        if (currentAuth() == null) return

        currentSyncData = syncData
        isEpisode = episodeMode
        lastProgress = 0.0
        isPlaying = true

        val media = buildMedia(
            syncData = syncData,
            title = if (episodeMode) episodeTitle ?: title else title,
            year = year,
        )
        currentMedia = media
        if (media == null) {
            Log.d(TAG, "No recognized external ID (imdb/tmdb/trakt) — scrobbling skipped")
            return
        }

        lastStartSentMs = System.currentTimeMillis()
        sendScrobble("start", progress = 0.0)
    }

    /**
     * Called periodically from the player's position callback.
     * Sends `start` again every [START_REFRESH_INTERVAL_MS] to keep the
     * "now playing" status alive, and `pause`/resume as appropriate.
     *
     * @param positionMs Current playback position in milliseconds.
     * @param durationMs Total duration in milliseconds.
     * @param playing Whether playback is currently running (not paused).
     */
    fun onPositionChanged(positionMs: Long, durationMs: Long, playing: Boolean) {
        if (currentMedia == null) return
        if (currentAuth() == null) return
        if (durationMs <= 0) return

        val progress = (positionMs.toDouble() / durationMs) * 100.0
        lastProgress = progress.coerceIn(0.0, 100.0)

        val now = System.currentTimeMillis()
        val wasPaused = !isPlaying
        isPlaying = playing

        if (!playing && wasPaused) {
            // Still paused, no-op.
            return
        }

        if (!playing && !wasPaused) {
            // Transition from playing to paused — notify Trakt.
            sendScrobble("pause", progress = lastProgress)
            return
        }

        if (playing && wasPaused) {
            // Resumed from pause — restart the "now playing" signal.
            lastStartSentMs = now
            sendScrobble("start", progress = lastProgress)
            return
        }

        // Playing — resend "start" periodically.
        if (now - lastStartSentMs >= START_REFRESH_INTERVAL_MS) {
            lastStartSentMs = now
            sendScrobble("start", progress = lastProgress)
        }
    }

    /** Call when the player is closed or the episode finishes. */
    fun onPlaybackStopped() {
        if (currentMedia == null) return
        if (currentAuth() == null) {
            reset()
            return
        }
        sendScrobble("stop", progress = lastProgress)
        reset()
    }

    private fun reset() {
        currentSyncData = null
        currentMedia = null
        isEpisode = false
        lastStartSentMs = 0L
        lastProgress = 0.0
        isPlaying = false
    }

    // ---------- Helpers ----------

    private fun sendScrobble(action: String, progress: Double) {
        val media = currentMedia ?: return
        val auth = currentAuth() ?: return
        val body = if (isEpisode) {
            ScrobbleBody(episode = media, progress = progress)
        } else {
            ScrobbleBody(movie = media, progress = progress)
        }
        ioSafe {
            val ok = traktApi.scrobble(auth, action, body)
            Log.d(TAG, "scrobble/$action progress=${"%.1f".format(progress)}% ok=$ok")
        }
    }

    /**
     * Build a [ScrobbleMedia] from the player's sync data.
     *
     * The sync data may contain IMDB, TMDB, or Trakt IDs. These keys are
     * the string forms of [SyncIdName] values ("Imdb", "Trakt") plus "tmdb"
     * which is commonly added by the tracker. We prefer IMDB > Trakt > TMDB
     * because Trakt resolves most reliably from IMDB.
     */
    private fun buildMedia(
        syncData: HashMap<String, String>?,
        title: String?,
        year: Int?,
    ): ScrobbleMedia? {
        if (syncData == null || syncData.isEmpty()) return null

        val ids = extractIds(syncData)
        if (ids == null) return null

        return ScrobbleMedia(
            title = title,
            year = year,
            ids = ids,
        )
    }

    private fun extractIds(syncData: HashMap<String, String>): ScrobbleIds? {
        // syncData keys come from SyncIdName enum + tracker-added "tmdb" key.
        // They are stored as lowercase strings in some places. Try both cases.
        val imdb = firstNonNull(syncData, "Imdb", "imdb", "IMDB")
        val traktId = firstNonNull(syncData, "Trakt", "trakt")?.toIntOrNull()
        val tmdb = firstNonNull(syncData, "tmdb", "Tmdb", "TMDB")?.toIntOrNull()

        // Simkl stores multiple ids in a JSON object — try to parse those.
        val simklIds = firstNonNull(syncData, "Simkl", "simkl")?.let { parseSimklIdString(it) }

        val resolvedImdb = imdb ?: simklIds?.imdb
        val resolvedTmdb = tmdb ?: simklIds?.tmdb
        val resolvedTrakt = traktId

        if (resolvedImdb == null && resolvedTmdb == null && resolvedTrakt == null) return null

        return ScrobbleIds(
            imdb = resolvedImdb,
            tmdb = resolvedTmdb,
            trakt = resolvedTrakt,
        )
    }

    private fun firstNonNull(map: Map<String, String>, vararg keys: String): String? {
        for (k in keys) {
            map[k]?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    /**
     * Simkl stores IDs as a JSON object: {"imdb":"tt1234","tmdb":5678,...}.
     * Extract IMDB and TMDB from it if present.
     */
    private fun parseSimklIdString(raw: String): ScrobbleIds? {
        return try {
            val json = Json.parseToJsonElement(raw).jsonObject
            val imdb = json["imdb"]?.jsonPrimitive?.content
            val tmdb = json["tmdb"]?.jsonPrimitive?.content?.toIntOrNull()
            if (imdb == null && tmdb == null) null
            else ScrobbleIds(imdb = imdb, tmdb = tmdb)
        } catch (_: Exception) {
            null
        }
    }
}
