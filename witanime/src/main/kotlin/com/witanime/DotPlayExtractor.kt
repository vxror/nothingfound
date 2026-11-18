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
    var linkLabel: String? = null

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfter("/embed/").substringBefore("/").substringBefore("?")
            if (code.isBlank()) { WitaLog.d("DotPlay no code"); return }
            val headers = mapOf("User-Agent" to EXTRACTOR_UA)

            try {
                var embedHtml = sessionGet(url, referer = referer)
                if (embedHtml == null || WitaWeb.looksChallenge(embedHtml)) {
                    embedHtml = try { app.get(url, headers = headers, referer = referer, interceptor = cfKiller).text }
                    catch (_: Exception) { embedHtml ?: "" }
                }
                val embedCode = Regex("""window\.embedCode\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1) ?: code
                val embedToken = Regex("""window\.embedToken\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1).orEmpty()
                val apiHeaders = mutableMapOf("Accept" to "application/json")
                if (embedToken.isNotBlank()) apiHeaders["X-Embed-Token"] = embedToken

                val apiUrl = "$mainUrl/api.php?code=$embedCode"
                var jsonStr = sessionGet(apiUrl, referer = url, headers = apiHeaders)
                if (jsonStr.isNullOrBlank() || !jsonStr.trimStart().startsWith("{")) {
                    jsonStr = try { app.get(apiUrl, headers = headers + apiHeaders, referer = url, interceptor = cfKiller).text }
                    catch (_: Exception) { jsonStr ?: "" }
                }
                val json = try { JSONObject(jsonStr) } catch (_: Exception) { null }
                val encodedUrl = json?.optString("video_url", "") ?: ""
                var videoUrl = ""
                if (encodedUrl.isNotBlank()) {
                    videoUrl = try {
                        var fixed = encodedUrl.trim()
                        val pad = fixed.length % 4
                        if (pad != 0) fixed += "=".repeat(4 - pad)
                        String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                    } catch (_: Exception) { encodedUrl }
                }
                if (!videoUrl.startsWith("http")) {
                    videoUrl = extractUrlsFromJson(jsonStr).firstOrNull {
                        it.startsWith("http") && !it.contains("dotplay.net") && !it.endsWith(".jpg") && !it.endsWith(".png")
                    } ?: ""
                    if (!videoUrl.startsWith("http") && json != null) {
                        val enc = mutableListOf<String>()
                        collectEncodedStrings(json, enc)
                        for (e in enc) {
                            try {
                                var fixed = e.trim()
                                val pad = fixed.length % 4
                                if (pad != 0) fixed += "=".repeat(4 - pad)
                                val d = String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                                if (d.startsWith("http")) { videoUrl = d; break }
                            } catch (_: Exception) {}
                        }
                    }
                }
                if (videoUrl.startsWith("http")) { emitVideo(videoUrl, callback); return }

                Regex("""[A-Za-z0-9+/=]{40,}""").findAll(embedHtml).map { it.value }.distinct().take(30).forEach { tok ->
                    try {
                        var fixed = tok
                        val pad = fixed.length % 4
                        if (pad != 0) fixed += "=".repeat(4 - pad)
                        val d = String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                        if (d.startsWith("http")) { emitVideo(d, callback); return }
                    } catch (_: Exception) {}
                }
                val combined = "$embedHtml\n${unpackPackedJs(embedHtml) ?: ""}".replace("\\/", "/")
                val found = Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*)""").findAll(combined)
                    .map { it.groupValues[1].trimEnd('#') }.distinct().toList()
                if (found.isNotEmpty()) { found.forEach { emitVideo(it, callback) }; return }
            } catch (e: Exception) { WitaLog.d("DotPlay method1/2 fail: ${e.message}") }

            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""dropboxusercontent|dropbox\.com|\.mp4|\.m3u8|previews\.dropbox"""),
                    additionalUrls = listOf(Regex("""dropboxusercontent|dropbox\.com|\.mp4|\.m3u8|previews\.dropbox""")),
                    useOkhttp = false, timeout = 25_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.isNotEmpty() && intercepted != url) emitVideo(intercepted, callback)
            } catch (e: Exception) { WitaLog.d("DotPlay WV fail: ${e.message}") }
        } catch (e: Exception) { WitaLog.e("DotPlay error: ${e.message}") }
    }

    private suspend fun emitVideo(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        val cleanUrl = videoUrl.trim().substringBefore('|').trimEnd('#', '"', '\'').replace(" ", "%20")
        val qName = qualityName(cleanUrl, linkLabel)
        callback(newExtractorLink(name, "DotPlay" + (qName?.let { " $it" } ?: ""), cleanUrl,
            if (cleanUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            this.referer = mainUrl
            quality = bestQuality(cleanUrl, linkLabel)
        })
    }

    private fun extractUrlsFromJson(jsonStr: String): List<String> {
        val urls = mutableListOf<String>()
        try { collectUrls(JSONObject(jsonStr), urls) } catch (_: Exception) {
            try { collectUrls(JSONArray(jsonStr), urls) } catch (_: Exception) {
                Regex("""https?://[^\s"',}\\]+""").findAll(jsonStr).forEach { urls.add(it.value.trimEnd('}', ']', '"')) }
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
