package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

object ZenUniversalSniffer {

    private val JUNK_MARKERS = listOf(
        "doubleclick.net", "googlesyndication", "google-analytics", "facebook.com",
        "twitter.com", "instagram.com", "cdn-cgi", "recaptcha", "captcha",
        ".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".woff", ".ttf"
    )

    private fun isJunk(link: String): Boolean =
        link.length <= 20 || JUNK_MARKERS.any { link.contains(it, ignoreCase = true) }

    private fun detectQuality(url: String): Int = when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1440", true) -> Qualities.P1440.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private val VIDEO_PATTERNS = listOf(
        Regex("""(?:file|source|src|url)\s*:\s*["'](https?://[^"']+?(?:\.m3u8|\.mp4|\.mkv|\.avi|\.webm)[^"']*?)["']""", RegexOption.IGNORE_CASE),
        Regex("""<source\s+[^>]*src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""<video[^>]+src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""data-(?:video|url|src|embed|file|source)\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4|mkv|avi|webm)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
    )

    // 🧠 DEEP DEOBFUSCATION ENGINE (5 layers)
    private fun deepUnwrap(html: String): String {
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
                    result = result.replace(match.value, "\"${codes.map { it.toChar() }.joinToString("")}\"")
                    changed = true
                } catch (_: Exception) {}
            }

            // 4. 🆕 \xNN hex escapes
            if (result.contains("\\x")) {
                val decoded = result.replace(Regex("""\\x([0-9a-fA-F]{2})""")) { m -> (m.groupValues[1].toInt(16).toChar()).toString() }
                if (decoded != result) { result = decoded; changed = true }
            }

            // 5. 🆕 \uNNNN unicode escapes
            if (result.contains("\\u")) {
                val decoded = result.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { m -> (m.groupValues[1].toInt(16).toChar()).toString() }
                if (decoded != result) { result = decoded; changed = true }
            }

            // 6. 🆕 unescape / decodeURIComponent
            Regex("""(?:unescape|decodeURIComponent)\s*\(\s*["'](%[^"']+)["']""").findAll(result).forEach { match ->
                try {
                    result = result.replace(match.value, URLDecoder.decode(match.groupValues[1], "UTF-8"))
                    changed = true
                } catch (_: Exception) {}
            }

            if (!changed) break
            depth++
        }
        return result
    }

    private fun collectLinks(text: String, out: MutableSet<String>) {
        for (pattern in VIDEO_PATTERNS) {
            pattern.findAll(text).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                if (!isJunk(link) && link.startsWith("http")) out.add(link)
            }
        }
    }

    // 🆕 IFRAME RECURSION: follow players hidden inside players (max 2)
    private suspend fun scanIframes(html: String, referer: String, headers: Map<String, String>, out: MutableSet<String>) {
        var count = 0
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            if (count >= 2) return@forEach
            var src = match.groupValues[1]
            if (src.startsWith("//")) src = "https:$src"
            if (!src.startsWith("http") || isJunk(src)) return@forEach
            try {
                val sub = app.get(src, headers = headers, referer = referer).text
                collectLinks(deepUnwrap(sub), out)
                count++
            } catch (_: Exception) {}
        }
    }

    suspend fun deepScan(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers, referer = url, allowRedirects = true)
            var text = deepUnwrap(response.text)

            if (text.contains("eval(function(p,a,c,k,e,d)")) {
                try { JsUnpacker(text).unpack()?.let { text += "\n" + it } } catch (_: Exception) {}
            }

            Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").findAll(text).forEach { match ->
                try { text += "\n" + String(Base64.decode(match.groupValues[1], Base64.DEFAULT)) } catch (_: Exception) {}
            }

            val allLinks = mutableSetOf<String>()
            collectLinks(text, allLinks)

            // 🆕 Nothing found? Follow nested iframes
            if (allLinks.isEmpty()) scanIframes(response.text, url, headers, allLinks)

            if (allLinks.isNotEmpty()) {
                for (link in allLinks) {
                    val type = if (link.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(newExtractorLink(source = sourceName, name = "$sourceName Sniffed", url = link, type = type) {
                        this.referer = url
                        this.quality = detectQuality(link).takeIf { it != Qualities.Unknown.value } ?: quality
                        this.headers = headers
                    })
                }
                return true
            }
            false
        } catch (e: Exception) { false }
    }
}
