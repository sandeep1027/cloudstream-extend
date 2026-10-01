package com.torrin.tmdb

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers [TmdbProvider] as a regular MainAPI source — it shows up on the
 * home/dashboard page exactly like any repository-installed extension, with
 * no code in the app itself.
 */
class TmdbPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TmdbProvider())
    }
}
