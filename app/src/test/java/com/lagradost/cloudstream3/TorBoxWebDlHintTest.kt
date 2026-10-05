package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.torrin.TorBoxWebDl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `cs_webdl` hint is the whole contract between a plugin and TorBox's Web
 * Downloads service: get the split wrong and either the hoster page gets played
 * or TorBox is handed a URL with our own parameter still attached.
 */
class TorBoxWebDlHintTest {

    private fun request(url: String) = TorBoxWebDl.parseRequest(url)

    @Test
    fun plainLinkIsNotAWebDownload() {
        val url = "https://host.example/file.mkv"
        assertFalse(TorBoxWebDl.hasHint(url))
        assertNull(request(url))
    }

    @Test
    fun otherCsHintsAreNotMistakenForWebdl() {
        // cs_file and cs_debrid belong to the magnet path.
        val url = "magnet:?xt=urn:btih:abc&cs_file=3&cs_debrid=torbox"
        assertFalse(TorBoxWebDl.hasHint(url))
        assertNull(request(url))
    }

    @Test
    fun plainHintAsksForWebDownload() {
        val parsed = request("https://host.example/file.mkv?cs_webdl=1")
        assertTrue(TorBoxWebDl.hasHint("https://host.example/file.mkv?cs_webdl=1"))
        assertEquals("https://host.example/file.mkv", parsed?.link)
        assertEquals(false, parsed?.onlyIfCached)
    }

    @Test
    fun onlyCachedAsksForNoDownload() {
        val parsed = request("https://host.example/file.mkv?cs_webdl=only_cached")
        assertEquals("https://host.example/file.mkv", parsed?.link)
        assertEquals(true, parsed?.onlyIfCached)
    }

    @Test
    fun hintIsRemovedFromTheMiddleOfAQuery() {
        val parsed = request("https://host.example/f.mkv?token=abc&cs_webdl=1&expires=99")
        assertEquals("https://host.example/f.mkv?token=abc&expires=99", parsed?.link)
    }

    @Test
    fun hintAloneLeavesNoDanglingSeparator() {
        assertEquals(
            "https://host.example/f.mkv",
            request("https://host.example/f.mkv?cs_webdl=1")?.link
        )
        assertEquals(
            "https://host.example/f.mkv?a=1",
            request("https://host.example/f.mkv?a=1&cs_webdl=1")?.link
        )
        // A fragment must survive, and the emptied query must not leave "?&" or
        // a bare "?" in front of it.
        assertEquals(
            "https://host.example/f.mkv#frag",
            request("https://host.example/f.mkv?cs_webdl=1#frag")?.link
        )
        assertEquals(
            "https://host.example/f.mkv?a=1#frag",
            request("https://host.example/f.mkv?cs_webdl=1&a=1#frag")?.link
        )
    }

    @Test
    fun unknownValueStillMeansWebDownloadButNeverCachedOnly() {
        // A plugin using a value we do not know must not accidentally be given
        // the stricter cached-only behaviour.
        val parsed = request("https://host.example/f.mkv?cs_webdl=yes")
        assertEquals("https://host.example/f.mkv", parsed?.link)
        assertEquals(false, parsed?.onlyIfCached)
    }

    @Test
    fun valueIsCaseInsensitive() {
        assertEquals(true, request("https://h/f?cs_webdl=ONLY_CACHED")?.onlyIfCached)
    }
}