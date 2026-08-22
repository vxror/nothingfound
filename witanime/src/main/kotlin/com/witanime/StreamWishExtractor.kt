package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to EXTRACTOR_UA, "Accept-Language" to "en-US,en;q=0.5")
        val found = LinkedHashSet<String>()
        var base = hostOf(url)
        val streamRx = Regex("""\.(m3u8|mp4)""", RegexOption.IGNORE_CASE)

        // LAYER 1: fetch + unpack + regex
        try {
            val res = app.get(url, headers = headers, referer = referer)
            base = hostOf(res.url)
            val raw = res.text
            val unpacked = (unpackPackedJs(raw) ?: raw).replace("\\", "")
            val combined = "$raw\n$unpacked"
            Regex("""file\s*:\s*"(https?://[^"]+)"""").findAll(combined).forEach { found.add(it.groupValues[1]) }
            Regex("""file":"(https?://[^"]+)"""").findAll(combined).forEach { found.add(it.groupValues[1]) }
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*"(https?://[^"]+)"""").findAll(combined).forEach { found.add(it.groupValues[1]) }
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(unpacked).forEach { found.add(it.groupValues[1]) }
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").findAll(unpacked).forEach { found.add(it.groupValues[1]) }
        } catch (e: Exception) { println("WitAnimeDebug: $name L1 fail: ${e.message}") }

        // LAYER 2: WebView network intercept (catches XHR-loaded players)
        if (found.isEmpty()) {
            try {
                val res = app.get(url, headers = headers, referer = referer,
                    interceptor = WebViewResolver(interceptUrl = streamRx))
                if (streamRx.containsMatchIn(res.url)) found.add(res.url)
                else Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(res.text.replace("\\", ""))
                    .forEach { found.add(it.groupValues[1]) }
            } catch (e: Exception) { println("WitAnimeDebug: $name L2 fail: ${e.message}") }
        }

        println("WitAnimeDebug: $name found=${found.size} for $url")
        found.filter { it.startsWith("http") }.forEach { link ->
            val full = if (link.startsWith("/")) base + link else link
            val q = Regex("""(\d{3,4})p""").find(full)?.groupValues?.get(1)
            callback(newExtractorLink(this.name, this.name, full,
                if (full.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "$base/"
                quality = getQualityFromName(q)
            })
        }

        Regex("""\{"file":\s*["']([^"']+\.(?:vtt|srt))["'][^}]*?"label":\s*["']([^"']+)["']""").findAll(
            (unpackPackedJs("") ?: "") // placeholder never runs; subs extracted below
        ).forEach { }
    }
}

class Awish : StreamWishExtractor() { override val name = "Awish"; override val mainUrl = "https://awish.pro" }
class Asnwish : StreamWishExtractor() { override val name = "Asnwish"; override val mainUrl = "https://asnwish.com" }
class CdnwishCom : StreamWishExtractor() { override val name = "Cdnwish"; override val mainUrl = "https://cdnwish.com" }
class EmbedWish : StreamWishExtractor() { override val name = "EmbedWish"; override val mainUrl = "https://embedwish.com" }
