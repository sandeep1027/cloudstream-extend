package com.lagradost.cloudstream3.ui.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadHeaderCached
import kotlinx.coroutines.runBlocking

/**
 * RemoteViewsService backing the Continue Watching widget list.
 */
class ContinueWatchingWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ContinueWatchingWidgetFactory(applicationContext)
    }
}

/**
 * RemoteViewsFactory that loads the user's Continue Watching items and
 * renders them as widget rows.
 *
 * This runs on a binder thread from the system, so synchronous access to
 * DataStore is fine — we must not block the main thread.
 */
class ContinueWatchingWidgetFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<DataStoreHelper.ResumeWatchingResult> = emptyList()

    override fun onCreate() {
        loadItems()
    }

    override fun onDataSetChanged() {
        loadItems()
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_continue_watching_item)
        val item = items.getOrNull(position) ?: return views

        views.setTextViewText(R.id.widget_item_title, item.name)

        // Episode label — only show season/episode for non-movie types.
        val episodeText = if (item.season != null && item.episode != null) {
            context.getString(
                R.string.widget_continue_watching_episode_format,
                item.season, item.episode,
            )
        } else if (item.episode != null) {
            "E${item.episode}"
        } else {
            item.apiName
        }
        views.setTextViewText(R.id.widget_item_episode, episodeText)

        // Progress bar — compute percentage from watchPos.
        val progress = item.watchPos?.let {
            if (it.duration > 0) (it.position * 100 / it.duration).toInt().coerceIn(0, 100)
            else 0
        } ?: 0
        views.setProgressBar(R.id.widget_item_progress, 100, progress, false)

        // Poster — loaded via Coil's singleton ImageLoader (binder thread).
        item.posterUrl?.let { url ->
            try {
                val bitmap: Bitmap? = runBlocking { loadPosterBitmap(url) }
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_item_poster, bitmap)
                } else {
                    views.setImageViewResource(R.id.widget_item_poster, R.drawable.ic_cloudstreamlogotv)
                }
            } catch (_: Exception) {
                views.setImageViewResource(R.id.widget_item_poster, R.drawable.ic_cloudstreamlogotv)
            }
        } ?: views.setImageViewResource(R.id.widget_item_poster, R.drawable.ic_cloudstreamlogotv)

        // Per-item click — fills in the fill-in intent with the resume extras.
        val fillInIntent = Intent().apply {
            putExtra(ContinueWatchingWidgetProvider.EXTRA_RESUME_ID, item.id)
            putExtra(ContinueWatchingWidgetProvider.EXTRA_RESUME_PARENT_ID, item.parentId)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun loadItems() {
        items = try {
            val ids = DataStoreHelper.getAllResumeStateIds() ?: return
            ids.mapNotNull { id -> DataStoreHelper.getLastWatched(id) }
                .sortedBy { -it.updateTime }
                .take(MAX_ITEMS)
                .mapNotNull { resume ->
                    val headerCache = CloudStreamApp.getKey<DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        resume.parentId.toString(),
                    )
                    val data = headerCache ?: return@mapNotNull null
                    val watchPos = DataStoreHelper.getViewPos(resume.episodeId)
                    DataStoreHelper.ResumeWatchingResult(
                        name = data.name,
                        url = data.url,
                        apiName = data.apiName,
                        type = data.type,
                        posterUrl = data.poster,
                        watchPos = watchPos,
                        id = resume.episodeId,
                        parentId = resume.parentId,
                        episode = resume.episode,
                        season = resume.season,
                        isFromDownload = false,
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Load a poster bitmap synchronously via Coil.
     * Returns null if the load fails.
     */
    private suspend fun loadPosterBitmap(url: String): Bitmap? {
        val loader: ImageLoader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false) // Required for mutable Bitmap on RemoteViews
            .build()
        val result = loader.execute(request)
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    companion object {
        /** Maximum number of items shown in the widget. */
        private const val MAX_ITEMS = 10
    }
}
