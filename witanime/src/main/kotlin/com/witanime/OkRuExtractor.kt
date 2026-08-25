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

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("WitAnimeDebug: OkRu START url=$url")

            // Extract video ID from both URL formats
            val videoId = url.substringAfter("/videoembed/").substringAfter("/video/")
                .substringBefore("/").substringBefore("?").trim()
            if (videoId.isBlank()) { println("WitAnimeDebug: OkRu no ID"); return }

            val headers = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to (referer ?: "https://ok.ru/"),
            )

            var emitted = false

            // ═══ METHOD 1: Metadata API — returns JSON with video URLs directly ═══
            try {
                val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
                val apiResp = app.get(apiUrl, headers = headers)
                val apiText = apiResp.text
                println("WitAnimeDebug: OkRu API len=${apiText.length}")

                if (apiText.length > 100) {
                    val json = try { JSONObject(apiText) } catch (_: Exception) { null }
                    if (json != null) {
                        // Try videos array (most common format)
                        val videos = json.optJSONArray("videos")
                        if (videos != null) {
                            for (i in 0 until videos.length()) {
                                val v = videos.optJSONObject(i) ?: continue
                                val u = v.optString("url")
                                if (u.isBlank()) continue
                                val full = if (u.startsWith("//")) "https:$u" else u
                                if (!full.startsWith("http")) continue
                                val vName = v.optString("name")
                                println("WitAnimeDebug: OkRu API video [$vName] -> ${full.take(90)}")
                                callback(newExtractorLink(name, "$name $vName", full,
                                    if (full.contains(".m3u8") || full.contains("type/")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                    this.referer = "https://ok.ru/"
                                    quality = okruQuality(vName)
                                })
                                emitted = true
                            }
                        }

                        // Try hlsManifestUrl
                        if (!emitted) {
                            val hls = json.optString("hlsManifestUrl")
                            if (hls.isNotBlank() && hls.startsWith("http")) {
                                println("WitAnimeDebug: OkRu API hls -> ${hls.take(90)}")
                                M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
                                    callback(it); emitted = true
                                }
                            }
                        }

                        // Try movie > videos
                        if (!emitted) {
                            val movie = json.optJSONObject("movie")
                            val movieVideos = movie?.optJSONArray("videos")
                            if (movieVideos != null) {
                                for (i in 0 until movieVideos.length()) {
                                    val v = movieVideos.optJSONObject(i) ?: continue
                                    val u = v.optString("url")
                                    if (u.isBlank()) continue
                                    val full = if (u.startsWith("//")) "https:$u" else u
                                    val vName = v.optString("name")
                                    println("WitAnimeDebug: OkRu API movie video [$vName] -> ${full.take(90)}")
                                    callback(newExtractorLink(name, "$name $vName", full, ExtractorLinkType.VIDEO) {
                                        this.referer = "https://ok.ru/"
                                        quality = okruQuality(vName)
                                    })
                                    emitted = true
                                }
                            }
                        }
                    }
                }
                if (emitted) { println("WitAnimeDebug: OkRu: API SUCCESS"); return }
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu API fail: ${e.message}")
            }

            // ═══ METHOD 2: Scrape the embed page HTML ═══
            try {
                val html = app.get(url, headers = headers).text
                println("WitAnimeDebug: OkRu HTML len=${html.length}")
                if (html.length > 500) {
                    if (emitFromHtml(html, callback)) {
                        println("WitAnimeDebug: OkRu: HTML SUCCESS")
                        return
                    }
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu HTML fail: ${e.message}")
            }

            // ═══ METHOD 3: WebView (15s timeout) ═══
            println("WitAnimeDebug: OkRu: trying WebView (15s)")
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""okcdn\.ru|\.m3u8|videoPlayerCdn"""),
                    additionalUrls = listOf(Regex("""okcdn\.ru|\.m3u8|videoPlayerCdn""")),
                    useOkhttp = false,
                    timeout = 15_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.isNotEmpty() && intercepted != url &&
                    (intercepted.contains("okcdn") || intercepted.contains(".m3u8") || intercepted.contains("videoPlayerCdn"))) {
                    println("WitAnimeDebug: OkRu WV -> ${intercepted.take(120)}")
                    if (intercepted.contains(".m3u8") || intercepted.contains("videoPlayerCdn")) {
                        M3u8Helper.generateM3u8(name, intercepted, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach(callback)
                    } else {
                        callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) {
                            this.referer = "https://ok.ru/"
                        })
                    }
                    return
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu WV fail: ${e.message}")
            }

            println("WitAnimeDebug: OkRu: ALL METHODS FAILED")
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
            M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
                callback(it); emitted = true
            }
            if (emitted) return true
        }

        for (text in listOf(unescaped, html)) {
            val videosStr = Regex(""""videos":(\[[^]]*\])"""").find(text)?.groupValues?.get(1)
            if (videosStr != null) {
                val videos = try { JSONArray(videosStr) } catch (_: Exception) { null }
                if (videos != null && videos.length() > 0) {
                    for (i in 0 until videos.length()) {
                        val v = videos.optJSONObject(i) ?: continue
                        val u = v.optString("url")
                        if (u.isBlank()) continue
                        val full = if (u.startsWith("//")) "https:$u" else u
                        val vName = v.optString("name")
                        callback(newExtractorLink(name, "$name $vName", full, ExtractorLinkType.VIDEO) {
                            this.referer = "https://ok.ru/"
                            quality = okruQuality(vName)
                        })
                        emitted = true
                    }
                    if (emitted) return true
                }
            }
        }

        Regex("""(https?://[a-zA-Z0-9.-]+\.okcdn\.ru/[^\s"'<>\\]+)""").findAll(html)
            .map { it.groupValues[1] }.distinct().take(5).forEach { cdnUrl ->
                if (cdnUrl.contains("m3u8") || cdnUrl.contains("video")) {
                    M3u8Helper.generateM3u8(name, cdnUrl, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
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
