package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class OkRuExtractor : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    private val playbackHeaders = mapOf("User-Agent" to EXTRACTOR_UA, "Referer" to "https://ok.ru/", "Origin" to "https://ok.ru")
    private val interceptRx = Regex("""(?<!iv\.)okcdn\.ru|videoPlayerCdn|\.m3u8""")

    private suspend fun emitCapped(videos: JSONArray, callback: (ExtractorLink) -> Unit): Boolean {
        val candidates = ArrayList<Triple<String, String, Int>>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val u = v.optString("url")
            if (u.isBlank()) continue
            val full = if (u.startsWith("//")) "https:$u" else u
            if (!full.startsWith("http")) continue
            val vName = v.optString("name")
            candidates.add(Triple("$name $vName", full, okruQuality(vName)))
        }
        candidates.sortByDescending { it.third }
        val picked = candidates.distinctBy { it.second }.take(3)
        picked.forEach { (nm, full, q) ->
            callback(newExtractorLink(name, nm, full,
                if (full.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "https://ok.ru/"
                this.headers = playbackHeaders
                quality = q
            })
        }
        return picked.isNotEmpty()
    }

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoId = url.substringAfter("/videoembed/").substringAfter("/video/")
                .substringBefore("/").substringBefore("?").trim()
            if (videoId.isBlank()) { WitaLog.d("OkRu no ID"); return }
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-Fetch-Dest" to "iframe", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "cross-site",
            )
            var emitted = false
            val warmHtml = withTimeoutOrNull(6_000L) {
                sessionGet(url, referer = referer ?: "https://ok.ru/", headers = headers)
            }.orEmpty()

            try {
                val apiText = withTimeoutOrNull(8_000L) {
                    sessionGet("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                        referer = url, headers = headers + mapOf("Accept" to "application/json"))
                }.orEmpty()
                if (apiText.length > 100) {
                    val json = try { JSONObject(apiText) } catch (_: Exception) { null }
                    if (json != null) {
                        json.optJSONArray("videos")?.let { if (it.length() > 0) emitted = emitCapped(it, callback) }
                        if (!emitted) {
                            val hls = json.optString("hlsManifestUrl")
                            if (hls.startsWith("http"))
                                M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = playbackHeaders).forEach { callback(it); emitted = true }
                        }
                        if (!emitted) json.optJSONObject("movie")?.optJSONArray("videos")?.let { emitted = emitCapped(it, callback) }
                    }
                }
                if (emitted) { WitaLog.d("OkRu API SUCCESS"); return }
            } catch (e: Exception) { WitaLog.d("OkRu API fail: ${e.message}") }

            try {
                if (warmHtml.length > 500 && emitFromHtml(warmHtml, callback)) { WitaLog.d("OkRu HTML SUCCESS"); return }
            } catch (_: Exception) {}

            try {
                val resolver = WebViewResolver(interceptUrl = interceptRx, additionalUrls = listOf(interceptRx), useOkhttp = false, timeout = 15_000L)
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.contains("videoPreview")) return
                if (intercepted.isNotEmpty() && intercepted.contains("okcdn")) {
                    if (intercepted.contains("videoPlayerCdn") || intercepted.contains(".m3u8"))
                        M3u8Helper.generateM3u8(name, intercepted, "https://ok.ru/", headers = playbackHeaders).forEach(callback)
                    else callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.M3U8) {
                        this.referer = "https://ok.ru/"; this.headers = playbackHeaders
                    })
                }
            } catch (e: Exception) { WitaLog.d("OkRu WV fail: ${e.message}") }
        } catch (e: Exception) { WitaLog.e("OkRu error: ${e.message}") }
    }

    private suspend fun emitFromHtml(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        val unescaped = html.replace("\\&quot;", "\"").replace("&quot;", "\"").replace("\\\\", "\\")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
        var emitted = false
        val hls = Regex("""(?:hlsManifestUrl|ondemandHls)"?\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(unescaped)?.groupValues?.get(1)?.trim()
        if (hls != null && hls.startsWith("http")) {
            M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = playbackHeaders).forEach { callback(it); emitted = true }
            if (emitted) return true
        }
        for (text in listOf(unescaped, html)) {
            val videosStr = Regex(""""videos":(\[[^]]*\])"""").find(text)?.groupValues?.get(1)
            if (videosStr != null) {
                val videos = try { JSONArray(videosStr) } catch (_: Exception) { null }
                if (videos != null && videos.length() > 0) { emitted = emitCapped(videos, callback); if (emitted) return true }
            }
        }
        Regex("""(https?://[a-zA-Z0-9.-]+\.okcdn\.ru/[^\s"'<>\\]+)""").findAll(html)
            .map { it.groupValues[1] }.distinct().take(5).forEach { cdnUrl ->
                if (cdnUrl.contains("m3u8") || cdnUrl.contains("videoPlayerCdn"))
                    M3u8Helper.generateM3u8(name, cdnUrl, "https://ok.ru/", headers = playbackHeaders).forEach { callback(it); emitted = true }
            }
        return emitted
    }

    private fun okruQuality(name: String): Int {
        val n = name.lowercase()
        return when {
            n.contains("ultra") || n.contains("4k") || n.contains("2160") -> Qualities.P2160.value
            n.contains("quad") || n.contains("1440") -> Qualities.P1440.value
            n.contains("full") || n.contains("1080") -> Qualities.P1080.value
            n == "hd" || n.contains("720") -> Qualities.P720.value
            n == "sd" || n.contains("480") -> Qualities.P480.value
            n == "low" || n.contains("360") -> Qualities.P360.value
            n == "lowest" || n == "mobile" || n.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
