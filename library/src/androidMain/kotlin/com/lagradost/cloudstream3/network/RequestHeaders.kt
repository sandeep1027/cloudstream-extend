package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders

/**
 * Merge request headers, cookies and the default user agent into one okhttp
 * [Headers] block.
 *
 * Precedence, highest first: explicit headers, then cookies, then the default
 * user agent, so a caller can always override it.
 *
 * Lives in the library rather than the app because [CloudflareKiller] needs it
 * and is itself in the library: a .cs3 plugin that has to talk to a
 * Cloudflare-fronted site can now pass an Interceptor, which was impossible while
 * the class was app-internal.
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>,
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) {
            mapOf(
                "Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" }
            )
        } else {
            emptyMap()
        }
    val merged = mapOf("user-agent" to USER_AGENT) + headers + cookieMap
    return merged.toHeaders()
}