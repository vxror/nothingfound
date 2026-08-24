package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class DotPlayExtractor : ExtractorApi() {
    override val name = "DotPlay"
    override val mainUrl = "https://dotplay.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfter("/embed/").substringBefore("/").substringBefore("?")
            if (code.isBlank()) { println("WitAnimeDebug: DotPlay no code"); return }

            val apiUrl = "$mainUrl/api.php?code=$code"
            val apiHeaders = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "application/json",
                "Referer" to url
            )
            val response = app.get(apiUrl, headers = apiHeaders, referer = url)
            val jsonStr = response.text
            println("WitAnimeDebug: DotPlay api response=${jsonStr.take(200)}")

            val json = try { JSONObject(jsonStr) } catch (_: Exception) { null }
            val videoUrl = json?.let { j ->
                when {
                    j.has("url") -> j.optString("url")
                    j.has("link") -> j.optString("link")
                    j.has("file") -> j.optString("file")
                    j.has("src") -> j.optString("src")
                    j.has("video") -> j.optString("video")
                    j.has("source") -> j.optString("source")
                    j.has("download") -> j.optString("download")
                    j.has("direct") -> j.optString("direct")
                    j.has("play") -> j.optString("play")
                    j.has("stream") -> j.optString("stream")
                    else -> ""
                }
            } ?: ""

            if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                println("WitAnimeDebug: DotPlay video -> ${videoUrl.take(100)}")
                val cleanUrl = videoUrl.trimEnd('#')
                callback(newExtractorLink(name, name, cleanUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    quality = detectQuality(cleanUrl)
                })
            } else {
                println("WitAnimeDebug: DotPlay: no url in API, scanning embed page")
                val embedHtml = app.get(url, headers = apiHeaders).text
                Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(embedHtml).forEach { m ->
                    callback(newExtractorLink(name, name, m.groupValues[1], ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                    })
                }
                Regex("""https?://(?:www\.dropbox\.com|dl\.dropboxusercontent\.com|soraplay\.[a-z]+)/[^\s"'<>]+""").findAll(embedHtml).forEach { m ->
                    val link = m.groupValues[1].trimEnd('#', '"', '\'')
                    if (link.isNotBlank()) {
                        callback(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            quality = detectQuality(link)
                        })
                    }
                }
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: DotPlay error: ${e.message}")
        }
    }

    private fun detectQuality(url: String): Int = when {
        url.contains("1080") || url.contains("FHD", true) -> Qualities.P1080.value
        url.contains("720") || url.contains("HD", true) -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
