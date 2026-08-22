package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class FourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA))
            val html = res.text
            val doc = res.document
            val link = doc.selectFirst("video source[src]")?.attr("src")?.takeIf { it.startsWith("http") }
                ?: Regex("""downloadUrl\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex(""""(https?://[^"]+/(?:download|videoplay)[^"]*)"""").find(html)?.groupValues?.get(1)
                ?: return
            callback(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
            })
        } catch (e: Exception) { println("WitAnimeDebug: 4Shared error: ${e.message}") }
    }
}
