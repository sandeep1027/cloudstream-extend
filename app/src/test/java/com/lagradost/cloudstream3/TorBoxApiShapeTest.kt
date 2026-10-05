package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.torrin.TorBox
import com.lagradost.cloudstream3.torrin.TorBoxWebDl
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The responses below were captured from a live TorBox account, not written from
 * the API docs: TorBox's OpenAPI document leaves the webdl endpoints and
 * /user/me schemas empty, so the field names are only trustworthy because they
 * were observed.
 *
 * These pin the parts of the API the app depends on. The shape of a populated
 * webdl item (its `files` array) and of a successful `createwebdownload` are
 * still uncovered, because observing those needs a real hoster link.
 */
class TorBoxApiShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun userMeUsesTheFieldNamesTorBoxActuallyReturns() {
        val body = """
            {"data":{"additional_concurrent_slots":0,"auth_id":"b6ef19f4","base_email":"a@b.c",
            "cooldown_until":"2026-10-05T10:34:02Z","created_at":"2025-05-30T18:00:21Z",
            "customer":"b6ef19f4","email":"s@example.com","id":169399,"is_subscribed":true,
            "is_vendor":false,"long_term_seeding":false,"long_term_storage":false,"plan":1,
            "premium_expires_at":"2027-09-28T13:14:57Z","purchases_referred":0,
            "torrents_downloaded":9,"total_bytes_downloaded":1180090590,
            "total_bytes_uploaded":606430,"total_downloaded":2,"updated_at":"2026-10-04T22:10:00Z",
            "usenet_downloads_downloaded":0,"user_referral":"9a7b1f38","vendor_id":null,
            "web_downloads_downloaded":0},
            "detail":"Successfully retrieved your user information.","error":null,"success":true}
        """.trimIndent()

        val parsed = json.decodeFromString<TorBox.TbUserResponse>(body)
        assertTrue(parsed.success)
        val user = parsed.data!!
        // The old model read premium/expires_at/current_plan, none of which exist,
        // so every account used to render as "Free (Unknown)".
        assertEquals(1, user.plan)
        assertEquals("2027-09-28T13:14:57Z", user.premium_expires_at)
        assertTrue(user.is_subscribed)
        assertEquals(1180090590L, user.total_bytes_downloaded)
        assertEquals(0, user.web_downloads_downloaded)
    }

    @Test
    fun webdlListIsAListNotAnObject() {
        // /torrents/mylist?id= answers with a single object, /webdl/mylist does
        // not, and modelling it as an object would silently never match.
        val empty = """{"data":[],"detail":"web downloads list retrieved successfully.","error":null,"success":true}"""
        val parsed = json.decodeFromString<TorBoxWebDl.ListResponse>(empty)
        assertTrue(parsed.success)
        assertEquals(0, parsed.data?.size)
    }

    @Test
    fun cachedOnlyMissIsAnErrorBodyWithAReason() {
        // add_only_if_cached on something TorBox does not have comes back as HTTP
        // 500 with this body, which is the only place the reason is stated.
        val body = """{"data":null,"detail":"Web download not found in cache. Disable the add only if cached parameter to add the download.","error":"DOWNLOAD_NOT_CACHED","success":false}"""
        val parsed = json.decodeFromString<TorBoxWebDl.CreateResponse>(body)
        assertFalse(parsed.success)
        assertEquals("DOWNLOAD_NOT_CACHED", parsed.error)
        assertTrue(parsed.detail!!.contains("not found in cache"))
    }

    @Test
    fun cacheCheckIsAMapKeyedByHash() {
        // Both /torrents/checkcached and /webdl/checkcached answer with a map; an
        // empty map means "not cached".
        val body = """{"data":{},"detail":"Web download cache status retrieved successfully.","error":null,"success":true}"""
        val parsed = json.decodeFromString<TorBox.TbCacheCheckResponse>(body)
        assertTrue(parsed.success)
        assertTrue(parsed.data.isNullOrEmpty())
    }
}