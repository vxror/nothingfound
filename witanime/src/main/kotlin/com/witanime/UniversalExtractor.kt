package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import android.util.Base64

class UniversalExtractor : ExtractorApi() {
    override val name = "UniversalSniffer"
    override val mainUrl = "https://universal.sniffer"
    override val requiresReferer = false
    override fun getExtractorUrl(id: String) = ""

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val links = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            
            suspend fun scrape(targetUrl: String, currentReferer: String?, depth: Int) {
                if (depth > 2 || visited.contains(targetUrl)) return
                visited.add(targetUrl)
                
                val html = try {
                    app.get(targetUrl, referer = currentReferer, interceptor = WebViewResolver(Regex(".*"))).text
                } catch (e: Exception) {
                    try { app.get(targetUrl, referer = currentReferer).text } catch (_: Exception) { "" }
                }
                
                if (html.isBlank()) return
                
                val unpacked = unpackJs(html)
                val decoded = decodeBase64(html + "\n" + unpacked)
                val fullText = html + "\n" + unpacked + "\n" + decoded
                
                val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                val mp4Regex = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                
                m3u8Regex.findAll(fullText).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
                mp4Regex.findAll(fullText).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
                
                val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                iframeRegex.findAll(html).forEach {
                    var src = it.groupValues[1]
                    if (src.startsWith("//")) src = "https:$src"
                    else if (src.startsWith("/")) {
                        try { src = java.net.URL(java.net.URL(targetUrl), src).toString() } catch (_: Exception) {}
                    }
                    if (src.startsWith("http")) scrape(src, targetUrl, depth + 1)
                }
            }
            
            scrape(url, referer, 0)
            
            links.forEach { link ->
                val type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = when {
                    link.contains("1080", true) || link.contains("hlsv1", true) -> Qualities.P1080.value
                    link.contains("720", true) -> Qualities.P720.value
                    link.contains("480", true) -> Qualities.P480.value
                    link.contains("360", true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback(newExtractorLink("Universal", "Universal Sniffer", link, type) {
                    this.referer = referer ?: url
                    this.quality = quality
                })
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: UniversalExtractor error: ${e.message}")
        }
    }

    private fun unpackJs(html: String): String {
        var result = ""
        val regex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        regex.findAll(html).forEach { match ->
            try {
                val packed = match.value
                val payload = Regex("""'([^']*)'""").find(packed)?.groupValues?.get(1) ?: return@forEach
                val radix = Regex(""",(\d+),""").find(packed)?.groupValues?.get(1)?.toIntOrNull() ?: 62
                val words = Regex("""split\('([^']*)'\)""").find(packed)?.groupValues?.get(1)?.split("|") ?: return@forEach
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                fun unbase(v: String): Int = v.fold(0) { acc, c -> acc * radix + chars.indexOf(c) }
                val unpacked = payload.replace(Regex("""\b\w+\b""")) { mr ->
                    val token = mr.value
                    val idx = try { unbase(token) } catch (e: Exception) { -1 }
                    if (idx in words.indices && words[idx].isNotEmpty()) words[idx] else token
                }
                result += "\n" + unpacked
            } catch (_: Exception) {}
        }
        return result
    }

    private fun decodeBase64(html: String): String {
        var result = ""
        val regex = Regex("""(?:atob|base64\.decode)\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { match ->
            try {
                val b64 = match.groupValues[1]
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                result += "\n" + decoded
            } catch (_: Exception) {}
        }
        return result
    }
}
