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
            // Handle both /video/ID and /videoembed/ID formats
            val headers = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to (referer ?: url),
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-User" to "?1",
            )

            val html = try { app.get(url, headers = headers).text } catch (_: Exception) { null }

            if (html != null) {
                if (emitOkruStream(html, callback)) {
                    println("WitAnimeDebug: OkRu: HTML parse SUCCESS")
                    return
                }
                println("WitAnimeDebug: OkRu: HTML parsed but no stream, trying WebView...")
            }

            // ═══ WebView fallback — the player JS constructs the m3u8 URL dynamically ═══
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""okcdn\.ru|\.m3u8|videoPlayerCdn"""),
                    additionalUrls = listOf(Regex("""okcdn\.ru|\.m3u8|videoPlayerCdn""")),
                    useOkhttp = false,
                    timeout = 20_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.isNotEmpty() && (intercepted.contains("okcdn") || intercepted.contains(".m3u8") || intercepted.contains("videoPlayerCdn"))) {
                    println("WitAnimeDebug: OkRu WV -> ${intercepted.take(120)}")
                    if (intercepted.contains(".m3u8") || intercepted.contains("videoPlayerCdn")) {
                        M3u8Helper.generateM3u8(name, intercepted, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach(callback)
                    } else if (intercepted.contains(".mp4")) {
                        callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) {
                            this.referer = "https://ok.ru/"
                        })
                    }
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: OkRu WV fail: ${e.message}")
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: OkRu error: ${e.message}")
        }
    }

    /** Parse whatever format ok.ru served and emit the stream (AnimeKhor technique) */
    private suspend fun emitOkruStream(html: String, callback: (ExtractorLink) -> Unit): Boolean {
        // 🎯 unescape the JSON that ok.ru embeds inside HTML
        val unescaped = html
            .replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

        var emitted = false

        // ═══ 1) Modern formats: hlsManifestUrl / ondemandHls ═══
        val hls = Regex("""(?:hlsManifestUrl|ondemandHls)"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(unescaped)?.groupValues?.get(1)?.trim()
        if (hls != null && hls.startsWith("http")) {
            println("WitAnimeDebug: OkRu hlsManifest -> ${hls.take(90)}")
            M3u8Helper.generateM3u8(name, hls, "https://ok.ru/", headers = mapOf("Referer" to "https://ok.ru/")).forEach {
                callback(it); emitted = true
            }
            if (emitted) return true
        }

        // ═══ 2) Legacy format: "videos":[{name,url}] ═══
        val videosStr = Regex(""""videos":(\[[^]]*\])"""").find(unescaped)?.groupValues?.get(1)
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

        // ═══ 3) Direct signed .okcdn.ru URL ═══
        val direct = Regex(""""url"\s*:\s*"(https?://[^"]*\?expires=[^"]*)"""").find(unescaped)?.groupValues?.get(1)
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

        return false
    }

    /** quality from ok.ru video name — full mapping */
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
