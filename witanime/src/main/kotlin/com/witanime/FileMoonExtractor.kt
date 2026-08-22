package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class FileMoonExtractor : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA))
            val base = hostOf(res.url)
            val js = (unpackPackedJs(res.text) ?: res.text).replace("\\", "")
            Regex("""(?:sources|file)\s*[:=]\s*[\[{]*\s*"?(?:file"?\s*:\s*)?["']?(https?://[^"',\]} ]+)""")
                .findAll(js).map { it.groupValues[1] }.distinct()
                .filter { it.contains(".m3u8") || it.contains(".mp4") }
                .forEach { link ->
                    val q = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)
                    callback(newExtractorLink(name, name, link,
                        if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = "$base/"
                        quality = getQualityFromName(q)
                    })
                }
        } catch (e: Exception) { println("WitAnimeDebug: FileMoon error: ${e.message}") }
    }
}
