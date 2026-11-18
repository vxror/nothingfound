package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class SoraplayExtractor : ExtractorApi() {
    override val name = "Soraplay"
    override val mainUrl = "https://soraplay.xyz"
    override val requiresReferer = false
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("User-Agent" to EXTRACTOR_UA) + CHROME_HINTS
            val html = app.get(url, headers = headers, referer = referer).text
            var found = false
            val seen = HashSet<String>()
            fun acceptable(link: String) =
                !link.contains("advert", true) && !link.contains("/ads/", true) && !link.contains("preview", true) && seen.add(link)

            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(html)
                .map { it.groupValues[1] }.filter { acceptable(it) }.take(4).forEach { emitSora(it, callback); found = true }
            if (!found) {
                Regex("""["'](/[^"']*\.(?:mp4|m3u8)[^"']*)["']""").findAll(html)
                    .map { "$mainUrl${it.groupValues[1]}" }.filter { acceptable(it) }.take(4).forEach { emitSora(it, callback); found = true }
            }
            if (!found) {
                try {
                    val resolver = WebViewResolver(
                        interceptUrl = Regex("""\.mp4|\.m3u8"""),
                        additionalUrls = listOf(Regex("""\.mp4|\.m3u8""")),
                        useOkhttp = false, timeout = 15_000L
                    )
                    val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                    if (intercepted.contains(".mp4") || intercepted.contains(".m3u8")) { emitSora(intercepted, callback); found = true }
                } catch (_: Exception) {}
            }
            if (!found) WitaLog.d("Sora: nothing found")
        } catch (e: Exception) { WitaLog.e("Sora error: ${e.message}") }
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
