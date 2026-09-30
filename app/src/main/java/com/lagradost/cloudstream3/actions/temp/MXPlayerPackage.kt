package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

// MX Player API reference:
// https://sites.google.com/site/mxvpen/api
// https://mx.j2inter.com/api (legacy)

/**
 * MX Player (free, ad-supported).
 *
 * MX Player does NOT officially support forwarding custom HTTP headers via
 * intent extras. For signed/authenticated streams that require custom headers,
 * the URL itself must carry the credentials (query parameters) — which is
 * how most debrid-resolved links already work.
 */
open class MXPlayerPackage : OpenInAppAction(
    appName = txt("MX Player"),
    packageName = "com.mxtech.videoplayer.ad",
    intentClass = "com.mxtech.videoplayer.ad.ActivityScreen"
) {
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)

    override val oneSource: Boolean = true

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val link = result.links.getOrNull(index!!) ?: return
        intent.setDataAndType(link.url.toUri(), "video/*")
        intent.putExtra("secure_uri", true)
        intent.putExtra(Intent.EXTRA_TITLE, video.name)

        // Resume position in milliseconds.
        val position = getViewPos(video.id)?.position
        if (position != null && position > 0) {
            intent.putExtra("position", position)
        }

        // Pass subtitles. MX Player reads "subs" (Uri[]) and optional
        // "subs.name" / "subs.filename" (String[]) for display names.
        if (result.subs.isNotEmpty()) {
            intent.putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
            val names = result.subs.map { it.originalName }.toTypedArray()
            intent.putExtra("subs.name", names)
            intent.putExtra("subs.filename", names)
        }

        // MX Player's public API does not document an intent extra for custom
        // HTTP headers. For streams that need headers (e.g., Authorization),
        // they must be embedded in the URL as query parameters, which is how
        // most debrid links are already structured. We do nothing here.
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getLongExtra("position", -1L) ?: -1L
        val duration = intent?.getLongExtra("duration", -1L) ?: -1L
        Log.d("MXPlayer", "Position: $position, Duration: $duration")
        updateDurationAndPosition(position, duration)
    }
}

/** MX Player Pro (paid, no ads). Same intent API, different package name. */
class MXPlayerProPackage : MXPlayerPackage() {
    override val appName = txt("MX Player Pro")
    override val packageName = "com.mxtech.videoplayer.pro"
}
