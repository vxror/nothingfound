package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class SoraplayExtractor : ExtractorApi() {
    override val name = "Soraplay"
    override val mainUrl = "https://soraplay.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("User-Agent" to EXTRACTOR_UA)
            val html = app.get(url, headers = headers, referer = referer).text
            println("WitAnimeDebug: Sora len=${html.length}")

            var found = false

            // 1) Direct mp4/m3u8 links
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(html).forEach { m ->
                val link = m.groupValues[1]
                println("WitAnimeDebug: Sora direct -> ${link.take(90)}")
                callback(newExtractorLink(name, name, link,
                    if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    quality = detectQuality(link)
                })
                found = true
            }

            // 2) Relative paths starting with /
            if (!found) {
                Regex("""["'](/[^"']*\.(?:mp4|m3u8)[^"']*)["']""").findAll(html).forEach { m ->
                    val link = m.groupValues[1]
                    callback(newExtractorLink(name, name, "$mainUrl$link", ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        quality = detectQuality(link)
                    })
                    found = true
                }
            }

            // 3) WebView intercept — JS constructs the URL
            if (!found) {
                println("WitAnimeDebug: Sora: trying WebView intercept")
                try {
                    val resolver = WebViewResolver(
                        interceptUrl = Regex("""\.mp4|\.m3u8"""),
                        additionalUrls = listOf(Regex("""\.mp4|\.m3u8""")),
                        useOkhttp = false,
                        timeout = 15_000L
                    )
                    val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                    if (intercepted.isNotEmpty() && (intercepted.contains(".mp4") || intercepted.contains(".m3u8"))) {
                        println("WitAnimeDebug: Sora WV -> ${intercepted.take(90)}")
                        callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            quality = detectQuality(intercepted)
                        })
                        found = true
                    }
                } catch (_: Exception) {}
            }

            if (!found) println("WitAnimeDebug: Sora: nothing found")
        } catch (e: Exception) {
            println("WitAnimeDebug: Sora error: ${e.message}")
        }
    }

    private fun detectQuality(url: String): Int = when {
        url.contains("1080") || url.contains("FHD", true) || url.contains("source") -> Qualities.P1080.value
        url.contains("720") || url.contains("HD", true) -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
