package com.animewitcher

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink

object ZenUniversalSniffer {
    suspend fun deepScan(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            val response = app.get(url, headers = headers, referer = url, allowRedirects = true)
            var text = response.text
            if (text.contains("eval(function(p,a,c,k,e,d)")) { try { JsUnpacker(text).unpack()?.let { text += "\n" + it } } catch (_: Exception) {} }
            val jwPlayerRegex = Regex("""(?:file|source|src)\s*:\s*["'](https?://[^"']+?(?:\.m3u8|\.mp4)[^"']*?)["']""", RegexOption.IGNORE_CASE)
            // [!] CI/CD FIX: .toList() terminates the Sequence to allow .distinct() and .isNotEmpty()
            val jwMatches = jwPlayerRegex.findAll(text).map { it.groupValues[1].replace("\\/", "/") }.toList().distinct()
            val rawUrlRegex = Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
            val rawMatches = rawUrlRegex.findAll(text).map { it.groupValues[1].replace("\\/", "/") }.toList().distinct()
            val allLinks = (jwMatches + rawMatches).filter { link ->
                !link.contains(".css") && !link.contains(".js") && !link.contains("doubleclick.net") && !link.contains("googlesyndication") && link.length > 30
            }.distinct()
            if (allLinks.isNotEmpty()) {
                for (link in allLinks) {
                    val type = if (link.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(newExtractorLink(source = sourceName, name = "$sourceName Sniffed", url = link, type = type) {
                        this.referer = url; this.quality = quality; this.headers = headers
                    })
                }
                return true
            }
            false
        } catch (e: Exception) { false }
    }
}
