package com.animerift

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AnimeRiftHttp {

    private const val TAG = "AnimeRiftHttp"

    private val warmed = ConcurrentHashMap.newKeySet<String>()

    /** Fire-and-forget DNS resolve. Cheap, warms the resolver cache. */
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

    /**
     * Bounded parallel map. Replaces the async/awaitAll + semaphore dance
     * in hot loops so callers stay one-liners.
     */
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

    /**
     * Retry with exponential backoff, jittered.
     * Only retries on IOException or 5xx — not on 4xx.
     */
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
                val delayMs = baseDelayMs * (1L shl i) + (0..100).random()
                kotlinx.coroutines.delay(delayMs)
            }
        }
        throw last ?: IllegalStateException("retry exhausted")
    }
}
