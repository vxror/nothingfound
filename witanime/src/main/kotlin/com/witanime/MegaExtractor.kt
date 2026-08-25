package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class MegaExtractor : ExtractorApi() {
    override val name = "Mega"
    override val mainUrl = "https://mega.nz"
    override val requiresReferer = false

    /** quality label from the parent server (HD/FHD), set by routeLink */
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("WitAnimeDebug: Mega START url=$url label=$linkLabel")

            val local = MegaProxy.resolve(url) ?: run {
                println("WitAnimeDebug: Mega resolve failed for $url")
                return
            }

            println("WitAnimeDebug: Mega proxied OK -> ${local.take(60)}")
            val displayName = "Mega (Proxy)" + (linkLabel?.let { " $it" } ?: "")
            callback(newExtractorLink(name, displayName, local, ExtractorLinkType.VIDEO) {
                quality = labelQuality(linkLabel)
            })
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega error: ${e.message}")
        }
    }
}
