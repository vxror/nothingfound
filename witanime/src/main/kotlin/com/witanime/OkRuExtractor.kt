package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.json.JSONArray
import org.json.JSONObject

class OkRuExtractor : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    private val playbackHeaders = mapOf(
        "User-Agent" to EXTRACTOR_UA,
        "Referer" to "https://ok.ru/",
        "Origin" to "https://ok.ru",
    )

    /** [v166] cap ok.ru's 4-8 quality variants at the best 3, with stage logging */
    private suspend fun emitCapped(
        videos: JSONArray, callback: (ExtractorLink) -> Unit
    ): Boolean {
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
        val picked = candidates.take(3)
        println("WitAnimeDebug: OkRu emitCapped — ${candidates.size} variants, emitting ${picked.size}")
        picked.forEach { (nm, full, q) ->
            callback(newExtractorLink(name, nm, full, ExtractorLinkType.M3U8) {
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
            println("WitAnimeDebug: OkRu START url=$url")

            val videoId = url.substringAfter("/videoembed/").substringAfter("/video/")
                .substringBefore("/").substringBefore("?").trim()
            if (videoId.isBlank()) { println("WitAnimeDebug: OkRu ABORT: no ID in url"); return }

            val headers = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to (referer ?: "https://ok.ru/"),
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
            )

            var emitted = false

            // ═══ METHOD 1: Metadata API (warm-up removed — saves 2-3s) ═══
            try {
                val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
                val apiText = app.get(apiUrl, headers = headers).text
                println("WitAnimeDebug: OkRu M1 api len=${apiText.length}")

                if (apiText.length > 100) {
                    val json = try { JSONObject(apiText) } catch (_: Exception) { null }
                    if (json != null) {
                        val videos = json.optJSONArray("videos")
                        if (videos != null && videos.length() > 0) {
                            emitted = emitCapped(videos, callback)
                        }
                        if (!emitted) {
                            val hls = json.optString("hlsManifestUrl")
                            if (hls.isNotBlank() && hls.startsWith("http")) {
                                println("WitAnimeDebug: OkRu M1 hls -> ${hls.take(90)}")
                                M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = playbackHeaders).forEach {
                                    callback(it); emitted = true
                                }
                            }
                        }
                        if (!emitted) {
                            val movieVideos = json.optJSONObject("movie")?.optJSONArray("videos")
                            if (movieVideos != null) {
                                emitted = emitCapped(movieVideos, callback)
                            }
                        }
                    }
                }
                if (emitted) { println("WitAnimeDebug: OkRu M1 SUCCESS"); return }
                println("WitAnimeDebug: OkRu M1 empty — falling to M2")
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu M1 fail: ${e.message}")
            }

            // ═══ METHOD 2: HTML scrape ═══
            try {
                val html = app.get(url, headers = headers).text
                println("WitAnimeDebug: OkRu M2 html len=${html.length}")
                if (html.length > 500 && emitFromHtml(html, callback)) {
                    println("WitAnimeDebug: OkRu M2 SUCCESS")
                    return
                }
            } catch (_: Exception) {}

            // ═══ METHOD 3: WebView intercept — 12s (was 15s, tightens the budget) ═══
            println("WitAnimeDebug: OkRu M3 WebView")
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""okcdn\.ru|videoPlayerCdn|\.m3u8"""),
                    additionalUrls = listOf(Regex("""okcdn\.ru|videoPlayerCdn|\.m3u8""")),
                    useOkhttp = false,
                    timeout = 12_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                println("WitAnimeDebug: OkRu M3 WV=${intercepted.take(120)}")

                if (intercepted.isNotEmpty() && intercepted.contains("okcdn")) {
                    if (intercepted.contains("videoPlayerCdn") || intercepted.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, intercepted, "https://ok.ru/", headers = playbackHeaders).forEach(callback)
                    } else {
                        callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.M3U8) {
                            this.referer = "https://ok.ru/"
                            this.headers = playbackHeaders
                        })
                    }
                    println("WitAnimeDebug: OkRu M3 SUCCESS")
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu M3 fail: ${e.message}")
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: OkRu error: ${e.message}")
        }
    }

    private suspend fun emitFromHtml(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        val unescaped = html
            .replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

        var emitted = false

        val hls = Regex("""(?:hlsManifestUrl|ondemandHls)"?\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(unescaped)?.groupValues?.get(1)?.trim()
        if (hls != null && hls.startsWith("http")) {
            M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = playbackHeaders).forEach {
                callback(it); emitted = true
            }
            if (emitted) return true
        }

        for (text in listOf(unescaped, html)) {
            val videosStr = Regex(""""videos":(\[[^]]*\])"""").find(text)?.groupValues?.get(1)
            if (videosStr != null) {
                val videos = try { JSONArray(videosStr) } catch (_: Exception) { null }
                if (videos != null && videos.length() > 0) {
                    emitted = emitCapped(videos, callback)
                    if (emitted) return true
                }
            }
        }

        Regex("""(https?://[a-zA-Z0-9.-]+\.okcdn\.ru/[^\s"'<>\\]+)""").findAll(html)
            .map { it.groupValues[1] }.distinct().take(5).forEach { cdnUrl ->
                if (cdnUrl.contains("m3u8") || cdnUrl.contains("videoPlayerCdn")) {
                    M3u8Helper.generateM3u8(name, cdnUrl, "https://ok.ru/", headers = playbackHeaders).forEach {
                        callback(it); emitted = true
                    }
                }
            }

        return emitted
    }

    private fun okruQuality(name: String): Int {
        val n = name.uppercase()
        return when {
            n.contains("4K") || n.contains("ULTRA") -> Qualities.P2160.value
            n.contains("1440") || n.contains("QUAD") -> Qualities.P1440.value
            n.contains("1080") || n.contains("FULL") -> Qualities.P1080.value
            n.contains("720") || n.contains("HD") -> Qualities.P720.value
            n.contains("480") || n.contains("SD") -> Qualities.P480.value
            n.contains("360") || n.contains("LOW") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
