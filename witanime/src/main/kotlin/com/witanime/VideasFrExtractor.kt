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
            // Fetch the embed page (e.g., https://app.videas.fr/embed/media/...)
            // Using WebViewResolver as a fallback in case they use Cloudflare or JS rendering
            val html = try {
                app.get(url, referer = referer, interceptor = WebViewResolver(Regex("videas\\.fr"))).text
            } catch (e: Exception) {
                app.get(url, referer = referer).text
            }

            val links = mutableSetOf<String>()
            
            // 1. Hunt specifically for the cdn.videas.fr m3u8 playlist
            Regex("""(https?://cdn\.videas\.fr/[^"'\s]+\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE)
                .findAll(html).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
            
            // 2. Fallback: Hunt for any generic m3u8 on the page
            if (links.isEmpty()) {
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""", RegexOption.IGNORE_CASE)
                    .findAll(html).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
            }

            // 3. Fallback: Check JS player configs (file: "...", source: "...", src: "...")
            if (links.isEmpty()) {
                Regex("""(?:file|source|src|url)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html).forEach { links.add(it.groupValues[1].replace("\\/", "/")) }
            }

            links.forEach { link ->
                val quality = when {
                    link.contains("1080", true) || link.contains("hlsv1", true) -> Qualities.P1080.value
                    link.contains("720", true) -> Qualities.P720.value
                    link.contains("480", true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = "VideasFr",
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: VideasFr error: ${e.message}")
        }
    }
}
