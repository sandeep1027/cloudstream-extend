package com.lagradost.cloudstream3.ui.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.mvvm.logError
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that refreshes the Continue Watching home-screen widget.
 *
 * The system already triggers [ContinueWatchingWidgetProvider.onUpdate] every
 * 30 minutes via [android.appwidget.AppWidgetProviderInfo.updatePeriodMillis],
 * but this worker additionally ensures the list is kept fresh whenever the
 * user resumes / finishes watching something (the widget factory is called
 * synchronously on the widget thread, but the DataStore write that populates
 * resume data happens asynchronously).
 *
 * No foreground notification is needed — this worker just sends a broadcast
 * and finishes in milliseconds.
 */
class ContinueWatchingWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            ContinueWatchingWidgetProvider.requestUpdate(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            logError(t)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "work_continue_watching_widget"

        /** Schedule periodic widget refresh (every 15 minutes — WorkManager minimum). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequest.Builder(
                ContinueWatchingWorker::class.java,
                15,
                TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Cancel the periodic widget refresh. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Request an immediate one-shot refresh.
         * Useful after the user finishes / starts watching something,
         * so the widget reflects the change without waiting up to 15 minutes.
         */
        fun refreshNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequest.Builder(
                ContinueWatchingWorker::class.java,
            ).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
