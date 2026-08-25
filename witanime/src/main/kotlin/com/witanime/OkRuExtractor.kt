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

            // ═══ METHOD 1: Direct HTML fetch with browser-like headers ═══
            val headers = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to (referer ?: "https://ok.ru/"),
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
            )

            var html: String? = null
            try {
                val resp = app.get(url, headers = headers)
                html = resp.text
                println("WitAnimeDebug: OkRu METHOD1: got ${html.length} bytes, code=${resp.code}")
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu METHOD1 FAIL: ${e.message}")
            }

            if (html != null && html.length > 500) {
                if (emitOkruStream(html!!, callback)) {
                    println("WitAnimeDebug: OkRu: HTML parse SUCCESS")
                    return
                }
                println("WitAnimeDebug: OkRu: HTML parsed but no stream found")
            }

            // ═══ METHOD 2: WebView — the only reliable way for videoembed pages ═══
            // The /videoembed/ page is a JS-heavy page where the player constructs
            // the m3u8 URL dynamically. We intercept the actual CDN request.
            println("WitAnimeDebug: OkRu: trying WebView intercept")
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""okcdn\.ru|\.m3u8|videoPlayerCdn|video\.m3u8"""),
                    additionalUrls = listOf(Regex("""okcdn\.ru|\.m3u8|videoPlayerCdn|video\.m3u8""")),
                    useOkhttp = false,
                    timeout = 25_000L
                )
                val wvResp = app.get(url, referer = referer, interceptor = resolver)
                val intercepted = wvResp.url
                println("WitAnimeDebug: OkRu WV: intercepted=${intercepted.take(120)}")

                if (intercepted.isNotEmpty() && intercepted != url) {
                    when {
                        intercepted.contains(".m3u8") || intercepted.contains("videoPlayerCdn") || intercepted.contains("okcdn.ru") -> {
                            println("WitAnimeDebug: OkRu WV: found m3u8!")
                            M3u8Helper.generateM3u8(name, intercepted, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach(callback)
                            return
                        }
                        intercepted.contains(".mp4") -> {
                            println("WitAnimeDebug: OkRu WV: found mp4!")
                            callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) {
                                this.referer = "https://ok.ru/"
                            })
                            return
                        }
                        else -> {
                            println("WitAnimeDebug: OkRu WV: intercepted but not a stream URL")
                        }
                    }
                } else {
                    println("WitAnimeDebug: OkRu WV: nothing intercepted or same URL")
                    // Try to parse the WebView's rendered page HTML
                    val wvHtml = wvResp.text
                    if (wvHtml.length > 500) {
                        println("WitAnimeDebug: OkRu WV: trying HTML from WV response (${wvHtml.length} bytes)")
                        if (emitOkruStream(wvHtml, callback)) {
                            println("WitAnimeDebug: OkRu WV: HTML parse SUCCESS")
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu WV FAIL: ${e.message}")
            }

            println("WitAnimeDebug: OkRu: ALL METHODS FAILED")
        } catch (e: Exception) {
            println("WitAnimeDebug: OkRu error: ${e.message}")
        }
    }

    /** Parse whatever format ok.ru served and emit the stream */
    private suspend fun emitOkruStream(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Unescape the JSON that ok.ru embeds inside HTML
        val unescaped = html
            .replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

        var emitted = false

        // ═══ 1) Modern formats: hlsManifestUrl / ondemandHls ═══
        val hls = Regex("""(?:hlsManifestUrl|ondemandHls)"?\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(unescaped)?.groupValues?.get(1)?.trim()
        if (hls != null && hls.startsWith("http")) {
            println("WitAnimeDebug: OkRu hlsManifest -> ${hls.take(90)}")
            M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
                callback(it); emitted = true
            }
            if (emitted) return true
        } else {
            println("WitAnimeDebug: OkRu: no hlsManifestUrl found")
        }

        // ═══ 2) Legacy format: "videos":[{name,url}] (data-options) ═══
        // Try BOTH the unescaped and raw versions
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
                        if (!full.startsWith("http")) continue
                        val vName = v.optString("name")
                        println("WitAnimeDebug: OkRu video [$vName] -> ${full.take(90)}")
                        callback(newExtractorLink(name, "$name $vName", full,
                            if (full.contains(".m3u8") || full.contains("type/")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = "https://ok.ru/"
                            quality = okruQuality(vName)
                        })
                        emitted = true
                    }
                    if (emitted) return true
                }
            }
        }

        // ═══ 3) Direct signed .okcdn.ru URL in JSON ═══
        val direct = Regex(""""url"\s*:\s*"(https?://[^"]*okcdn[^"]*\?expires=[^"]*)"""").find(unescaped)?.groupValues?.get(1)
        if (direct != null && direct.startsWith("http")) {
            println("WitAnimeDebug: OkRu direct -> ${direct.take(90)}")
            callback(newExtractorLink(name, name, direct, ExtractorLinkType.VIDEO) {
                this.referer = "https://ok.ru/"
                quality = Qualities.Unknown.value
            })
            return true
        }

        // ═══ 4) Raw okcdn.ru m3u8 URLs anywhere in page ═══
        Regex("""(https?://[a-zA-Z0-9.-]+\.okcdn\.ru/[^\s"'<>\\]+)""").findAll(html)
            .map { it.groupValues[1] }.distinct().take(5).forEach { cdnUrl ->
                if (cdnUrl.contains("m3u8") || cdnUrl.contains("video")) {
                    println("WitAnimeDebug: OkRu CDN -> ${cdnUrl.take(90)}")
                    M3u8Helper.generateM3u8(name, cdnUrl, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
                        callback(it); emitted = true
                    }
                }
            }
        if (emitted) return true

        // ═══ 5) Any m3u8 URL on the page ═══
        Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").findAll(html).forEach { m ->
            val u = m.groupValues[1].replace("\\/", "/")
            if (u.startsWith("http") && !emitted) {
                println("WitAnimeDebug: OkRu generic m3u8 -> ${u.take(90)}")
                M3u8Helper.generateM3u8(name, u, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
                    callback(it); emitted = true
                }
            }
        }

        println("WitAnimeDebug: OkRu: emitOkruStream result: $emitted")
        return emitted
    }

    /** quality from ok.ru video name */
    private fun okruQuality(name: String): Int {
        val n = name.uppercase()
        return when {
            n.contains("4K") || n.contains("ULTRA") -> Qualities.P2160.value
            n.contains("1440") || n.contains("QUAD") -> Qualities.P1440.value
            n.contains("1080") || n.contains("FULL") -> Qualities.P1080.value
            n.contains("720") || n.contains("HD") -> Qualities.P720.value
            n.contains("480") || n.contains("SD") -> Qualities.P480.value
            n.contains("360") || n.contains("LOW") -> Qualities.P360.value
            n.contains("240") || n.contains("LOWEST") -> Qualities.P240.value
            n.contains("144") || n.contains("MOBILE") -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }
}
