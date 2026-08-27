package com.vxrux.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

open class BloggerBase : ExtractorApi() {
    override val name = "Blogger"
    override val mainUrl = "https://www.blogger.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val document = app.get(url).document
            document.select("script").forEach { script ->
                if (script.data().contains("\"streams\":[")) {
                    val data = script.data().substringAfter("\"streams\":[").substringBefore("]")
                    tryParseJson<List<ResponseSource>>("[$data]")?.forEach {
                        callback.invoke(
                            newExtractorLink(
                                name, name, it.playUrl,
                                if (it.playUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.youtube.com/"
                                this.quality = when (it.formatId) {
                                    18 -> 360
                                    22 -> 720
                                    else -> Qualities.Unknown.value
                                }
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    data class ResponseSource(
        @JsonProperty("play_url") val playUrl: String,
        @JsonProperty("format_id") val formatId: Int,
    )
}

class BloggerExtractor : BloggerBase()
