package com.animewitcher

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink

object VidTubeExtractor {
    suspend fun extract(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            val html = app.get(url, headers = headers, referer = url).text
            var text = html
            
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(text)
            if (packed != null) {
                text += "\n" + ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
            } else {
                try { JsUnpacker(text).unpack()?.let { text += "\n" + it } } catch (_: Exception) {}
            }

            val videoRegex = Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
            val match = videoRegex.find(text)
            val videoUrl = match?.groupValues?.get(1)
            if (videoUrl != null) {
                val finalUrl = videoUrl.replace("\\/", "/")
                val type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(newExtractorLink(source = sourceName, name = sourceName, url = finalUrl, type = type) {
                    this.referer = url; this.quality = quality
                })
                return true
            }
            false
        } catch (e: Exception) { false }
    }
}
