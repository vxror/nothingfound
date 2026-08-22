package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class FourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer).text
            
            // Try to find direct download link
            val link = Regex("""downloadUrl\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-url=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=["']([^"']*download[^"']*)["']""").find(html)?.groupValues?.get(1)
                ?: return
            
            val finalLink = if (link.startsWith("//")) "https:$link" else link
            
            callback(
                newExtractorLink(name, name, finalLink, ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            println("WitAnimeDebug: 4Shared error: ${e.message}")
        }
    }
}
