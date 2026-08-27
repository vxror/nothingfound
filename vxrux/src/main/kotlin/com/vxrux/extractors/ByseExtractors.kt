package com.vxrux.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class ByseBase : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://byse.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val base = try { java.net.URI(url).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { return }
            val code = url.trimEnd('/').substringAfterLast('/')

            // Step 1: details API
            val details = JSONObject(app.get("$base/api/videos/$code/embed/details", referer = url).text)
            val embedFrameUrl = details.optString("embed_frame_url")
            if (embedFrameUrl.isBlank()) return

            // Step 2: playback API from embed frame
            val embedBase = try { java.net.URI(embedFrameUrl).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { return }
            val code2 = embedFrameUrl.trimEnd('/').substringAfterLast('/')
            val playbackResp = app.get(
                "$embedBase/api/videos/$code2/embed/playback",
                headers = mapOf(
                    "accept" to "*/*",
                    "accept-language" to "en-US,en;q=0.5",
                    "priority" to "u=1, i",
                    "referer" to embedFrameUrl,
                    "x-embed-parent" to url,
                )
            ).text
            val playback = JSONObject(playbackResp).optJSONObject("playback") ?: return

            // Step 3: AES-GCM decrypt using our helper
            val keyParts = playback.optJSONArray("key_parts") ?: return
            if (keyParts.length() < 2) return
            val keyBytes = CryptoHelpers.b64UrlDecode(keyParts.getString(0)) + CryptoHelpers.b64UrlDecode(keyParts.getString(1))
            val ivBytes = CryptoHelpers.b64UrlDecode(playback.optString("iv"))
            val cipherBytes = CryptoHelpers.b64UrlDecode(playback.optString("payload"))

            val decrypted = CryptoHelpers.aesGcmDecrypt(keyBytes, ivBytes, cipherBytes) ?: return

            val sources = JSONObject(decrypted).optJSONArray("sources") ?: return
            val streamUrl = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
                .firstOrNull()?.optString("url") ?: return

            M3u8Helper.generateM3u8(name, streamUrl, mainUrl, headers = mapOf("Referer" to base)).forEach(callback)
        } catch (_: Exception) {}
    }
}

class Bysezejataos : ByseBase() { override val name = "Bysezejataos"; override val mainUrl = "https://bysezejataos.com" }
class ByseBuho : ByseBase() { override val name = "ByseBuho"; override val mainUrl = "https://bysebuho.com" }
class ByseVepoin : ByseBase() { override val name = "ByseVepoin"; override val mainUrl = "https://bysevepoin.com" }
class ByseQekaho : ByseBase() { override val name = "ByseQekaho"; override val mainUrl = "https://byseqekaho.com" }
