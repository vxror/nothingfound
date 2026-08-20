package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker

object ZenUniversalSniffer {
    suspend fun deepScan(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            val response = app.get(url, headers = headers, referer = url, allowRedirects = true)
            var text = response.text
            if (text.contains("eval(function(p,a,c,k,e,d)")) { try { JsUnpacker(text).unpack()?.let { text += "\n" + it } } catch (_: Exception) {} }
            
            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").findAll(text).forEach { match ->
                try { text += "\n" + String(Base64.decode(match.groupValues[1], Base64.DEFAULT)) } catch (_: Exception) {}
            }

            val jwPlayerRegex = Regex("""(?:file|source|src)\s*:\s*["'](https?://[^"']+?(?:\.m3u8|\.mp4|\.mkv|\.avi|\.webm)[^"']*?)["']""", RegexOption.IGNORE_CASE)
            val jwMatches = jwPlayerRegex.findAll(text).map { it.groupValues[1].replace("\\/", "/") }.toList().distinct()
            val rawUrlRegex = Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4|mkv|avi|webm)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
            val rawMatches = rawUrlRegex.findAll(text).map { it.groupValues[1].replace("\\/", "/") }.toList().distinct()
            
            val allLinks = (jwMatches + rawMatches).filter { link ->
                !link.contains(".css") && !link.contains(".js") && !link.contains("doubleclick.net") && !link.contains("googlesyndication") && link.length > 30
            }.distinct()
            
            if (allLinks.isNotEmpty()) {
                for (link in allLinks) {
                    val type = if (link.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    // [!] D8 FIX: Using LinkBuilder
                    callback.invoke(LinkBuilder.create(sourceName, "$sourceName Sniffed", link, type, quality, url, headers))
                }
                return true
            }
            false
        } catch (e: Exception) { false }
    }
}
