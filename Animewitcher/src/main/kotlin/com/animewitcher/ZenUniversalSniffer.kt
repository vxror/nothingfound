package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink

object ZenUniversalSniffer {
    
    /**
     * 🚀 ENHANCED: Deep scan with more patterns + base64 decoding + quality detection
     */
    suspend fun deepScan(
        url: String, sourceName: String, 
        quality: Int, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8"
            )
            
            val response = app.get(url, headers = headers, referer = url, allowRedirects = true)
            var text = response.text
            
            // 🆕 Handle P.A.C.K.E.R. obfuscation (multiple methods)
            if (text.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    JsUnpacker(text).unpack()?.let { text += "\n" + it }
                } catch (_: Exception) {}
                
                // Custom unpacker
                val packed = ZenCryptoAndObfuscation.findPackedJsInPage(text)
                if (packed != null) {
                    text += "\n" + ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
                }
            }
            
            // 🆕 Handle atob/base64 obfuscation
            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
                .findAll(text).forEach { match ->
                    try {
                        val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                        text += "\n" + decoded
                    } catch (_: Exception) {}
                }
            
            // 🆕 Enhanced regex patterns
            val patterns = listOf(
                // JW Player
                Regex("""(?:file|source|src)\s*:\s*["'](https?://[^"']+?(?:\.m3u8|\.mp4)[^"']*?)["']""", RegexOption.IGNORE_CASE),
                // Video.js
                Regex("""<source\s+src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                // HTML5 video
                Regex("""<video[^>]+src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                // Direct URLs
                Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE),
                // MKV/AVI/WebM
                Regex("""(https?://[^"'\s\\<>]+\.(?:mkv|avi|webm)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
            )
            
            val allLinks = mutableSetOf<String>()
            
            for (pattern in patterns) {
                pattern.findAll(text).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/")
                    if (ZenCore.isValidVideoUrl(link)) {
                        allLinks.add(link)
                    }
                }
            }
            
            // 🆕 Filter out non-video links
            val filteredLinks = allLinks.filter { link ->
                ZenCore.isValidVideoUrl(link) && link.length > 20
            }.distinct()
            
            if (filteredLinks.isNotEmpty()) {
                for (link in filteredLinks) {
                    val type = when {
                        link.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }
                    
                    // 🆕 Detect quality from URL
                    val detectedQuality = ZenCore.detectQualityFromUrl(link) ?: quality
                    
                    callback.invoke(newExtractorLink(
                        source = sourceName,
                        name = "$sourceName Sniffed",
                        url = link,
                        type = type
                    ) {
                        this.referer = url
                        this.quality = detectedQuality
                        this.headers = headers
                    })
                }
                return true
            }
            false
        } catch (e: Exception) {
            ZenCore.logError("ZenUniversalSniffer.deepScan", e)
            false
        }
    }
}
