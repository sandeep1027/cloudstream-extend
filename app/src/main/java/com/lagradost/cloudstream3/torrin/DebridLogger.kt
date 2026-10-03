package com.lagradost.cloudstream3.torrin

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Centralized debug logging for debrid services.
 *
 * Logging is controlled by a preference toggle in Settings → Player → Debrid.
 * When disabled, only warnings and errors are logged. When enabled, all
 * debrid operations (API calls, timing, cache hits/misses) are logged.
 */
object DebridLogger {

    private const val TAG_TORRIN = "Torrin"
    private const val TAG_TORBOX = "TorBox"
    private const val TAG_REALDEBRID = "RealDebrid"
    private const val TAG_CACHE = "DebridCache"

    const val KEY_DEBUG_ENABLED = "debrid_debug_enabled"

    /**
     * Returns true if debug logging is enabled in preferences.
     */
    fun isEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_DEBUG_ENABLED, false)
    }

    // ─── Torrin ──────────────────────────────────────────────────────────────

    fun torrinD(context: Context, message: String) {
        if (isEnabled(context)) Log.d(TAG_TORRIN, message)
    }

    fun torrinI(context: Context, message: String) {
        Log.i(TAG_TORRIN, message)
    }

    fun torrinW(context: Context, message: String) {
        Log.w(TAG_TORRIN, message)
    }

    fun torrinW(context: Context, message: String, throwable: Throwable) {
        Log.w(TAG_TORRIN, message, throwable)
    }

    // ─── TorBox ──────────────────────────────────────────────────────────────

    fun torboxD(context: Context, message: String) {
        if (isEnabled(context)) Log.d(TAG_TORBOX, message)
    }

    fun torboxI(context: Context, message: String) {
        Log.i(TAG_TORBOX, message)
    }

    fun torboxW(context: Context, message: String) {
        Log.w(TAG_TORBOX, message)
    }

    fun torboxW(context: Context, message: String, throwable: Throwable) {
        Log.w(TAG_TORBOX, message, throwable)
    }

    // ─── RealDebrid ──────────────────────────────────────────────────────────

    fun realDebridD(context: Context, message: String) {
        if (isEnabled(context)) Log.d(TAG_REALDEBRID, message)
    }

    fun realDebridI(context: Context, message: String) {
        Log.i(TAG_REALDEBRID, message)
    }

    fun realDebridW(context: Context, message: String) {
        Log.w(TAG_REALDEBRID, message)
    }

    fun realDebridW(context: Context, message: String, throwable: Throwable) {
        Log.w(TAG_REALDEBRID, message, throwable)
    }

    // ─── Cache ───────────────────────────────────────────────────────────────

    fun cacheD(context: Context, message: String) {
        if (isEnabled(context)) Log.d(TAG_CACHE, message)
    }

    // ─── Timing ──────────────────────────────────────────────────────────────

    /**
     * Logs the duration of an operation.
     */
    fun logDuration(context: Context, tag: String, operation: String, startTimeMs: Long) {
        val duration = System.currentTimeMillis() - startTimeMs
        if (isEnabled(context)) {
            Log.d(tag, "$operation completed in ${duration}ms")
        }
    }
}
