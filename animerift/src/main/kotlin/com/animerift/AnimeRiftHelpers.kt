package com.animerift

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

// ---------- shared data types ----------

data class AnimeRiftLink(
    val serverName: String,
    val subTitle: String,
    val quality: String,
    val url: String,
    val headers: Map<String, String>
)

data class AnimeRiftBundle(
    val links: List<AnimeRiftLink>,
    val subtitles: List<SubtitleFile>,
    val createdAt: Long
)

// ---------- LRU + TTL cache for JSON responses ----------

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
        private val TTL_BY_SCOPE = mapOf(
            "ANIME.LIBRARY.HOME_CONTENT" to 5 * 60 * 1000L,
            "ANIME.LIBRARY.SEARCH" to 3 * 60 * 1000L,
            "ANIME.LIBRARY.DETAILS" to 10 * 60 * 1000L,
            "ANIME.LIBRARY.EPISODES.ALL" to 10 * 60 * 1000L,
            "ANIME.LIBRARY.EPISODES.SOURCES.ALL" to 90 * 1000L,
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

// ---------- prefetcher ----------

class AnimeRiftPrefetcher(
    private val tag: String,
    private val ttlMs: Long = 5 * 60 * 1000L,
    private val windowSize: Int = 2
) {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("AnimeRift-prefetch")
    )

    private val cache = ConcurrentHashMap<String, AnimeRiftBundle>()
    private val inFlight = ConcurrentHashMap<String, Job>()

    @Volatile private var episodeOrder: List<String> = emptyList()
    @Volatile private var animeId: String = ""

    fun setEpisodeOrder(animeId: String, ordered: List<String>) {
        this.animeId = animeId
        this.episodeOrder = ordered.toList()
        val it = cache.keys.iterator()
        while (it.hasNext()) if (!it.next().startsWith("$animeId|")) it.remove()
    }

    fun cached(data: String): AnimeRiftBundle? {
        val b = cache[data] ?: return null
        if (System.currentTimeMillis() - b.createdAt > ttlMs) {
            cache.remove(data)
            return null
        }
        return b
    }

    fun store(data: String, bundle: AnimeRiftBundle) {
        cache[data] = bundle.copy(createdAt = System.currentTimeMillis())
        prune()
    }

    fun triggerAround(currentData: String, resolve: suspend (String) -> AnimeRiftBundle?) {
        val idx = episodeOrder.indexOf(currentData)
        if (idx < 0) return
        for (offset in 1..windowSize) {
            val next = episodeOrder.getOrNull(idx + offset) ?: continue
            schedule(next, resolve)
        }
    }

    fun triggerAll(resolve: suspend (String) -> AnimeRiftBundle?) {
        for (ep in episodeOrder.take(2)) schedule(ep, resolve)
    }

    private fun schedule(data: String, resolve: suspend (String) -> AnimeRiftBundle?) {
        if (cache.containsKey(data)) return
        if (inFlight.containsKey(data)) return

        val job = scope.launch {
            try {
                val b = resolve(data) ?: return@launch
                store(data, b)
                Log.d(tag, "prefetched ${b.links.size} links for $data")
            } catch (e: Exception) {
                Log.e(tag, "prefetch $data: ${e.message}")
            } finally {
                inFlight.remove(data)
            }
        }
        inFlight[data] = job
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - ttlMs
        val it = cache.entries.iterator()
        while (it.hasNext()) if (it.next().value.createdAt < cutoff) it.remove()
    }

    fun cancelAll() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        cache.clear()
    }
}

// ---------- http helpers ----------

object AnimeRiftHttp {

    private const val TAG = "AnimeRiftHttp"

    private val warmed = ConcurrentHashMap.newKeySet<String>()

    fun prewarm(vararg hosts: String) {
        for (h in hosts) {
            if (!warmed.add(h)) continue
            Thread {
                try {
                    val t0 = System.currentTimeMillis()
                    InetAddress.getAllByName(h)
                    Log.d(TAG, "dns prewarm $h in ${System.currentTimeMillis() - t0}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "dns prewarm $h: ${e.message}")
                }
            }.apply { isDaemon = true; name = "af-dns-$h" }.start()
        }
    }

    suspend fun <T, R> pmap(
        items: List<T>,
        concurrency: Int = 6,
        block: suspend (T) -> R
    ): List<R> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val sem = Semaphore(concurrency)
        coroutineScope {
            items.map { item ->
                async { sem.withPermit { block(item) } }
            }.awaitAll()
        }
    }

    suspend fun <T> retry(
        attempts: Int = 3,
        baseDelayMs: Long = 250L,
        block: suspend () -> T
    ): T {
        var last: Throwable? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: Throwable) {
                val m = e.message.orEmpty()
                val retryable = e is java.io.IOException ||
                    m.contains("timeout", ignoreCase = true) ||
                    m.contains("500") || m.contains("502") ||
                    m.contains("503") || m.contains("504")
                if (!retryable || i == attempts - 1) throw e
                last = e
                delay(baseDelayMs * (1L shl i) + (0..100).random())
            }
        }
        throw last ?: IllegalStateException("retry exhausted")
    }
}
