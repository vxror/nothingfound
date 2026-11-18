package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.UUID

open class VideasExtractor : ExtractorApi() {
    override val name = "Videas"
    override val mainUrl = "https://app.videas.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val origin = if (url.contains("videas.fr")) "https://app.videas.fr" else "https://app.videas.com"
        val normalizedUrl = url.replace("/embed/media/", "/embed/")
        if (normalizedUrl.contains(".m3u8")) { emit(normalizedUrl, normalizedUrl, callback); return }

        var found = false
        try {
            found = withTimeoutOrNull(20_000L) { viaApi(normalizedUrl, origin, referer, subtitleCallback, callback) } ?: false
        } catch (e: Exception) { WitaLog.d("Videas API fail: ${e.message}") }
        if (found) return

        val foundLinks = LinkedHashSet<String>()
        val m3u8Rx = Regex("""https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*""")
        try {
            val raw = sessionGet(normalizedUrl, referer = referer)
                ?: app.get(normalizedUrl, headers = mapOf("User-Agent" to EXTRACTOR_UA)).text
            val cleaned = raw.replace("\\/", "/").replace("\\\"", "\"")
            m3u8Rx.findAll(cleaned).forEach { foundLinks.add(it.value) }
            Regex(""""(?:src|file|url|source)"\s*:\s*"([^"]+\.m3u8[^"]*)"""").findAll(cleaned).forEach { foundLinks.add(it.groupValues[1]) }
        } catch (e: Exception) { WitaLog.d("Videas L1 fail: ${e.message}") }

        if (foundLinks.isEmpty()) {
            try {
                val res = app.get(normalizedUrl, referer = referer,
                    interceptor = WebViewResolver(interceptUrl = Regex("""\.m3u8""", RegexOption.IGNORE_CASE)))
                if (res.url.contains(".m3u8")) foundLinks.add(res.url)
            } catch (e: Exception) { WitaLog.d("Videas L2 fail: ${e.message}") }
        }
        foundLinks.filter { it.startsWith("http") }.forEach { emit(normalizedUrl, it, callback) }
    }

    private suspend fun viaApi(
        pageUrl: String, origin: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = sessionGet(pageUrl, referer = referer ?: "https://witanime.site/") ?: return false
        val doc = Jsoup.parse(html, pageUrl)
        var embedId = ""
        var opening = ""
        val cfgRaw = doc.selectFirst("script#videas-embed-config")?.data().orEmpty()
        if (cfgRaw.isNotBlank()) {
            runCatching { JSONObject(cfgRaw) }.getOrNull()?.let {
                embedId = it.optString("embedId").trim()
                opening = it.optString("opening").trim()
            }
        }
        if (embedId.isEmpty())
            embedId = Regex("""/embed/(?:media/)?([0-9a-fA-F-]{20,})""").find(pageUrl)?.groupValues?.get(1).orEmpty()
        if (embedId.isEmpty()) return false

        val visHeaders = mutableMapOf("Accept" to "application/json", "Origin" to origin)
        if (opening.isNotEmpty()) visHeaders["X-Embed-Opening"] = opening
        val visRaw = sessionGet("$origin/api/visitor/embed/$embedId/?viewer_id=${UUID.randomUUID()}",
            referer = pageUrl, headers = visHeaders) ?: return false
        val visObj = runCatching { JSONObject(visRaw) }.getOrNull() ?: return false
        val playbackToken = visObj.optString("playback_token").trim()
        val sessionId = visObj.optString("session_id").trim()
        val items = visObj.optJSONArray("items") ?: JSONArray()
        var emitted = false

        for (idx in 0 until items.length()) {
            val item = items.optJSONObject(idx) ?: continue
            val asset = item.optJSONObject("asset") ?: continue
            val assetUid = asset.optString("uid").trim()
            if (assetUid.isEmpty()) continue

            asset.optJSONArray("subtitles")?.let { subs ->
                for (s in 0 until subs.length()) {
                    val sub = subs.optJSONObject(s) ?: continue
                    val subUrl = sub.optString("url").trim()
                    val lang = sub.optString("label", sub.optString("language", "Arabic")).trim()
                    if (subUrl.isNotEmpty())
                        subtitleCallback(newSubtitleFile(lang, if (subUrl.startsWith("http")) subUrl else "$origin$subUrl"))
                }
            }

            val pbJson = JSONObject().put("asset_id", assetUid)
                .put("playback_token", playbackToken).put("session_id", sessionId).toString()
            val pbRaw = sessionPost("$origin/api/visitor/playback/info/", pbJson, referer = pageUrl,
                headers = mapOf("Accept" to "application/json", "Origin" to origin)) ?: continue
            val pbObj = runCatching { JSONObject(pbRaw) }.getOrNull() ?: continue

            val hlsRaw = pbObj.optJSONObject("hls_url")?.optString("url")
                ?: pbObj.optString("hls_url").takeIf { it.startsWith("/") || it.startsWith("http") }
            if (!hlsRaw.isNullOrBlank()) {
                val fullHls = if (hlsRaw.startsWith("http")) hlsRaw else "$origin$hlsRaw"
                val variants = M3u8Helper.generateM3u8(name, fullHls, "$origin/",
                    headers = mapOf("User-Agent" to EXTRACTOR_UA, "Referer" to "$origin/"))
                if (variants.isNotEmpty()) { variants.forEach(callback); emitted = true }
                else { emit(pageUrl, fullHls, callback); emitted = true }
            }
            val mp4Raw = pbObj.optJSONObject("source_url")?.optString("url")
                ?: pbObj.optString("source_url").takeIf { it.startsWith("/") || it.startsWith("http") }
            if (!mp4Raw.isNullOrBlank()) {
                val fullMp4 = if (mp4Raw.startsWith("http")) mp4Raw else "$origin$mp4Raw"
                callback(newExtractorLink(name, "$name (MP4)", fullMp4, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    quality = urlQuality(fullMp4)
                })
                emitted = true
            }
        }
        if (emitted) WitaLog.d("Videas API SUCCESS")
        return emitted
    }

    private suspend fun emit(pageUrl: String, link: String, callback: (ExtractorLink) -> Unit) {
        val q = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)
        callback(newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
            this.referer = hostOf(pageUrl)
            quality = getQualityFromName(q)
        })
    }
}

class VideasFrExtractor : VideasExtractor() {
    override val name = "VideasFr"
    override val mainUrl = "https://app.videas.fr"
}
