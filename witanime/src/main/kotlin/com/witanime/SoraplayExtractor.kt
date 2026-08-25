package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class SoraplayExtractor : ExtractorApi() {
    override val name = "Soraplay"
    override val mainUrl = "https://soraplay.xyz"
    override val requiresReferer = false

    /** quality label (HD/FHD) from the parent server, set by routeLink */
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("User-Agent" to EXTRACTOR_UA)
            val html = app.get(url, headers = headers, referer = referer).text
            println("WitAnimeDebug: Sora len=${html.length}")

            var found = false

            // 1) Direct mp4/m3u8 links — quality from URL (480p/720p/1080p in filename)
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(html).forEach { m ->
                val link = m.groupValues[1]
                println("WitAnimeDebug: Sora direct -> ${link.take(90)}")
                emitSora(link, callback)
                found = true
            }

            // 2) Relative paths
            if (!found) {
                Regex("""["'](/[^"']*\.(?:mp4|m3u8)[^"']*)["']""").findAll(html).forEach { m ->
                    val link = m.groupValues[1]
                    emitSora("$mainUrl$link", callback)
                    found = true
                }
            }

            // 3) WebView intercept
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
                        emitSora(intercepted, callback)
                        found = true
                    }
                } catch (_: Exception) {}
            }

            if (!found) println("WitAnimeDebug: Sora: nothing found")
        } catch (e: Exception) {
            println("WitAnimeDebug: Sora error: ${e.message}")
        }
    }

    private suspend fun emitSora(link: String, callback: (ExtractorLink) -> Unit) {
        val cleanUrl = link.trimEnd('#')
        val qName = qualityName(cleanUrl, linkLabel)
        callback(newExtractorLink(name, name + (qName?.let { " $it" } ?: ""), cleanUrl,
            if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.referer = mainUrl
            quality = bestQuality(cleanUrl, linkLabel)
        })
    }
}
