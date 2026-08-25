package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import org.json.JSONArray
import org.json.JSONObject

class DotPlayExtractor : ExtractorApi() {
    override val name = "DotPlay"
    override val mainUrl = "https://dotplay.net"
    override val requiresReferer = false

    private val cfKiller = CloudflareKiller()

    /** quality label (HD/FHD) from the parent server, set by routeLink */
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfter("/embed/").substringBefore("/").substringBefore("?")
            if (code.isBlank()) { println("WitAnimeDebug: DotPlay no code"); return }

            val headers = mapOf("User-Agent" to EXTRACTOR_UA)

            // ═══ METHOD 1: embed page (session) → api.php → base64-decoded video_url ═══
            try {
                app.get(url, headers = headers, referer = referer, interceptor = cfKiller)

                val apiUrl = "$mainUrl/api.php?code=$code"
                val apiResp = app.get(apiUrl, headers = headers + mapOf("Accept" to "application/json"), referer = url, interceptor = cfKiller)
                val jsonStr = apiResp.text
                println("WitAnimeDebug: DotPlay api=${jsonStr.take(250)}")

                val json = try { JSONObject(jsonStr) } catch (_: Exception) { null }

                // video_url is BASE64-ENCODED
                val encodedUrl = json?.optString("video_url", "") ?: ""
                var videoUrl = ""
                if (encodedUrl.isNotBlank()) {
                    try {
                        var fixed = encodedUrl.trim()
                        val pad = fixed.length % 4
                        if (pad != 0) fixed += "=".repeat(4 - pad)
                        videoUrl = String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                    } catch (_: Exception) { videoUrl = encodedUrl }
                }

                // fallback: recursive scan + base64 decode every long field
                if (!videoUrl.startsWith("http")) {
                    val urls = extractUrlsFromJson(jsonStr).filter {
                        it.startsWith("http") && !it.contains("dotplay.net") &&
                        !it.endsWith(".jpg") && !it.endsWith(".png")
                    }
                    videoUrl = urls.firstOrNull() ?: ""
                    if (!videoUrl.startsWith("http") && json != null) {
                        val encodedCandidates = mutableListOf<String>()
                        collectEncodedStrings(json, encodedCandidates)
                        for (enc in encodedCandidates) {
                            try {
                                var fixed = enc.trim()
                                val pad = fixed.length % 4
                                if (pad != 0) fixed += "=".repeat(4 - pad)
                                val decoded = String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                                if (decoded.startsWith("http")) { videoUrl = decoded; break }
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (videoUrl.startsWith("http")) {
                    emitVideo(videoUrl, callback)
                    return
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: DotPlay method1 fail: ${e.message}")
            }

            // ═══ METHOD 2: embed page scan (base64 tokens in HTML) ═══
            try {
                val embedHtml = app.get(url, headers = headers, referer = referer, interceptor = cfKiller).text
                Regex("""[A-Za-z0-9+/=]{40,}""").findAll(embedHtml).map { it.value }.distinct().take(30).forEach { tok ->
                    try {
                        var fixed = tok
                        val pad = fixed.length % 4
                        if (pad != 0) fixed += "=".repeat(4 - pad)
                        val d = String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                        if (d.startsWith("http")) {
                            println("WitAnimeDebug: DotPlay b64 -> ${d.take(100)}")
                            emitVideo(d, callback)
                            return
                        }
                    } catch (_: Exception) {}
                }
                val combined = "$embedHtml\n${unpackPackedJs(embedHtml) ?: ""}".replace("\\/", "/")
                val found = Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*)""").findAll(combined)
                    .map { it.groupValues[1].trimEnd('#') }.distinct().toList()
                if (found.isNotEmpty()) {
                    found.forEach { emitVideo(it, callback) }
                    return
                }
            } catch (_: Exception) {}

            // ═══ METHOD 3: WebView (✅ paren fixed) ═══
            println("WitAnimeDebug: DotPlay trying WebView")
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""dropboxusercontent|dropbox\.com|\.mp4|\.m3u8|previews\.dropbox"""),
                    additionalUrls = listOf(Regex("""dropboxusercontent|dropbox\.com|\.mp4|\.m3u8|previews\.dropbox""")),
                    useOkhttp = false,
                    timeout = 25_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.isNotEmpty() && intercepted != url) {
                    println("WitAnimeDebug: DotPlay WV -> ${intercepted.take(100)}")
                    emitVideo(intercepted, callback)
                }
            } catch (e: Exception) {
                println("WitAnimeDebug: DotPlay WV fail: ${e.message}")
            }
        } catch (e: Exception) {
            println("WitAnimeDebug: DotPlay error: ${e.message}")
        }
    }

    private suspend fun emitVideo(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        // strip |timestamp, #, quotes
        val cleanUrl = videoUrl
            .trim()
            .substringBefore('|')
            .trimEnd('#', '"', '\'')
            .replace(" ", "%20")

        println("WitAnimeDebug: DotPlay emitting -> ${cleanUrl.take(100)}")

        val qName = qualityName(cleanUrl, linkLabel)
        val displayLabel = "DotPlay" + (qName?.let { " $it" } ?: "")

        callback(newExtractorLink(name, displayLabel, cleanUrl,
            if (cleanUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.referer = mainUrl
            quality = bestQuality(cleanUrl, linkLabel)
        })
    }

    private fun extractUrlsFromJson(jsonStr: String): List<String> {
        val urls = mutableListOf<String>()
        try { collectUrls(JSONObject(jsonStr), urls) } catch (_: Exception) {
            try { collectUrls(JSONArray(jsonStr), urls) } catch (_: Exception) {
                Regex("""https?://[^\s"',}\\]+""").findAll(jsonStr).forEach {
                    urls.add(it.value.trimEnd('}', ']', '"'))
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

    private fun collectEncodedStrings(any: Any?, out: MutableList<String>) {
        when (any) {
            is JSONObject -> for (key in any.keys()) collectEncodedStrings(any.opt(key), out)
            is JSONArray -> for (i in 0 until any.length()) collectEncodedStrings(any.opt(i), out)
            is String -> if (any.length > 40 && any.matches(Regex("""[A-Za-z0-9+/=]+"""))) out.add(any)
        }
    }
}
