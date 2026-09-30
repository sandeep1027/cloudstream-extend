package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/** https://github.com/anilbeesetti/nextplayer */
class NextPlayerPackage : OpenInAppAction(
    appName = txt("NextPlayer"),
    packageName = "dev.anilbeesetti.nextplayer",
    intentClass = "dev.anilbeesetti.nextplayer.feature.player.PlayerActivity"
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
        intent.putExtra("title", video.name)

        // Resume position
        val position = getViewPos(video.id)?.position
        if (position != null && position > 0) {
            intent.putExtra("position", position)
        }

        // Forward HTTP headers as a Bundle via the standard EXTRA_HEADERS key.
        val headers = Bundle().apply {
            link.headers.forEach { (k, v) -> putString(k, v) }
            if (!link.referer.isNullOrBlank() && !link.headers.keys.any { it.equals("Referer", ignoreCase = true) }) {
                putString("Referer", link.referer)
            }
        }
        if (!headers.isEmpty) {
            // Intent.EXTRA_HEADERS = "android.intent.extra.HTTP_HEADERS" (API 34+).
            // Use the string literal so we work on older API levels too.
            intent.putExtra("android.intent.extra.HTTP_HEADERS", headers)
        }

        // Pass subtitles — NextPlayer accepts a Uri array under "subs".
        if (result.subs.isNotEmpty()) {
            intent.putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
            intent.putExtra("subs.name", result.subs.map { it.originalName }.toTypedArray())
        }
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getLongExtra("position", -1L) ?: -1L
        val duration = intent?.getLongExtra("duration", -1L) ?: -1L
        Log.d("NextPlayer", "Position: $position, Duration: $duration")
        updateDurationAndPosition(position, duration)
    }
}