package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
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
            if (code.isBlank()) { println("WitAnimeDebug: DotPlay no code in $url"); return }

            // ═══ 1) Visit embed page FIRST — establishes the PHPSESSID session
            //        (exactly what the browser does before calling api.php) ═══
            val embedHeaders = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val embedPage = app.get(url, headers = embedHeaders, referer = referer)
            val embedHtml = embedPage.text
            println("WitAnimeDebug: DotPlay embed OK len=${embedHtml.length}")

            // ═══ 2) Call api.php — same session, referer = the embed page ═══
            val apiUrl = "$mainUrl/api.php?code=$code"
            val apiHeaders = mapOf(
                "User-Agent" to EXTRACTOR_UA,
                "Accept" to "application/json",
                "Referer" to url
            )
            val apiResp = app.get(apiUrl, headers = apiHeaders, referer = url)
            val jsonStr = apiResp.text
            println("WitAnimeDebug: DotPlay api RAW=${jsonStr.take(300)}")   // 📸 full response in logcat

            // ═══ 3) LEARNING-MACHINE STYLE: recursively grab EVERY http URL
            //        anywhere in the JSON — field name doesn't matter ═══
            val urls = extractUrlsFromJson(jsonStr).filter {
                it.startsWith("http") && !it.contains("dotplay.net") && !it.endsWith(".jpg") && !it.endsWith(".png")
            }

            if (urls.isNotEmpty()) {
                urls.forEach { videoUrl ->
                    val cleanUrl = videoUrl.trimEnd('#', '"')
                    println("WitAnimeDebug: DotPlay found -> ${cleanUrl.take(100)}")
                    when {
                        // dropbox raw=1 → 302 → dropboxusercontent serves video/mp4 (no referer needed)
                        cleanUrl.contains("dropbox") -> {
                            callback(newExtractorLink(name, "DotPlay", cleanUrl, ExtractorLinkType.VIDEO) {
                                quality = detectQuality(cleanUrl)
                            })
                        }
                        // other known CDNs (archive.org, soraplay, okcdn)
                        isDirectCdnLink(cleanUrl) -> emitDirectCdn(cleanUrl, callback = callback)
                        // anything else — emit as direct video, let the player follow redirects
                        else -> {
                            callback(newExtractorLink(name, "DotPlay", cleanUrl,
                                if (cleanUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                quality = detectQuality(cleanUrl)
                            })
                        }
                    }
                }
                return
            }

            // ═══ 4) Fallback: scan the embed page HTML for direct links ═══
            println("WitAnimeDebug: DotPlay: api gave nothing, scanning embed page")
            val combined = "$embedHtml\n${unpackPackedJs(embedHtml) ?: ""}".replace("\\/", "/")
            val candidates = mutableListOf<String>()
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*)""").findAll(combined).forEach {
                candidates.add(it.groupValues[1].trimEnd('#'))
            }
            Regex("""https?://(?:www\.dropbox\.com|dl\.dropboxusercontent\.com|soraplay\.[a-z]+|[^"'\s]*archive\.org)/[^\s"'<>]+""").findAll(embedHtml).forEach {
                candidates.add(it.groupValues[1].trimEnd('#', '"', '\''))
            }
            candidates.distinct().filter { it.startsWith("http") }.forEach { link ->
                println("WitAnimeDebug: DotPlay fallback -> ${link.take(100)}")
                callback(newExtractorLink(name, "DotPlay", link, ExtractorLinkType.VIDEO) {
                    quality = detectQuality(link)
                })
            }

            // ═══ 5) Last resort: WebView on the embed page (JS may build the URL) ═══
            if (candidates.isEmpty()) {
                println("WitAnimeDebug: DotPlay: trying WebView intercept")
                try {
                    val resolver = com.lagradost.cloudstream3.network.WebViewResolver(
                        interceptUrl = Regex("""\.mp4|\.m3u8|dropboxusercontent|soraplay"""),
                        additionalUrls = listOf(Regex("""\.mp4|\.m3u8|dropboxusercontent|soraplay""")),
                        useOkhttp = false,
                        timeout = 15_000L
                    )
                    val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                    if (intercepted.isNotEmpty() && intercepted != url) {
                        println("WitAnimeDebug: DotPlay WV -> ${intercepted.take(100)}")
                        callback(newExtractorLink(name, "DotPlay", intercepted.trimEnd('#'),
                            if (intercepted.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            quality = detectQuality(intercepted)
                        })
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: DotPlay error: ${e.message}")
        }
    }

    /** 🔍 recursively walk any JSON structure and collect all http strings */
    private fun extractUrlsFromJson(jsonStr: String): List<String> {
        val urls = mutableListOf<String>()
        try {
            collectUrls(JSONObject(jsonStr), urls)
        } catch (_: Exception) {
            try {
                collectUrls(JSONArray(jsonStr), urls)
            } catch (_: Exception) {
                // not valid JSON — regex the raw text
                Regex("""https?://[^\s"',}\\]+""").findAll(jsonStr).forEach {
                    urls.add(it.value.trimEnd('}', ']'))
                }
            }
        }
        return urls.distinct()
    }

    private fun collectUrls(any: Any?, out: MutableList<String>) {
        when (any) {
            is JSONObject -> for (key in any.keys()) collectUrls(any.opt(key), out)
            is JSONArray -> for (i in 0 until any.length()) collectUrls(any.opt(i), out)
            is String -> if (any.startsWith("http")) out.add(any)
        }
    }

    private fun detectQuality(url: String): Int = when {
        url.contains("1080") || url.contains("FHD", true) || url.contains("source") -> Qualities.P1080.value
        url.contains("720") || url.contains("HD", true) -> Qualities.P720.value
        url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
