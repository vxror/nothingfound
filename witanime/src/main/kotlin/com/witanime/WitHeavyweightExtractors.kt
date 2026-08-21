package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import java.net.URI
import kotlin.random.Random

object WitHeavyweightExtractors {

    suspend fun extract(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val host = try { URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return try {
            when {
                host.contains("dood") || host.contains("dstream") -> extractDood(url, referer, sourceName, callback)
                host.contains("streamtape") || host.contains("tape") -> extractStreamTape(url, referer, sourceName, callback)
                host.contains("vidguard") || host.contains("listeamed") -> extractVidGuard(url, referer, sourceName, callback)
                host.contains("voe") -> extractVoe(url, referer, sourceName, callback)
                host.contains("filemoon") || host.contains("moonplayer") -> extractFileMoon(url, referer, sourceName, callback)
                else -> universalSniffer(url, referer, sourceName, callback)
            }
        } catch (e: Exception) {
            android.util.Log.e("WitExtractors", "Error extracting $host", e)
            false
        }
    }

    private suspend fun extractDood(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val embedUrl = url.replace("/d/", "/e/").replace("/w/", "/e/")
        val html = app.get(embedUrl, referer = referer).text
        val md5Hash = Regex("""'/pass_md5/([^']+)'""").find(html)?.groupValues?.get(1) ?: return false
        val baseUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }
        val passUrl = "$baseUrl/pass_md5/$md5Hash"
        val passResponse = app.get(passUrl, referer = embedUrl).text
        val randomToken = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(62)] }.joinToString("")
        val videoUrl = "${passResponse}.$randomToken"
        if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
            callback(newExtractorLink(sourceName, "DoodStream", videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = embedUrl; this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    private suspend fun extractStreamTape(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val match = Regex("""getElementById\('norobotlink'\)\.innerHTML\s*=\s*'([^']+)'""").find(html) 
            ?: Regex("""id=["']norobotlink["'][^>]*>([^<]+)<""").find(html)
        if (match != null) {
            var link = match.groupValues[1].trim()
            if (link.startsWith("//")) link = "https:$link"
            if (!link.contains("&stream=1")) link += "&stream=1"
            callback(newExtractorLink(sourceName, "StreamTape", link, ExtractorLinkType.VIDEO) {
                this.referer = url; this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    private suspend fun extractVidGuard(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val embedUrl = if (url.contains("/d/") || url.contains("/v/")) url.replace("/d/", "/e/").replace("/v/", "/e/") else url
        val script = app.get(embedUrl, referer = referer ?: "https://vidguard.to/").document.selectFirst("script:containsData(eval)")?.data() ?: return false
        val unpacked = JsUnpacker(script).unpack() ?: script
        val streamMatch = Regex("""stream\s*:\s*["']([^"']+)["']""").find(unpacked) ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)
        if (streamMatch != null) {
            var link = streamMatch.groupValues[1]
            // VidGuard uses a custom base64-like encoding
            if (link.length % 4 != 0) link += "=".repeat(4 - link.length % 4)
            try {
                val decoded = String(Base64.decode(link, Base64.DEFAULT))
                val finalLink = if (decoded.startsWith("http")) decoded else link
                val type = if (finalLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(newExtractorLink(sourceName, "VidGuard", finalLink, type) {
                    this.referer = embedUrl; this.quality = Qualities.Unknown.value
                })
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun extractVoe(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val text = app.get(url, referer = url).text
        val link = Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""").find(text)?.value 
            ?: Regex("""file:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
        if (link != null) {
            callback(newExtractorLink(sourceName, "Voe", link, ExtractorLinkType.M3U8) {
                this.referer = url; this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    private suspend fun extractFileMoon(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val packed = JsUnpacker(html).unpack() ?: html
        val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(packed)?.groupValues?.get(1) ?: return false
        callback(newExtractorLink(sourceName, "FileMoon", m3u8, ExtractorLinkType.M3U8) {
            this.referer = url; this.quality = Qualities.Unknown.value
        })
        return true
    }

    private suspend fun universalSniffer(url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val patterns = listOf(
            Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source|src)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                val videoUrl = match.groupValues.last().replace("\\/", "/").replace("\\u0026", "&")
                if (videoUrl.length > 20 && videoUrl.startsWith("http")) {
                    val type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(sourceName, "Sniffed", videoUrl, type) {
                        this.referer = url; this.quality = Qualities.Unknown.value
                    })
                    return true
                }
            }
        }
        return false
    }
}
