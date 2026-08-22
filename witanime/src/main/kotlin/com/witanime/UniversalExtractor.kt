package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class UniversalExtractor : ExtractorApi() {
    override val name = "UniversalSniffer"
    override val mainUrl = "https://universal.sniffer"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val found = LinkedHashSet<String>()
        val headers = mapOf("User-Agent" to EXTRACTOR_UA)
        val streamRx = Regex("""\.(m3u8|mp4)""", RegexOption.IGNORE_CASE)

        // L1: fetch + unpack + regex
        try {
            val raw = app.get(url, referer = referer, headers = headers).text
            val text = "$raw\n${(unpackPackedJs(raw) ?: "").replace("\\", "")}"
            Regex("""(?:file|source|src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(text).forEach { found.add(it.groupValues[1]) }
            Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""").findAll(text)
                .forEach { found.add(it.groupValues[1]) }
        } catch (e: Exception) { println("WitAnimeDebug: Universal L1 fail: ${e.message}") }

        // L2: single WebView network intercept — NO recursion storms
        if (found.isEmpty()) {
            try {
                val res = app.get(url, referer = referer, headers = headers,
                    interceptor = WebViewResolver(interceptUrl = streamRx))
                if (streamRx.containsMatchIn(res.url)) found.add(res.url)
            } catch (e: Exception) { println("WitAnimeDebug: Universal L2 fail: ${e.message}") }
        }

        println("WitAnimeDebug: Universal found=${found.size} for $url")
        found.filter { it.startsWith("http") }.forEach { link ->
            val q = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)
            callback(newExtractorLink(name, "Direct", link,
                if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = hostOf(url)
                quality = getQualityFromName(q)
            })
        }
    }
}
