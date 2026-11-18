package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class GoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    private val wtSalt = "12af056dacea0b" // gofile web client salt — update if rotated

    private fun websiteToken(accountToken: String, lang: String = "ar"): String {
        val bucket = (System.currentTimeMillis() / 1000L) / 14_400L
        val payload = "$EXTRACTOR_UA::$lang::$accountToken::$bucket::$wtSalt"
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val contentId = Regex("""gofile\.io/(?:d/|contents/)?([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')
            if (contentId.isBlank()) return
            val baseHeaders = mapOf("Origin" to "https://gofile.io", "Referer" to "https://gofile.io/")
            val accRaw = sessionPost("https://api.gofile.io/accounts", "{}", headers = baseHeaders) ?: return
            val token = runCatching { JSONObject(accRaw).optJSONObject("data")?.optString("token").orEmpty() }.getOrDefault("")
            if (token.isBlank()) return

            val apiHeaders = baseHeaders + mapOf(
                "Authorization" to "Bearer $token",
                "X-Website-Token" to websiteToken(token),
                "X-BL" to "ar",
                "Accept" to "application/json"
            )
            val contentRaw = sessionGet(
                "https://api.gofile.io/contents/$contentId?page=1&pageSize=100&sortField=name&sortDirection=1",
                referer = "https://gofile.io/", headers = apiHeaders) ?: return
            val data = runCatching { JSONObject(contentRaw).optJSONObject("data") }.getOrNull() ?: return

            val entries = mutableListOf<JSONObject>()
            if (data.optString("type") == "file") entries.add(data)
            else {
                data.optJSONObject("children")?.let { ch -> ch.keys().forEach { k -> ch.optJSONObject(k)?.let { entries.add(it) } } }
                data.optJSONArray("children")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) } }
            }
            val videoExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m3u8", ".flv", ".m4v")
            val playable = entries.filter { child ->
                val n = child.optString("name").lowercase()
                val mime = child.optString("mimetype").lowercase()
                child.optString("link").isNotBlank() && (mime.startsWith("video") || videoExt.any { n.endsWith(it) })
            }.sortedByDescending { it.optLong("size", 0L) }.take(4)

            playable.forEach { child ->
                val fileName = child.optString("name")
                val q = getQualityFromName(fileName).takeIf { it > 0 } ?: urlQuality(fileName)
                callback(newExtractorLink(name, "$name ${fileName.take(40)}", child.optString("link").trim(),
                    if (child.optString("link").contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "https://gofile.io/"
                    this.quality = q
                    this.headers = mapOf("Cookie" to "accountToken=$token", "User-Agent" to EXTRACTOR_UA)
                })
            }
        } catch (e: Exception) { WitaLog.e("GoFile error: ${e.message}") }
    }
}
