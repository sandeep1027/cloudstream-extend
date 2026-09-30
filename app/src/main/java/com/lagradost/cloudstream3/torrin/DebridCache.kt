package com.lagradost.cloudstream3.torrin

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for resolved debrid streams.
 *
 * Caches the mapping from magnet info_hash to resolved stream URLs so we
 * don't re-submit the same magnets to the debrid API within the cache TTL.
 *
 * Thread-safe: uses ConcurrentHashMap for lock-free concurrent access.
 */
object DebridCache {

    private data class CacheEntry(
        val link: ExtractorLink,
        val expiresAt: Long,
    )

    // info_hash -> CacheEntry
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Default cache TTL: 2 hours (debrid stream URLs typically expire after 3-4 hours) */
    private const val DEFAULT_TTL_MS = 2 * 60 * 60 * 1000L

    /**
     * Gets a cached stream link for the given info_hash, or null if not cached or expired.
     */
    fun get(infoHash: String): ExtractorLink? {
        val entry = cache[infoHash.uppercase()] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(infoHash.uppercase())
            return null
        }
        return entry.link
    }

    /**
     * Caches a stream link for the given info_hash with the default TTL.
     */
    fun put(infoHash: String, link: ExtractorLink) {
        put(infoHash, link, DEFAULT_TTL_MS)
    }

    /**
     * Caches a stream link for the given info_hash with a custom TTL.
     */
    fun put(infoHash: String, link: ExtractorLink, ttlMs: Long) {
        cache[infoHash.uppercase()] = CacheEntry(
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
