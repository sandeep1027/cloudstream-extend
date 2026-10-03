package com.zangetsu.cs3

import com.lagradost.cloudstream3.plugins.BasePlugin

/**
 * Entry point loaded by CloudStream's PluginManager from the .cs3 archive.
 *
 * Registers the Zangetsu sources ported from
 * https://github.com/Spyou/zangetsu-providers as regular MainAPI sources —
 * each one shows up on search/home exactly like any repository-installed
 * extension, with no code in the app itself.
 *
 * Sources are independent: a site going down only disables its own provider.
 */
class ZangetsuPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HianimeProvider())
        registerMainAPI(AnikotoProvider())
        registerMainAPI(AnimecubeProvider())
        registerMainAPI(Hdhub4uProvider())
    }
}