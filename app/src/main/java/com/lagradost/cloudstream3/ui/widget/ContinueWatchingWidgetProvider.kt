package com.lagradost.cloudstream3.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R

/**
 * Home-screen widget that shows the user's "Continue Watching" list.
 *
 * The widget is populated by [ContinueWatchingWidgetService]. Updates are
 * triggered:
 *   1. Automatically every 30 minutes via [AppWidgetProvider.onUpdate]
 *   2. Manually from [ContinueWatchingWorker] whenever the user finishes
 *      or starts watching something (to keep the list fresh)
 *   3. By the system when the widget is added / resized
 *
 * Tapping an item launches [MainActivity] with an extra that tells it to
 * resume that specific title.
 */
class ContinueWatchingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ContinueWatchingWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ContinueWatchingWorker.cancel(context)
    }

    companion object {
        const val ACTION_RESUME_ITEM = "com.lagradost.cloudstream3.widget.RESUME"
        const val EXTRA_RESUME_ID = "resume_id"
        const val EXTRA_RESUME_PARENT_ID = "resume_parent_id"

        /** Broadcast an update request to all active widgets. */
        fun requestUpdate(context: Context) {
            val intent = Intent(context, ContinueWatchingWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val ids = AppWidgetManager.getInstance(context.applicationContext)
                .getAppWidgetIds(
                    android.content.ComponentName(
                        context,
                        ContinueWatchingWidgetProvider::class.java
                    )
                )
            if (ids.isNotEmpty()) {
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_continue_watching)

            // Tapping the header opens the app to the home screen.
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, openAppPending)

            // Hook up the list view to the RemoteViewsService.
            val serviceIntent = Intent(context, ContinueWatchingWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // The data URI must be unique per widget so the system doesn't
                // reuse a stale factory for different widget instances.
                data = android.net.Uri.parse(toUri(appWidgetId))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            // Per-item click template — the service fills in the extras.
            val resumeIntent = Intent(context, ContinueWatchingWidgetProvider::class.java).apply {
                action = ACTION_RESUME_ITEM
            }
            val resumePending = PendingIntent.getBroadcast(
                context, 0, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setPendingIntentTemplate(R.id.widget_list, resumePending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun toUri(id: Int) = "cloudstream://widget/$id"
    }
}
