package com.lagradost.cloudstream3.torrin

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for resolved debrid streams.
 *
 * Caches the mapping from magnet info_hash to resolved stream URLs so we
 * don't re-submit the same magnets to the debrid API within the cache TTL.
 *
 * Entries are keyed by debrid service *and* info_hash. The same hash resolved
 * through two services is two different links (Torrin and TorBox hand out
 * different CDN URLs, and a cached-only TorBox link is a different flavour of
 * stream again), so a key of the bare hash would serve one service's answer to
 * another service's request.
 *
 * Thread-safe: uses ConcurrentHashMap for lock-free concurrent access.
 */
object DebridCache {

    private data class CacheEntry(
        val link: ExtractorLink,
        val expiresAt: Long,
    )

    // "source:INFO_HASH" -> CacheEntry
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Default cache TTL: 2 hours (debrid stream URLs typically expire after 3-4 hours) */
    private const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L

    private fun key(source: String, infoHash: String): String =
        "$source:${infoHash.uppercase()}"

    /**
     * Gets a cached stream link for the given info_hash from the given debrid
     * service, or null if not cached or expired.
     */
    fun get(infoHash: String, source: String): ExtractorLink? {
        val cacheKey = key(source, infoHash)
        val entry = cache[cacheKey] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(cacheKey)
            return null
        }
        return entry.link
    }

    /**
     * Caches a stream link for the given info_hash with the default TTL.
     */
    fun put(infoHash: String, source: String, link: ExtractorLink) {
        put(infoHash, source, link, DEFAULT_TTL_MS)
    }

    /**
     * Caches a stream link for the given info_hash with a custom TTL.
     */
    fun put(infoHash: String, source: String, link: ExtractorLink, ttlMs: Long) {
        cache[key(source, infoHash)] = CacheEntry(
            link = link,
            expiresAt = System.currentTimeMillis() + ttlMs
        )
    }

    /**
     * Clears all cached entries.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Returns the number of cached entries (for debugging).
     */
    fun size(): Int = cache.size

    /**
     * Removes expired entries (called periodically to prevent unbounded growth).
     */
    fun pruneExpired() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) -> now > entry.expiresAt }
    }
}
