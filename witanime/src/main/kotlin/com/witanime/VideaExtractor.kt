package com.witanime

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class VideaExtractor : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    private val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeSrc = url.trim()
            if (iframeSrc.isBlank()) return
            val pageResp = app.get(iframeSrc, headers = mapOf("User-Agent" to EXTRACTOR_UA) + CHROME_HINTS, referer = referer).text
            val nonce = Regex("_xt\\s*=\\s*\"([^\"]+)\"").find(pageResp)?.groupValues?.get(1) ?: return

            val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
            val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""
            val resultBuilder = StringBuilder()
            for (i in 0 until 32) {
                val ch = paramL.getOrNull(i) ?: 'a'
                val idxInStupid = STUPID_KEY.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInStupid - 31)
                val safeIndex = when {
                    paramSPart.isEmpty() -> 0
                    index < 0 -> 0
                    index >= paramSPart.length -> paramSPart.length - 1
                    else -> index
                }
                resultBuilder.append(paramSPart.getOrNull(safeIndex) ?: 'a')
            }
            val result = resultBuilder.toString()
            val seed = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
            val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
            val rc4KeyPart = if (result.length > 16) result.substring(16) else ""

            val videoId = Uri.parse(iframeSrc).getQueryParameter("v")
                ?: Regex("[?&]v=([^&]+)").find(iframeSrc)?.groupValues?.get(1) ?: return

            val xmlResponse = app.get("https://videa.hu/player/xml?platform=desktop",
                params = mapOf("_s" to seed, "_t" to paramT, "v" to videoId),
                headers = mapOf("Referer" to iframeSrc, "Origin" to "https://videa.hu"))
            val body = xmlResponse.text
            val xVideaXs = xmlResponse.headers["x-videa-xs"] ?: ""

            val finalDoc = if (body.trimStart().startsWith("<?xml")) {
                val parsed = Jsoup.parse(body, "", Parser.xmlParser())
                if (parsed.selectFirst("error") != null) return
                parsed
            } else {
                try {
                    val decoded = Base64.decode(body.trim(), Base64.DEFAULT)
                    Jsoup.parse(rc4Decrypt(decoded, rc4KeyPart + seed + xVideaXs), "", Parser.xmlParser())
                } catch (_: Exception) { return }
            }

            val collected = mutableListOf<Triple<Int, String, String>>()
            for (src in finalDoc.select("video_source")) {
                val name = src.attr("name")
                val videoUrlPart = src.text().trim()
                val exp = src.attr("exp")
                val md5 = finalDoc.getElementsByTag("hash_value_$name").first()?.text()?.trim() ?: continue
                val finalUrl = if (videoUrlPart.startsWith("http")) "$videoUrlPart?md5=$md5&expires=$exp"
                else "https:$videoUrlPart?md5=$md5&expires=$exp"
                val q = getQualityFromName(name).takeIf { it > 0 } ?: Qualities.Unknown.value
                collected.add(Triple(q, name, finalUrl))
            }
            val sorted = collected.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }
                .thenByDescending { Regex("(\\d{3,4})").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0 })

            withContext(Dispatchers.Main) {
                for ((qualityVal, name, finalUrl) in sorted) {
                    try {
                        callback(newExtractorLink(this@VideaExtractor.name, "$name (Videa)", finalUrl,
                            if (finalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = referer ?: iframeSrc
                            this.quality = qualityVal
                        })
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { WitaLog.e("Videa error: ${e.message}") }
    }

    private fun rc4Decrypt(data: ByteArray, key: String): String = try {
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "RC4"))
        String(cipher.update(data) ?: cipher.doFinal(data), StandardCharsets.UTF_8)
    } catch (_: Exception) { "" }
}
