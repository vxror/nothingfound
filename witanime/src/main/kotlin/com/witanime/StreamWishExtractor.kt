package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8

open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer)
        val unpacked = getAndUnpack(res.text)   // handles the p,a,c,k,e,d eval

        // video sources
        Regex("""file\s*:\s*"(https?://[^"]+)"""").findAll(unpacked)
            .map { it.groupValues[1] }.distinct()
            .forEach { link ->
                if (link.contains(".m3u8", true)) {
                    callback(
                        newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            quality = getQualityFromName(Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1))
                        }
                    )
                }
            }

        // subtitles
        Regex("""file\s*:\s*"([^"]+\.(?:vtt|srt))"[^}]*?label\s*:\s*"([^"]*)"""")
            .findAll(unpacked).forEach {
                subtitleCallback(SubtitleFile(it.groupValues[2).ifBlank { "Sub" }, it.groupValues[1]))
            }
    }
}

class Awish : StreamWishExtractor() {
    override val name = "Awish"
    override val mainUrl = "https://awish.pro"
}

class Asnwish : StreamWishExtractor() {
    override val name = "Asnwish"
    override val mainUrl = "https://asnwish.com"
}

class CdnwishCom : StreamWishExtractor() {
    override val name = "Cdnwish"
    override val mainUrl = "https://cdnwish.com"
}
