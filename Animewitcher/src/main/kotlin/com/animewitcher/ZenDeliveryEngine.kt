package com.animewitcher

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object ZenDeliveryEngine {
    private val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val RESOLUTION_RE = Regex("""RESOLUTION=\d+x(\d+)""")
    private val cache = ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()
    private const val TTL_MS = 60 * 60_000L // 1 hour

    private suspend fun probeHeaders(url: String, referer: String?): Map<String, String> = coroutineScope {
        val host = runCatching { URI(url).host }.getOrNull() 
            ?: return@coroutineScope mapOf("User-Agent" to DEFAULT_UA)
        
        // Check cache
        cache[host]?.let { (ts, headers) ->
            if (System.currentTimeMillis() - ts < TTL_MS) {
                ZenCore.cacheHitCount.incrementAndGet()
                return@coroutineScope headers
            }
        }
        
        val origin = try { 
            val u = URI(url); "${u.scheme}://${u.host}" 
        } catch (e: Exception) { null }
        
        val combos = listOf(
            mapOf("User-Agent" to DEFAULT_UA, "Accept" to "*/*"),
            mapOf("User-Agent" to DEFAULT_UA, "Accept" to "*/*", "Referer" to (referer ?: "")),
            mapOf(
                "User-Agent" to DEFAULT_UA, 
                "Accept" to "*/*", 
                "Referer" to (referer ?: ""), 
                "Origin" to (origin ?: "")
            )
        )
        
        // [!] CI/CD FIX: .toMutableList() allows .remove()
        val jobs = combos.map { headers ->
            async {
                try {
                    val res = app.get(url, headers = headers + ("Range" to "bytes=0-1023"), timeout = 5000)
                    if (res.code in 200..299 || res.code == 206) headers else null
                } catch (e: Exception) { 
                    null 
                }
            }
        }.toMutableList()
        
        while (jobs.isNotEmpty()) {
            val done = select<Deferred<Map<String, String>?>> { 
                jobs.forEach { job -> job.onAwait { job } } 
            }
            jobs.remove(done)
            val result = done.await()
            if (result != null) {
                jobs.forEach { it.cancel() }
                cache[host] = System.currentTimeMillis() to result
                return@coroutineScope result
            }
        }
        
        mapOf(
            "User-Agent" to DEFAULT_UA, 
            "Accept" to "*/*", 
            "Referer" to (referer ?: "")
        )
    }

    private fun parseVariants(masterText: String): List<Pair<String, Int?>> {
        val variants = mutableListOf<Pair<String, Int?>>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val height = RESOLUTION_RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val uriLine = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                if (uriLine.isNotBlank() && !uriLine.startsWith("#")) {
                    variants.add(uriLine to height)
                }
                i += 2
            } else { 
                i++ 
            }
        }
        return variants
    }

    private fun resolveUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http")) return path
        return try {
            val uri = URI(baseUrl)
            val origin = "${uri.scheme}://${uri.host}" + 
                if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            if (path.startsWith("/")) {
                origin + path
            } else {
                "$origin${uri.path.orEmpty().substringBeforeLast('/', "")}/$path"
            }
        } catch (e: Exception) { 
            path 
        }
    }

    /**
     * 🆕 QUALITY LABEL: Human-readable quality label from height.
     */
    private fun qualityLabel(height: Int?): String {
        return when {
            height == null -> "Auto"
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height >= 360 -> "360p"
            else -> "${height}p"
        }
    }

    suspend fun deliverSmartLink(
        source: String, 
        name: String, 
        url: String, 
        referer: String?, 
        quality: Int, 
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.isBlank()) return
        
        val effectiveHeaders = probeHeaders(url, referer)
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        
        if (isM3u8) {
            try {
                val masterText = app.get(url, headers = effectiveHeaders, timeout = 8000).text
                val variants = parseVariants(masterText)
                
                // If master playlist with multiple variants
                if (variants.isNotEmpty() && variants.size > 1) {
                    for ((variantPath, height) in variants) {
                        val variantUrl = resolveUrl(url, variantPath)
                        val label = qualityLabel(height)
                        
                        callback(newExtractorLink(
                            source = source,
                            name = "$name $label",
                            url = variantUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = height ?: quality
                            this.referer = referer ?: ""
                            this.headers = effectiveHeaders
                        })
                    }
                    return
                }
            } catch (e: Exception) {
                ZenCore.logError("ZenDeliveryEngine.deliverSmartLink", e)
            }
        }
        
        // Fallback: single link
        callback(newExtractorLink(
            source = source,
            name = name,
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.quality = quality
            this.referer = referer ?: ""
            this.headers = effectiveHeaders
        })
    }
}
