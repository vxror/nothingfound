package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink

object ZenUniversalSniffer {
    
    // 🧠 BEAST FEATURE: Deep Deobfuscation Engine
    private suspend fun deepUnwrap(html: String): String {
        var result = html
        var depth = 0
        while (depth < 5) {
            var changed = false
            
            // 1. P.A.C.K.E.R.
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(result)
            if (packed != null) {
                result += "\n" + ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
                changed = true
            }
            
            // 2. eval(atob(...))
            Regex("""eval\s*\(\s*atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)\s*\)""").findAll(result).forEach { match ->
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                    result = result.replace(match.value, decoded)
                    changed = true
                } catch (_: Exception) {}
            }
            
            // 3. String.fromCharCode chains
            Regex("""String\.fromCharCode\s*\(\s*(\d+(?:\s*,\s*\d+)+)\s*\)""").findAll(result).forEach { match ->
                try {
                    val codes = match.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                    val decoded = codes.map { it.toChar() }.joinToString("")
                    result = result.replace(match.value, "\"$decoded\"")
                    changed = true
                } catch (_: Exception) {}
            }
            
            if (!changed) break
            depth++
        }
        return result
    }

    suspend fun deepScan(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val response = app.get(url, headers = headers, referer = url, allowRedirects = true)
            var text = response.text
            
            // 🚀 APPLY BEAST DEOBFUSCATION
            text = deepUnwrap(text)
            
            // Standard Unpacking
            if (text.contains("eval(function(p,a,c,k,e,d)")) { 
                try { JsUnpacker(text).unpack()?.let { text += "\n" + it } } catch (_: Exception) {} 
            }
            
            // Base64 Decoding
            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").findAll(text).forEach { match ->
                try { text += "\n" + String(Base64.decode(match.groupValues[1], Base64.DEFAULT)) } catch (_: Exception) {}
            }

            // 🎯 ENHANCED REGEX PATTERNS
            val patterns = listOf(
                Regex("""(?:file|source|src)\s*:\s*["'](https?://[^"']+?(?:\.m3u8|\.mp4|\.mkv|\.avi|\.webm)[^"']*?)["']""", RegexOption.IGNORE_CASE),
                Regex("""<source\s+src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""<video[^>]+src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4|mkv|avi|webm)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
            )
            
            val allLinks = mutableSetOf<String>()
            for (pattern in patterns) {
                pattern.findAll(text).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/")
                    if (link.length > 20 && !link.contains(".css") && !link.contains(".js")) allLinks.add(link)
                }
            }
            
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
