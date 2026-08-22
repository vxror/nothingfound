package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class VideasFrExtractor : ExtractorApi() {
    override val name = "VideasFr"
    override val mainUrl = "https://videas.fr"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = try {
                app.get(url, referer = referer, interceptor = WebViewResolver(Regex(".*"))).text
            } catch (e: Exception) {
                app.get(url, referer = referer).text
            }

            val m3u8Regex = Regex("""(https?://[^\s"'<>]*videas\.fr[^\s"'<>]*\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            val genericM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            
            val links = mutableSetOf<String>()
            m3u8Regex.findAll(html).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
            if (links.isEmpty()) {
                genericM3u8.findAll(html).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
            }

            links.forEach { link ->
                val quality = when {
                    link.contains("1080", true) || link.contains("hlsv1", true) -> Qualities.P1080.value
                    link.contains("720", true) -> Qualities.P720.value
                    link.contains("480", true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                callback(newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = quality
                })
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: VideasFr error: ${e.message}")
        }
    }
}
