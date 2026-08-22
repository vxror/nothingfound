package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class DoodStreamExtractor : ExtractorApi() {
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embed = url.replace("/d/", "/e/").replace("/w/", "/e/")
            val html = app.get(embed, referer = referer).text
            
            val md5 = Regex("""'/pass_md5/([^']+)'""").find(html)?.groupValues?.get(1) ?: return
            val base = java.net.URI(embed).let { "${it.scheme}://${it.host}" }
            val pass = app.get("$base/pass_md5/$md5", referer = embed).text
            
            val token = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[kotlin.random.Random.nextInt(62)] }.joinToString("")
            val link = "$pass$token"
            
            callback(
                newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            println("WitAnimeDebug: DoodStream error: ${e.message}")
        }
    }
}

class DoodStreamComExtractor : DoodStreamExtractor() {
    override val name = "DoodStreamCom"
    override val mainUrl = "https://dood.cm"
}

class DoodStreamToExtractor : DoodStreamExtractor() {
    override val name = "DoodStreamTo"
    override val mainUrl = "https://dood.to"
}

class DoodStreamWatchExtractor : DoodStreamExtractor() {
    override val name = "DoodStreamWatch"
    override val mainUrl = "https://dood.watch"
}
