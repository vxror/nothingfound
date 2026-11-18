package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class FileMoonExtractor : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA) + CHROME_HINTS)
            val base = hostOf(res.url)
            val unpacked = runCatching { getAndUnpack(res.text) }.getOrNull()?.ifBlank { null } ?: unpackPackedJs(res.text) ?: ""
            val js = "${res.text}\n$unpacked".replace("\\/", "/").replace("\\", "")
            val links = Regex("""["']?(?:file|src)["']?\s*[:=]\s*["'](https?://[^"']+)["']""")
                .findAll(js).map { it.groupValues[1] }
                .plus(Regex("""(https?://[^\s"',\]}<>]+\.(?:m3u8|mp4)[^\s"',\]}<>]*)""").findAll(js).map { it.groupValues[1] })
                .distinct().filter { it.contains(".m3u8") || it.contains(".mp4") }.take(3).toList()
            links.forEach { link ->
                callback(newExtractorLink(name, name, link,
                    if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$base/"
                    quality = urlQuality(link)
                })
            }
            if (links.isEmpty()) {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8|\.mp4"""),
                    additionalUrls = listOf(Regex("""\.m3u8|\.mp4""")),
                    useOkhttp = false, timeout = 12_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.contains(".m3u8") || intercepted.contains(".mp4"))
                    callback(newExtractorLink(name, name, intercepted,
                        if (intercepted.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { this.referer = "$base/" })
            }
        } catch (e: Exception) { WitaLog.e("FileMoon error: ${e.message}") }
    }
}
