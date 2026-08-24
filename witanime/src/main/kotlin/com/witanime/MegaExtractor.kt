package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class MegaExtractor : ExtractorApi() {
    override val name = "Mega"
    override val mainUrl = "https://mega.nz"
    override val requiresReferer = false

    /** set by the yonaplay router so HD/FHD links are distinguishable */
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val local = MegaProxy.resolve(url) ?: run {
            println("WitAnimeDebug: Mega resolve failed for $url"); return
        }
        println("WitAnimeDebug: Mega proxied OK${linkLabel?.let { " ($it)" } ?: ""}")
        callback(newExtractorLink(name, "Mega (Proxy)" + (linkLabel?.let { " $it" } ?: ""), local, ExtractorLinkType.VIDEO) {
            quality = labelQuality(linkLabel)
        })
    }
}
