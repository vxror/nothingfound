package com.vxrux.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLDecoder

open class CdaBase : ExtractorApi() {
    override val name = "Cda"
    override val mainUrl = "https://ebd.cda.pl"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val mediaId = url.split("/").last().split("?").first()
            val doc = app.get(
                "https://ebd.cda.pl/647x500/$mediaId",
                headers = mapOf(
                    "Referer" to "https://ebd.cda.pl/647x500/$mediaId",
                    "User-Agent" to USER_AGENT,
                    "Cookie" to "cda.player=html5",
                )
            ).document
            val dataRaw = doc.selectFirst("[player_data]")?.attr("player_data") ?: return
            val playerData = tryParseJson<PlayerData>(dataRaw) ?: return
            val fileUrl = getFile(playerData.video.file)

            callback.invoke(
                newExtractorLink(name, name, fileUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ebd.cda.pl/647x500/$mediaId"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {}
    }

    private fun rot13(a: String): String = a.map {
        when {
            it in 'A'..'M' || it in 'a'..'m' -> it + 13
            it in 'N'..'Z' || it in 'n'..'z' -> it - 13
            else -> it
        }
    }.joinToString("")

    private fun cdaUggc(a: String): String {
        val decoded = rot13(a)
        return if (decoded.endsWith("adc.mp4")) decoded.replace("adc.mp4", ".mp4") else decoded
    }

    private fun cdaDecrypt(b: String): String {
        var a = b.replace("_XDDD", "").replace("_CDA", "").replace("_ADC", "")
            .replace("_CXD", "").replace("_QWE", "").replace("_Q5", "").replace("_IKSDE", "")
        a = try { URLDecoder.decode(a, "UTF-8") } catch (_: Exception) { a }
        a = a.map { char ->
            if (char.code in 33..126) (33 + (char.code + 14) % 94).toChar().toString() else char.toString()
        }.joinToString("")
        a = a.replace(".cda.mp4", "").replace(".2cda.pl", ".cda.pl").replace(".3cda.pl", ".cda.pl")
        return if (a.contains("/upstream")) "https://" + a.replace("/upstream", ".mp4/upstream") else "https://${a}.mp4"
    }

    private fun getFile(a: String) = when {
        a.startsWith("uggc") -> cdaUggc(a)
        !a.startsWith("http") -> cdaDecrypt(a)
        else -> a
    }

    data class VideoPlayerData(
        @JsonProperty("file") val file: String,
        @JsonProperty("qualities") val qualities: Map<String, String> = mapOf(),
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("ts") val ts: Int? = null,
        @JsonProperty("hash2") val hash2: String? = null,
    )

    data class PlayerData(
        @JsonProperty("video") val video: VideoPlayerData,
    )
}

class CdaExtractor : CdaBase()
