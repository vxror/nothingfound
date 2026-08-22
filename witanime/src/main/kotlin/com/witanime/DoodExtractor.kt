package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlin.random.Random

open class DoodExtractor : ExtractorApi() {
    override val name = "Dood"
    override val mainUrl = "https://dood.watch"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embed = url.replace("/d/", "/e/").replace("/w/", "/e/")
            val headers = mapOf("User-Agent" to EXTRACTOR_UA)
            val response = app.get(embed, referer = referer, headers = headers)
            val content = response.text
            if (!content.contains("/pass_md5/")) { println("WitAnimeDebug: Dood no pass_md5: $url"); return }

            val doodHost = hostOf(response.url)          // self-resolves the REAL dood domain
            val md5Path = Regex("""/pass_md5/[^'"\s]*""").find(content)?.value ?: return
            val token = md5Path.substringAfterLast("/")   // token from the PATH
            val pass = app.get(doodHost + md5Path, referer = response.url, headers = headers).text
            val rnd = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(62)] }.joinToString("")
            val link = "$pass$rnd?token=$token"           // ?token= is REQUIRED

            val q = Regex("""(\d{3,4})p""").find(content.substringAfter("<title>").substringBefore("</title>"))?.groupValues?.get(1)
            println("WitAnimeDebug: Dood resolved via $doodHost (q=$q)")
            callback(newExtractorLink(this.name, this.name, link, ExtractorLinkType.VIDEO) {
                this.referer = "$doodHost/"
                quality = getQualityFromName(q)
            })
        } catch (e: Exception) { println("WitAnimeDebug: Dood error: ${e.message}") }
    }
}

class DoodToExtractor : DoodExtractor() { override val name = "DoodTo"; override val mainUrl = "https://dood.to" }
class DoodLaExtractor : DoodExtractor() { override val name = "DoodLa"; override val mainUrl = "https://dood.la" }
class DoodYtExtractor : DoodExtractor() { override val name = "DoodYt"; override val mainUrl = "https://dood.yt" }
class DoodWsExtractor : DoodExtractor() { override val name = "DoodWs"; override val mainUrl = "https://dood.ws" }
class Ds2PlayExtractor : DoodExtractor() { override val name = "Ds2Play"; override val mainUrl = "https://ds2play.com" }
class D000dExtractor : DoodExtractor() { override val name = "D000d"; override val mainUrl = "https://d000d.com" }
