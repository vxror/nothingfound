package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class OkRuExtractor : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, referer = referer, headers = mapOf("User-Agent" to EXTRACTOR_UA)).text

            // primary: data-options JSON → movie → videos[]
            Regex("""data-options="([^"]+)"""").findAll(html).forEach { m ->
                try {
                    val opt = JSONObject(m.groupValues[1].replace("&quot;", "\""))
                    if (!opt.has("movie")) return@forEach
                    val movie = opt.getJSONObject("movie")
                    if (!movie.has("videos")) return@forEach
                    val vids = movie.getJSONArray("videos")
                    for (i in 0 until vids.length()) {
                        val v = vids.optJSONObject(i) ?: continue
                        val vUrl = v.optString("url")
                        if (vUrl.isBlank()) continue
                        val full = if (vUrl.startsWith("//")) "https:$vUrl" else vUrl
                        val vName = v.optString("name")
                        callback(newExtractorLink(name, "$name $vName", full,
                            if (full.contains(".m3u8") || full.contains("type/0")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            quality = getQualityFromName(vName)
                        })
                    }
                } catch (_: Exception) {}
            }

            // fallback: raw hls/mp4 in page
            Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)"""").findAll(html).forEach {
                val u = it.groupValues[1].replace("\\/", "/")
                if (u.startsWith("http")) callback(newExtractorLink(name, name, u, ExtractorLinkType.M3U8) { this.referer = mainUrl })
            }
            Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""").findAll(html).forEach {
                callback(newExtractorLink(name, name, it.groupValues[1], ExtractorLinkType.VIDEO) { this.referer = mainUrl })
            }
        } catch (e: Exception) { println("WitAnimeDebug: OkRu error: ${e.message}") }
    }
}
