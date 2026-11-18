package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver

open class HgCloudExtractor : ExtractorApi() {
    override val name = "HgCloud"
    override val mainUrl = "https://hgcloud.to"
    override val requiresReferer = false

    private val family = listOf("hgcloud", "vibuxer", "hanerix", "streamhg", "huntrexus")

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            var pageUrl = url
            var corpus = ""
            val full = sessionGetFull(url, referer = referer ?: "$mainUrl/")
            if (full != null && !WitaWeb.looksChallenge(full.second)) {
                pageUrl = full.first; corpus = full.second
            } else {
                val r = try {
                    app.get(url, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA), interceptor = CloudflareKiller())
                } catch (_: Exception) { null }
                if (r != null) { pageUrl = r.url; corpus = r.text }
            }
            if (corpus.isBlank()) { webViewFallback(url, hostOf(pageUrl), callback); return }

            var playerHost = hostOf(pageUrl)
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(corpus)
                .map { it.groupValues[1] }.take(4).forEach { raw ->
                    var src = raw
                    if (src.startsWith("//")) src = "https:$src"
                    if (!src.startsWith("http")) return@forEach
                    val h = linkHost(src)
                    val looksPlayer = family.any { h.contains(it) } || src.contains("/e/") || src.contains("/embed/")
                    if (!looksPlayer || h == linkHost(pageUrl)) return@forEach
                    val inner = sessionGetFull(src, referer = pageUrl)
                    if (inner != null && inner.second.length > 500) {
                        corpus += "\n" + inner.second
                        playerHost = hostOf(inner.first)
                        WitaLog.d("HgCloud player iframe → $playerHost")
                    }
                }

            val unpacked = runCatching { getAndUnpack(corpus) }.getOrNull()?.ifBlank { null } ?: unpackPackedJs(corpus) ?: ""
            val js = "$corpus\n$unpacked".replace("\\/", "/").replace("\\\"", "\"")
            val playbackHeaders = mapOf("User-Agent" to EXTRACTOR_UA, "Referer" to "$playerHost/", "Origin" to playerHost)

            val candidates = LinkedHashMap<String, String>()
            Regex("""(?:file|src)\s*:\s*["']([^"']+(?:master\.txt|\.m3u8)[^"']*)["']""")
                .findAll(js).forEach { candidates[it.groupValues[1]] = "primary" }
            Regex("""["'](hls\d)["']\s*:\s*["']([^"']+)["']""")
                .findAll(js).forEach { candidates[it.groupValues[2]] = it.groupValues[1] }
            Regex("""(https?://[^\s"'<>\\]+(?:master\.txt|\.m3u8)[^\s"'<>\\]*)""")
                .findAll(js).forEach { candidates.putIfAbsent(it.groupValues[1], "bare") }
            Regex("""["'](/[^"']*(?:master\.txt|\.m3u8)[^"']*)["']""")
                .findAll(js).forEach { candidates.putIfAbsent("$playerHost${it.groupValues[1]}", "rel") }

            val ordered = candidates.entries.sortedBy { (_, k) ->
                when (k) { "primary" -> 0; "hls3" -> 1; "hls2" -> 2; "hls4" -> 3; "hls1" -> 4; else -> 5 }
            }.map { it.key }.distinct().take(5)

            WitaLog.d("HgCloud candidates=${ordered.size} player=$playerHost")
            for (c in ordered) {
                val abs = when { c.startsWith("http") -> c; c.startsWith("//") -> "https:$c"; else -> "$playerHost$c" }
                if (emitManifest(abs, playbackHeaders, callback)) { WitaLog.d("HgCloud OK -> ${abs.take(90)}"); return }
            }
            webViewFallback(url, playerHost, callback)
        } catch (e: Exception) { WitaLog.e("HgCloud error: ${e.message}") }
    }

    private suspend fun emitManifest(manifestUrl: String, headers: Map<String, String>, callback: (ExtractorLink) -> Unit): Boolean {
        val body = sessionGet(manifestUrl, referer = headers["Referer"], headers = headers) ?: return false
        if (!body.contains("#EXTM3U")) return false
        val base = try { java.net.URI(manifestUrl) } catch (_: Exception) { return false }
        var emitted = false
        var pendingRes: Int? = null
        val seen = HashSet<String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingRes = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                line.isNotEmpty() && !line.startsWith("#") && pendingRes != null -> {
                    val variant = try { base.resolve(line).toString() } catch (_: Exception) { line }
                    if (seen.add(variant)) {
                        val res = pendingRes!!
                        callback(newExtractorLink(name, "$name ${res}p", variant, ExtractorLinkType.M3U8) {
                            this.referer = headers["Referer"] ?: ""
                            this.headers = headers
                            quality = getQualityFromName("${res}p")
                        })
                        emitted = true
                    }
                    pendingRes = null
                }
            }
        }
        if (!emitted) {
            callback(newExtractorLink(name, name, manifestUrl, ExtractorLinkType.M3U8) {
                this.referer = headers["Referer"] ?: ""
                this.headers = headers
                quality = urlQuality(manifestUrl)
            })
            emitted = true
        }
        return emitted
    }

    private suspend fun webViewFallback(url: String, playerHost: String, callback: (ExtractorLink) -> Unit) {
        try {
            val rx = Regex("""master\.txt|\.m3u8|index-v\d""")
            val resolver = WebViewResolver(interceptUrl = rx, additionalUrls = listOf(rx), useOkhttp = false, timeout = 15_000L)
            val intercepted = app.get(url, referer = "$mainUrl/", interceptor = resolver).url
            if (intercepted.contains("master.txt") || intercepted.contains(".m3u8") || intercepted.contains("index-v")) {
                val origin = when {
                    playerHost.isBlank() || playerHost.contains("hgcloud") -> "https://vibuxer.com"
                    else -> playerHost
                }
                emitManifest(intercepted, mapOf("User-Agent" to EXTRACTOR_UA, "Referer" to "$origin/", "Origin" to origin), callback)
            }
        } catch (e: Exception) { WitaLog.d("HgCloud WV fail: ${e.message}") }
    }
}

class VibuxerExtractor : HgCloudExtractor() {
    override val name = "Vibuxer"
    override val mainUrl = "https://vibuxer.com"
}
