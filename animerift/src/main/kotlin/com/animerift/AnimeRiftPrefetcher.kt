package com.animerift

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Prefetch window around the currently-playing episode.
 * Resolves N+1 and N+2 in the background so switching is instant.
 * Emits results into a shared cache the main API reads from.
 */
class AnimeRiftPrefetcher(
    private val tag: String,
    private val ttlMs: Long = 5 * 60 * 1000L,
    private val windowSize: Int = 2
) {

    data class ResolvedLink(
        val serverName: String,
        val subTitle: String,
        val quality: String,
        val url: String,
        val headers: Map<String, String>
    )

    data class Bundle(
        val links: List<ResolvedLink>,
        val subtitles: List<SubtitleFile>,
        val createdAt: Long
    )

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("AnimeRift-prefetch")
    )

    private val cache = ConcurrentHashMap<String, Bundle>()
    private val inFlight = ConcurrentHashMap<String, Job>()

    @Volatile private var episodeOrder: List<String> = emptyList()
    @Volatile private var animeId: String = ""

    fun setEpisodeOrder(animeId: String, ordered: List<String>) {
        this.animeId = animeId
        this.episodeOrder = ordered.toList()
        // drop prefetches from any other show
        val it = cache.keys.iterator()
        while (it.hasNext()) if (!it.next().startsWith("$animeId|")) it.remove()
    }

    fun cached(data: String): Bundle? {
        val b = cache[data] ?: return null
        if (System.currentTimeMillis() - b.createdAt > ttlMs) {
            cache.remove(data)
            return null
        }
        return b
    }

    fun store(data: String, bundle: Bundle) {
        cache[data] = bundle
        prune()
    }

    fun triggerAround(currentData: String, resolve: suspend (String) -> Bundle?) {
        val idx = episodeOrder.indexOf(currentData)
        if (idx < 0) return
        for (offset in 1..windowSize) {
            val next = episodeOrder.getOrNull(idx + offset) ?: continue
            schedule(next, resolve)
        }
    }

    fun triggerAll(resolve: suspend (String) -> Bundle?) {
        // warm the first 2 of the show when the details page opens
        for (ep in episodeOrder.take(2)) schedule(ep, resolve)
    }

    private fun schedule(data: String, resolve: suspend (String) -> Bundle?) {
        if (cache.containsKey(data)) return
        if (inFlight.containsKey(data)) return

        val job = scope.launch {
            try {
                val b = resolve(data) ?: return@launch
                store(data, b.copy(createdAt = System.currentTimeMillis()))
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
