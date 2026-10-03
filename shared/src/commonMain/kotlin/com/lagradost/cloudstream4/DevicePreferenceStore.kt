package com.lagradost.cloudstream4

import androidx.compose.runtime.Composable
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.TvType
import com.mihon.common.preference.PreferenceData
import com.mihon.common.preference.PreferenceStore
import com.mihon.common.preference.getEnumSet

@Composable
expect fun rememberAppSettings(): AppSettings

/**
 * App settings for every setting we have, that way we can just inject AppSettings into a viewmodel,
 * or similar.
 *
 * We use an internal constructor to force the user to construct it using the plantform specific
 * initalizer
 * */
class AppSettings internal constructor(
    preferences: PreferenceStore,
    // Todo add database key-value interface here, possibly using the same PreferenceStore interface
) {
    val general = GeneralPreferences(preferences)
    val player = PlayerPreferences(preferences)
    val provider = ProviderPreferences(preferences)
    val ui = UIPreferences(preferences)
    val security = SecurityPreferences(preferences)
    val updates = UpdatePreferences(preferences)
    val backup = BackupPreferences(preferences)
    val plugins = PluginPreferences(preferences)
    val debrid = DebridPreferences(preferences)
    val downloads = DownloadPreferences(preferences)
    val subtitles = SubtitlePreferences(preferences)
}

class PluginPreferences(preferences: PreferenceStore) {
    val autoUpdate = preferences.getBoolean("auto_update_plugins", true)
    val autoDownload = preferences.getInt("auto_download_plugins_key2", 0)
}

class BackupPreferences(preferences: PreferenceStore) {
    val frequency = preferences.getInt("automatic_backup_key", 0)
    val path = preferences.getString("backup_path_key")
    val visualPath = preferences.getString("backup_dir_key")
}
class UpdatePreferences(preferences: PreferenceStore) {
    val apkInstaller = preferences.getInt("apk_installer_key",1)
    val showAppUpdates = preferences.getBoolean("auto_update", true)
    // Node id for this update
    val skipUpdate = preferences.getString("skip_update_key", "")
}

class SecurityPreferences(preferences: PreferenceStore) {
    val biometrics = preferences.getBoolean(
        "biometric_key", false
    )
    val skipAccountSelection = preferences.getBoolean(
        "skip_startup_account_select_key", false
    )
}

class UIPreferences(preferences: PreferenceStore) {
    val primaryColor = preferences.getString(
        "primary_color_key", "Normal"
    )
    val theme = preferences.getString(
        "app_theme_key", "AmoledLight"
    )
    val layout = preferences.getInt(
        "app_layout_key", -1
    )
    val bottomTitle = preferences.getBoolean(
        "bottom_title_key", true
    )
    val advancedSearch = preferences.getBoolean(
        "advanced_search", true
    )
    val searchSuggestions = preferences.getBoolean(
        "search_suggestions_enabled", true
    )
    val kitsuPostersEnabled = preferences.getBoolean(
        "show_kitsu_posters_key", true
    )
    val trailersEnabled = preferences.getBoolean(
        "show_trailers_key", true
    )
    val castEnabled = preferences.getBoolean(
        "show_cast_in_details_key", true
    )
    val fillersEnabled = preferences.getBoolean(
        "show_fillers_key", false
    )
    val showMetadataOverlay = preferences.getBoolean(
        "show_player_metadata_key", true
    )
    val overscanDp = preferences.getInt(
        "overscan_key", 0
    )
    val posterSize = preferences.getInt(
        "poster_size_key", 0
    )
    val posterShowHd = preferences.getBoolean(
        "show_hd_key", true
    )
    val posterShowDub = preferences.getBoolean(
        "show_dub_key", true
    )
    val posterShowSub = preferences.getBoolean(
        "show_sub_key", true
    )
    val posterShowRating = preferences.getBoolean(
        "show_rating_key", true
    )
    val posterShowTitle = preferences.getBoolean(
        "show_title_key", true
    )
    val posterShowEpisode = preferences.getBoolean(
        "show_episode_text_key", true
    )
    val showClock = preferences.getBoolean(
        "tv_layout_clock_key", false
    )

    val randomButtonEnabled = preferences.getBoolean(
        "random_button_key", false
    )

    val confirmExit = preferences.getInt(
        "confirm_exit_key", -1
    )

    /** This had to be refactored to use enumSet because the old system is prone to bugs */
    val filterQuality = preferences.getEnumSet<SearchQuality>(
        "pref_filter_search_quality_key2", emptySet()
    )

    /** Minimum release year shown in search results; null means no lower bound.
     *  Stored as the raw int; -1 is the sentinel for "unset". */
    val searchFilterYearMin = preferences.getInt("pref_search_filter_year_min", -1)

    /** Maximum release year shown in search results; null means no upper bound.
     *  Stored as the raw int; -1 is the sentinel for "unset". */
    val searchFilterYearMax = preferences.getInt("pref_search_filter_year_max", -1)

    /** Sort mode used for search results. Stored as the enum name. */
    val searchFilterSortMode = preferences.getString("pref_search_filter_sort_mode", "DEFAULT")
}

class ProviderPreferences(preferences: PreferenceStore) {
    companion object {
        private val defaultPreferredMedia =
            TvType.entries.filter { it != TvType.NSFW }.map { it.ordinal.toString() }.toSet()
        private val defaultDub = DubStatus.entries.map { it.name }.toSet()
    }

    val preferredMedia = preferences.getStringSet(
        "prefer_media_type_key_2", defaultPreferredMedia
    )

    val extensionLanguages = preferences.getStringSet(
        "provider_lang_key", setOf(AllLanguagesName)
    )

    val displayDubSub = preferences.getStringSet(
        "display_sub_key", defaultDub
    )

    /** User supplied TheMovieDB API key. Empty falls back to the built-in public key. */
    val tmdbApiKey = preferences.getString("tmdb_api_key", "")

    /**
     * User supplied MDBList API key (free, from mdblist.com preferences).
     * Used by the Torrin MDBList extension for latest-releases rows.
     */
    val mdblistApiKey = preferences.getString("mdblist_api_key", "")

    /**
     * User supplied Trakt client id (free, from trakt.tv developer settings).
     * Used by the Torrin Trakt extension for latest-releases rows.
     */
    val traktApiKey = preferences.getString("trakt_api_key", "")
}

class PlayerPreferences(preferences: PreferenceStore) {
    val episodeSync = preferences.getBoolean("episode_sync_enabled_key", true)
    val defaultPlayer = preferences.getString("player_default_key", "")
    val limitPlayerTitle = preferences.getInt("prefer_limit_title_key", 0)
    val hidePlayerControlNames = preferences.getBoolean("hide_player_control_names_key", false)
    val showName = preferences.getBoolean("show_name", true)
    val showResolution = preferences.getBoolean("show_resolution", true)
    val showMediaInfo = preferences.getBoolean("show_media_info", false)
    val pipEnabled = preferences.getBoolean("pip_enabled_key", true)
    val resizeEnabled = preferences.getBoolean("player_resize_enabled_key", true)
    val speedEnabled = preferences.getBoolean("playback_speed_enabled_key", false)
    val tiktokEnabled = preferences.getBoolean("speedup_key", false)
    val autoPlayEnabled = preferences.getBoolean("autoplay_next_key", true)
    val startPaused = preferences.getBoolean("start_paused_key", false)
    val skipOpEnabled = preferences.getBoolean("enable_skip_op_from_database", true)
    val autoRotateEnabled = preferences.getBoolean("auto_rotate_video_key", true)
    val rotateButtonEnabled = preferences.getBoolean("rotate_video_key", false)
    val previewBarEnabled = preferences.getBoolean("preview_seekbar_key", true)
    val softwareDecoding = preferences.getInt("software_decoding_key2", -1)
    val extraBrightnessEnabled = preferences.getBoolean("extra_brightness_key", false)
    val swipeHorizontalEnabled = preferences.getBoolean("swipe_enabled_key", true)
    val swipeVerticalEnabled = preferences.getBoolean("swipe_vertical_enabled_key", true)
    val doubleTapToSeekEnabled = preferences.getBoolean("double_tap_enabled_key", false)
    val doubleTapToPauseEnabled = preferences.getBoolean("double_tap_pause_enabled_key", false)
    val doubleTapTime = preferences.getInt("double_tap_seek_time_key2", 10)
    val bufferDiskMB = preferences.getInt("video_buffer_disk_key", 0)
    val bufferRamMB = preferences.getInt("video_buffer_size_key", 0)
    val bufferTimeSec = preferences.getInt("video_buffer_length_key", 0)
    val tvSeekOnTime = preferences.getInt("android_tv_interface_on_seek_key", 10)
    val tvSeekOffTime = preferences.getInt("android_tv_interface_off_seek_key", 10)

    // TMDB region and language preferences for localized content
    val tmdbRegion = preferences.getString("tmdb_region_key", "US")
    val tmdbLanguage = preferences.getString("tmdb_language_key", "en-US")
}

class GeneralPreferences(preferences: PreferenceStore) {
    val locale = preferences.getString("app_locale", "")
    val bananas = preferences.getInt("benene_count", 0)
    val parallelDownloads = preferences.getInt("download_parallel_key", 3)
    val concurrentConnections = preferences.getInt("download_concurrent_key", 3)
    val batterOptimization = preferences.getBoolean("battery_optimisation_key", false)

    /** Please note that it used R.array.dns_pref before, this was a bug and caused this setting to be language sensitive **/
    val dns = preferences.getInt("dns_key", 0)

    /** Very weird setKey based usage, just having this setting might not do it */
    val jsdelivrProxy = preferences.getBoolean("jsdelivr_proxy_key", false)

    /** This should honesty be refactored to a single setting */
    val downloadPath = preferences.getString("download_path_key", "")
    val downloadPathVisual = preferences.getString("download_path_key_visual", "")
}

/**
 * Debrid service settings. Torrin (https://torrin.app), TorBox
 * (https://torbox.app), and Real-Debrid (https://real-debrid.com):
 * resolve magnet links into directly playable, signed HTTPS streams
 * instead of streaming them through the local torrent engine.
 */
class DebridPreferences(preferences: PreferenceStore) {
    companion object {
        const val KEY_ENABLED = "torrin_enabled_key"
        const val KEY_BASE_URL = "torrin_base_url_key"
        const val KEY_TIMEOUT_SECONDS = "torrin_timeout_seconds_key"
        /** Stored under a private key so the secret is not exposed by backup/restore flows. */
        val KEY_API_KEY = PreferenceData.privateKey("torrin_api_key_key")
        const val DEFAULT_BASE_URL = "https://api.torrin.app"
        const val DEFAULT_TIMEOUT_SECONDS = 90

        const val KEY_TORBOX_ENABLED = "torbox_enabled_key"
        val KEY_TORBOX_API_KEY = PreferenceData.privateKey("torbox_api_key_key")
        const val KEY_TORBOX_TIMEOUT_SECONDS = "torbox_timeout_seconds_key"
        const val DEFAULT_TORBOX_BASE_URL = "https://api.torbox.app/v1/api"

        const val KEY_REALDEBRID_ENABLED = "realdebrid_enabled_key"
        val KEY_REALDEBRID_API_KEY = PreferenceData.privateKey("realdebrid_api_key_key")
        const val KEY_REALDEBRID_TIMEOUT_SECONDS = "realdebrid_timeout_seconds_key"

        const val KEY_DEBUG_ENABLED = "debrid_debug_enabled"
    }

    val torrinEnabled = preferences.getBoolean(KEY_ENABLED, false)
    val torrinApiKey = preferences.getString(KEY_API_KEY)
    val torrinBaseUrl = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
    val torrinTimeoutSeconds = preferences.getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)

    val torboxEnabled = preferences.getBoolean(KEY_TORBOX_ENABLED, false)
    val torboxApiKey = preferences.getString(KEY_TORBOX_API_KEY)
    val torboxTimeoutSeconds = preferences.getInt(KEY_TORBOX_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)

    val realDebridEnabled = preferences.getBoolean(KEY_REALDEBRID_ENABLED, false)
    val realDebridApiKey = preferences.getString(KEY_REALDEBRID_API_KEY)
    val realDebridTimeoutSeconds = preferences.getInt(KEY_REALDEBRID_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)

    val debridDebugEnabled = preferences.getBoolean(KEY_DEBUG_ENABLED, false)
}

/**
 * Download manager preferences
 */
class DownloadPreferences(preferences: PreferenceStore) {
    val downloadEnabled = preferences.getBoolean("download_enabled_key", true)
    val downloadOverWifiOnly = preferences.getBoolean("download_wifi_only_key", false)
    val maxConcurrentDownloads = preferences.getInt("max_concurrent_downloads_key", 2)
    val autoDeleteAfterWatch = preferences.getBoolean("auto_delete_after_watch_key", false)
    val downloadQuality = preferences.getString("download_quality_key", "best")
    val downloadPath = preferences.getString("download_path_key", "")
}

/**
 * Subtitle preferences
 */
class SubtitlePreferences(preferences: PreferenceStore) {
    val autoDownloadSubtitles = preferences.getBoolean("auto_download_subtitles_key", true)
    val subtitleLanguage = preferences.getString("subtitle_language_key", "en")
    val subtitleSize = preferences.getInt("subtitle_size_key", 16)
    val subtitleColor = preferences.getInt("subtitle_color_key", -1) // -1 = white
    val subtitleBackgroundColor = preferences.getInt("subtitle_bg_color_key", 0x80000000.toInt())
    val subtitleOutlineColor = preferences.getInt("subtitle_outline_color_key", -16777216) // black
    val subtitleOutlineWidth = preferences.getInt("subtitle_outline_width_key", 2)
    val subtitleFont = preferences.getString("subtitle_font_key", "default")
    val subtitleSyncOffset = preferences.getInt("subtitle_sync_offset_key", 0) // in milliseconds
}
