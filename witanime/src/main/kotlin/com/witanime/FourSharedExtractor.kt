package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class FourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = Regex("""4shared\.com/(?:web/embed/file|embed|video|file|s)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            val pageUrl = if (fileId != null) "$mainUrl/web/embed/file/$fileId" else url
            val html = sessionGet(pageUrl, referer = "$mainUrl/")
                ?: app.get(pageUrl, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA)).text
            val doc = Jsoup.parse(html, pageUrl)
            val link = doc.selectFirst("video source[src], video[src]")?.attr("src")?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("//")) "https:$it" else it }?.takeIf { it.startsWith("http") }
                ?: Regex("""https?://dc\d+\.4shared\.com/[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)?.value
                ?: Regex("""downloadUrl\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex(""""(https?://[^"]+/(?:download|videoplay)[^"]*)"""").find(html)?.groupValues?.get(1)
            if (link == null) { tryDownloadPageFallback(pageUrl, callback); return }
            if (!isPlayable(link, "$mainUrl/")) { tryDownloadPageFallback(pageUrl, callback); return }
            emitLink(link, "$mainUrl/", callback)
        } catch (e: Exception) { WitaLog.e("4Shared error: ${e.message}") }
    }

    private suspend fun emitLink(link: String, ref: String, callback: (ExtractorLink) -> Unit) {
        val qName = qualityName(link, linkLabel)
        callback(newExtractorLink(name, name + (qName?.let { " $it" } ?: ""), link, ExtractorLinkType.VIDEO) {
            this.referer = ref
            quality = bestQuality(link, linkLabel)
        })
    }

    private suspend fun tryDownloadPageFallback(originalUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val dlPageUrl = originalUrl.replace("/embed/", "/file/").replace("/web/embed/file/", "/file/")
            val dlHtml = sessionGet(dlPageUrl, referer = "$mainUrl/") ?: return
            val dlLink = Regex("""href=["'](https?://[^"']*download[^"']*)["']""").find(dlHtml)?.groupValues?.get(1)
                ?: Regex(""""(https?://dc\d*\.[^"]*4shared\.com/[^"]+)"""").find(dlHtml)?.groupValues?.get(1)
                ?: return
            if (isPlayable(dlLink, dlPageUrl)) emitLink(dlLink, dlPageUrl, callback)
        } catch (_: Exception) {}
    }
}
