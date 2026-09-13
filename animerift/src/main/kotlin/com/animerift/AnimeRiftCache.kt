package com.animerift

import com.fasterxml.jackson.databind.JsonNode

/**
 * Thread-safe LRU cache with per-entry TTL.
 * Wraps apiCall so home/details/episodes can be served from memory
 * during normal navigation. Session-bound endpoints (can_play,
 * direct_link) are NOT cached — see ApiCachePolicy below.
 */
class AnimeRiftCache(private val maxEntries: Int = 300) {

    private data class Entry(val node: JsonNode, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): JsonNode? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.node
    }

    @Synchronized
    fun put(key: String, node: JsonNode, ttlMs: Long) {
        map[key] = Entry(node, System.currentTimeMillis() + ttlMs)
    }

    @Synchronized
    fun invalidatePrefix(prefix: String) {
        val it = map.keys.iterator()
        while (it.hasNext()) if (it.next().startsWith(prefix)) it.remove()
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size

    companion object {
        // route -> TTL (ms). 0 = do not cache.
        private val TTL_BY_SCOPE = mapOf(
            "ANIME.LIBRARY.HOME_CONTENT" to 5 * 60 * 1000L,
            "ANIME.LIBRARY.SEARCH" to 3 * 60 * 1000L,
            "ANIME.LIBRARY.DETAILS" to 10 * 60 * 1000L,
            "ANIME.LIBRARY.EPISODES.ALL" to 10 * 60 * 1000L,
            "ANIME.LIBRARY.EPISODES.SOURCES.ALL" to 90 * 1000L,
            // session-bound — never cache
            "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY" to 0L,
            "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK" to 0L,
            "USER.ADS_MANAGER.CLAIMS" to 0L,
            "USER.AUTH.DEVICE.REGISTER" to 0L
        )

        fun ttlFor(scope: String): Long = TTL_BY_SCOPE[scope] ?: 0L

        fun cacheKey(method: String, url: String, body: Map<String, Any?>?): String {
            val bodyPart = body?.entries
                ?.sortedBy { it.key }
                ?.joinToString("&") { "${it.key}=${it.value}" }
                ?: ""
            return "$method|$url|$bodyPart"
        }
    }
}
