package com.witanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class MailruExtractor : ExtractorApi() {
    override val name = "MailRu"
    override val mainUrl = "https://my.mail.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: url
        val vidId = url.substringAfter("video/embed/").substringBefore("?").trim()
        if (vidId.isBlank() || vidId == url) return
        val videoReq = try {
            app.get("$mainUrl/+/video/meta/$vidId", referer = extRef, headers = mapOf("User-Agent" to EXTRACTOR_UA) + CHROME_HINTS)
        } catch (e: Exception) { WitaLog.d("MailRu meta fail: ${e.message}"); return }
        val videoKey = videoReq.cookies["video_key"]?.toString().orEmpty()
        val videoData = AppUtils.tryParseJson<MailRuData>(videoReq.text) ?: return
        for (video in videoData.videos ?: emptyList()) {
            var videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            if (videoKey.isNotBlank())
                videoUrl = if (videoUrl.contains("?")) "$videoUrl&video_key=$videoKey" else "$videoUrl?video_key=$videoKey"
            try {
                callback(newExtractorLink(name, "$name ${video.key}", videoUrl,
                    if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = extRef
                    this.quality = getQualityFromName(video.key)
                })
            } catch (e: Exception) { WitaLog.d("MailRu emit fail: ${e.message}") }
        }
    }

    data class MailRuData(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("videos") val videos: List<MailRuVideoData>? = null
    )
    data class MailRuVideoData(
        @JsonProperty("url") val url: String,
        @JsonProperty("key") val key: String
    )
}
