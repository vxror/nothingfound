package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class VideasFrExtractor : ExtractorApi() {
    override val name = "VideasFr"
    override val mainUrl = "https://app.videas.fr"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to EXTRACTOR_UA, "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.5")
        val found = LinkedHashSet<String>()
        val m3u8Rx = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")

        // L0: link is ALREADY a direct m3u8 (cdn.videas.fr/...playlist.m3u8 from download list)
        if (url.contains(".m3u8")) { emit(url, url, callback); return }

        // L1: embed page app.videas.fr/embed/media/{uuid} — find stream in page/JSON
        try {
            val raw = app.get(url, headers = headers, referer = referer).text
            val cleaned = raw.replace("\\/", "/").replace("\\\"", "\"")
            m3u8Rx.findAll(cleaned).forEach { found.add(it.value) }
            Regex(""""(?:src|file|url|source)"\s*:\s*"([^"]+\.m3u8[^"]*)"""").findAll(cleaned)
                .forEach { found.add(it.groupValues[1]) }
        } catch (e: Exception) { println("WitAnimeDebug: VideasFr L1 fail: ${e.message}") }

        // L2: player loads the playlist via XHR → WebView network intercept
        if (found.isEmpty()) {
            try {
                val res = app.get(url, headers = headers, referer = referer,
                    interceptor = WebViewResolver(interceptUrl = Regex("""\.m3u8""", RegexOption.IGNORE_CASE)))
                if (res.url.contains(".m3u8")) found.add(res.url)
            } catch (e: Exception) { println("WitAnimeDebug: VideasFr L2 fail: ${e.message}") }
        }

        println("WitAnimeDebug: VideasFr found=${found.size} for $url")
        found.filter { it.startsWith("http") }.forEach { emit(url, it, callback) }
    }

    private fun emit(pageUrl: String, link: String, callback: (ExtractorLink) -> Unit) {
        // /hlsv1/ = adaptive master playlist → Unknown, player picks quality
        val q = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)
        callback(
            newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                this.referer = if (linkHost(link).contains("cdn")) "https://app.videas.fr/" else hostOf(pageUrl)
                quality = getQualityFromName(q)
            }
        )
    }
}
