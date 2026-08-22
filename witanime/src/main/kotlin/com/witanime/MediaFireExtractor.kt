package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class MediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA)).text
            val link = Regex("""href="(https?://download[^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex(""""(https?://download\d+\.mediafire\.com/[^"]+)"""").find(html)?.groupValues?.get(1)
                ?: return
            callback(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
            })
        } catch (e: Exception) { println("WitAnimeDebug: MediaFire error: ${e.message}") }
    }
}
