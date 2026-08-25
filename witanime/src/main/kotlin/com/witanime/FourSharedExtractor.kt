package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class FourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    /** quality label from the parent server (HD/FHD), set by routeLink */
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("User-Agent" to EXTRACTOR_UA)
            val res = app.get(url, referer = referer, headers = headers)
            val html = res.text
            val doc = res.document
            println("WitAnimeDebug: 4Shared len=${html.length} url=${res.url.take(80)}")

            val link = doc.selectFirst("video source[src]")?.attr("src")?.takeIf { it.startsWith("http") }
                ?: Regex("""downloadUrl\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""href=["']([^"']*download[^"']*)["']""").find(html)?.groupValues?.get(1)?.let { fixUrl(it) }
                ?: Regex(""""(https?://[^"]+/(?:download|videoplay)[^"]*)"""").find(html)?.groupValues?.get(1)
                ?: null

            if (link == null) {
                println("WitAnimeDebug: 4Shared: no link found, trying download page fallback")
                tryDownloadPageFallback(url, linkLabel, callback)
                return
            }

            val isPlayable = try {
                app.get(link, headers = headers + mapOf("Range" to "bytes=0-0"), referer = "$mainUrl/").isSuccessful
            } catch (_: Exception) { false }

            if (!isPlayable) {
                println("WitAnimeDebug: 4Shared: link not playable, trying fallback")
                tryDownloadPageFallback(url, linkLabel, callback)
                return
            }

            val qName = qualityName(link, linkLabel)
            callback(newExtractorLink(name, name + (qName?.let { " $it" } ?: ""), link, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                quality = bestQuality(link, linkLabel)
            })
        } catch (e: Exception) {
            println("WitAnimeDebug: 4Shared error: ${e.message}")
        }
    }

    /** Fallback for large/FHD files that show a download page instead of an embed */
    private suspend fun tryDownloadPageFallback(
        originalUrl: String,
        qLabel: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val dlPageUrl = originalUrl.replace("/embed/", "/file/")
            val headers = mapOf("User-Agent" to EXTRACTOR_UA)
            val dlHtml = app.get(dlPageUrl, headers = headers, referer = "$mainUrl/").text

            val dlLink = Regex("""href=["'](https?://[^"']*download[^"']*)["']""").find(dlHtml)?.groupValues?.get(1)
                ?: Regex(""""(https?://dc\d*\.\d+\.4shared\.com/[^"]+)"""").find(dlHtml)?.groupValues?.get(1)
                ?: return

            val isPlayable = try {
                app.get(dlLink, headers = headers + mapOf("Range" to "bytes=0-0"), referer = dlPageUrl).isSuccessful
            } catch (_: Exception) { false }

            if (isPlayable) {
                val qName = qualityName(dlLink, qLabel)
                callback(newExtractorLink(name, name + (qName?.let { " $it" } ?: ""), dlLink, ExtractorLinkType.VIDEO) {
                    this.referer = dlPageUrl
                    quality = bestQuality(dlLink, qLabel)
                })
            }
        } catch (_: Exception) {}
    }
}
