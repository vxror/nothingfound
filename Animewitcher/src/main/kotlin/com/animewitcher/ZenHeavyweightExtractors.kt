package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object ZenHeavyweightExtractors {
    
    // 🆕 UNIVERSAL DIRECT LINK DETECTOR (Catches unknown hosts)
    private suspend fun extractDirectLink(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            val html = app.get(url, headers = headers, referer = referer, allowRedirects = true).text
            val patterns = listOf(
                Regex("""(https?://[^\s"'<>]+\.(?:mp4|mkv|avi|webm|m3u8)[^\s"'<>]*)""", RegexOption.IGNORE_CASE),
                Regex("""(?:file|source|src)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            )
            val foundLinks = mutableSetOf<String>()
            for (pattern in patterns) {
                pattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1].replace("\\/", "/")
                    if (videoUrl.length > 20 && !videoUrl.contains(".css") && !videoUrl.contains(".js")) foundLinks.add(videoUrl)
                }
            }
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            if (packed != null) {
                val decoded = ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
                patterns.forEach { pattern ->
                    pattern.findAll(decoded).forEach { match ->
                        val videoUrl = match.groupValues[1].replace("\\/", "/")
                        if (videoUrl.length > 20 && !videoUrl.contains(".css") && !videoUrl.contains(".js")) foundLinks.add(videoUrl)
                    }
                }
            }
            if (foundLinks.isNotEmpty()) {
                for (link in foundLinks) {
                    val type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(newExtractorLink(source = sourceName, name = "$sourceName Direct", url = link, type = type) {
                        this.referer = url; this.quality = quality; this.headers = headers
                    })
                }
                return true
            }
            false
        } catch (e: Exception) { false }
    }

    // 🆕 NEW EXTRACTORS
    private suspend fun extractStreamSB(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.groupValues?.get(1) ?: return false
            ZenDeliveryEngine.deliverSmartLink(sourceName, "StreamSB", m3u8.replace("\\/", "/"), referer, quality, callback); true
        } catch (e: Exception) { false }
    }

    private suspend fun extractDoodStream(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val embedUrl = url.replace("/d/", "/e/").replace("/w/", "/e/")
            val html = app.get(embedUrl, referer = referer).text
            val md5Hash = Regex("""'/pass_md5/([^']+)'""").find(html)?.groupValues?.get(1) ?: return false
            val baseUrl = URI(embedUrl).let { "${it.scheme}://${it.host}" }
            val passUrl = "$baseUrl/pass_md5/$md5Hash"
            val passResponse = app.get(passUrl, referer = embedUrl).text
            val randomToken = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(62)] }.joinToString("")
            val videoUrl = "${passResponse}.$randomToken"
            if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                ZenDeliveryEngine.deliverSmartLink(sourceName, "DoodStream", videoUrl, embedUrl, quality, callback); true
            } else false
        } catch (e: Exception) { false }
    }

    private suspend fun extractOkRu(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val videos = Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""").findAll(html)
            var found = false
            videos.forEach { match ->
                val qualityName = match.groupValues[1]
                val videoUrl = match.groupValues[2].replace("\\/", "/")
                if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8")) {
                    ZenDeliveryEngine.deliverSmartLink(sourceName, "OK.ru $qualityName", videoUrl, referer, quality, callback)
                    found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }

    private suspend fun extractUqload(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val videoUrl = Regex("""sources\s*:\s*\[\s*["']([^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1) ?: return false
            ZenDeliveryEngine.deliverSmartLink(sourceName, "Uqload", videoUrl.replace("\\/", "/"), referer, quality, callback); true
        } catch (e: Exception) { false }
    }

    private suspend fun extractFileMoon(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            val text = if (packed != null) ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third) else html
            val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(text)?.groupValues?.get(1) ?: return false
            ZenDeliveryEngine.deliverSmartLink(sourceName, "FileMoon", m3u8, referer, quality, callback); true
        } catch (e: Exception) { false }
    }

    private suspend fun extractStreamWish(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1) ?: return false
            ZenDeliveryEngine.deliverSmartLink(sourceName, "StreamWish", m3u8, referer, quality, callback); true
        } catch (e: Exception) { false }
    }

    private suspend fun extractVidhide(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, referer = referer).text
            val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html)
            val text = if (packed != null) ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third) else html
            val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(text)?.groupValues?.get(1) ?: return false
            ZenDeliveryEngine.deliverSmartLink(sourceName, "Vidhide", m3u8, referer, quality, callback); true
        } catch (e: Exception) { false }
    }

    suspend fun tryExtract(host: String, url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            when {
                host.contains("dailymotion") -> extractDailymotion(url, sourceName, quality, callback)
                host.contains("voe.sx") || host.contains("voe.") -> extractVoe(url, sourceName, quality, callback)
                host.contains("videa.hu") -> extractVidea(url, referer, sourceName, quality, callback)
                host.contains("vk.com") || host.contains("vkvideo.ru") || host.contains("my.mail.ru") -> extractVKAndMailRu(url, referer, sourceName, quality, callback)
                host.contains("vidmoly") -> extractVidmoly(url, referer, sourceName, quality, callback)
                host.contains("luluvid") || host.contains("luluvdo") -> extractLuluvid(url, referer, sourceName, quality, callback)
                host.contains("hgcloud") || host.contains("streamhg") -> extractStreamHG(url, referer, sourceName, quality, callback)
                host.contains("vidguard") || host.contains("listeamed") -> extractVidguard(url, referer, sourceName, quality, callback)
                // 🆕 NEW HOSTS
                host.contains("streamsb") || host.contains("sbplay") -> extractStreamSB(url, referer, sourceName, quality, callback)
                host.contains("dood") || host.contains("dstream") -> extractDoodStream(url, referer, sourceName, quality, callback)
                host.contains("ok.ru") || host.contains("odnoklassniki") -> extractOkRu(url, referer, sourceName, quality, callback)
                host.contains("uqload") -> extractUqload(url, referer, sourceName, quality, callback)
                host.contains("filemoon") || host.contains("moonplayer") -> extractFileMoon(url, referer, sourceName, quality, callback)
                host.contains("streamwish") || host.contains("wishfast") -> extractStreamWish(url, referer, sourceName, quality, callback)
                host.contains("vidhide") || host.contains("vidhidepro") -> extractVidhide(url, referer, sourceName, quality, callback)
                else -> extractDirectLink(url, referer, sourceName, quality, callback)
            }
        } catch (e: Exception) { false }
    }

    private suspend fun extractDailymotion(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val idRegex = Regex("""[?&]video=([^&]+)|/video/([a-zA-Z0-9]+)"""); val match = idRegex.find(url) ?: return false
        val id = match.groupValues[1].ifEmpty { match.groupValues[2] }; if (id.isBlank()) return false
        val meta = JSONObject(app.get("https://www.dailymotion.com/player/metadata/video/$id", referer = "https://www.dailymotion.com/embed/video/$id").text)
        if (meta.has("error")) return false
        val auto = meta.optJSONObject("qualities")?.optJSONArray("auto") ?: return false
        for (i in 0 until auto.length()) {
            val videoUrl = auto.optJSONObject(i)?.optString("url")
            if (!videoUrl.isNullOrBlank() && videoUrl.contains(".m3u8")) { ZenDeliveryEngine.deliverSmartLink(sourceName, "Dailymotion", videoUrl, null, quality, callback); return true }
        }
        return false
    }

    private suspend fun extractVoe(url: String, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val text = app.get(url, referer = url).text
        val link = Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""").find(text)?.value ?: Regex("""file:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
        if (link != null) { ZenDeliveryEngine.deliverSmartLink(sourceName, "Voe", link, null, quality, callback); return true }
        return false
    }

    private suspend fun extractVidea(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"
        val iframeSrc = url.trim(); val pageResp = app.get(iframeSrc).text
        val nonce = Regex("""_xt\s*=\s*"([^"]+)"""").find(pageResp)?.groupValues?.get(1) ?: return false
        val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
        val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""
        val resultBuilder = StringBuilder()
        for (i in 0 until 32) {
            val ch = paramL.getOrNull(i) ?: 'a'; val idxInStupid = STUPID_KEY.indexOf(ch).takeIf { it >= 0 } ?: 0
            val index = i - (idxInStupid - 31)
            val safeIndex = when { paramSPart.isEmpty() -> 0; index < 0 -> 0; index >= paramSPart.length -> paramSPart.length - 1; else -> index }
            resultBuilder.append(paramSPart.getOrNull(safeIndex) ?: 'a')
        }
        val result = resultBuilder.toString()
        val seed = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
        val rc4KeyPart = if (result.length > 16) result.substring(16) else ""
        val videoId = try { URI(iframeSrc).query?.split("&")?.find { it.startsWith("v=") }?.substringAfter("=") } catch (e: Exception) { null } ?: return false
        val xmlResponse = app.get("https://videa.hu/player/xml?platform=desktop&_s=$seed&_t=$paramT&v=$videoId", headers = mapOf("Referer" to iframeSrc, "Origin" to "https://videa.hu"))
        val xVideaXsHeader = xmlResponse.headers["x-videa-xs"] ?: ""
        val finalDoc = if (xmlResponse.text.trimStart().startsWith("<?xml")) Jsoup.parse(xmlResponse.text, "", Parser.xmlParser()) else {
            val decoded = Base64.decode(xmlResponse.text.trim(), Base64.DEFAULT)
            val spec = SecretKeySpec((rc4KeyPart + seed + xVideaXsHeader).toByteArray(StandardCharsets.UTF_8), "RC4")
            val cipher = Cipher.getInstance("RC4"); cipher.init(Cipher.DECRYPT_MODE, spec)
            Jsoup.parse(String(cipher.update(decoded) ?: cipher.doFinal(decoded), StandardCharsets.UTF_8), "", Parser.xmlParser())
        }
        val videoSources = finalDoc.select("video_source"); if (videoSources.isEmpty()) return false
        for (src in videoSources) {
            val name = src.attr("name"); val videoUrlPart = src.text().trim(); val exp = src.attr("exp")
            val md5 = finalDoc.getElementsByTag("hash_value_$name").first()?.text()?.trim() ?: continue
            val finalUrl = if (videoUrlPart.startsWith("http")) "$videoUrlPart?md5=$md5&expires=$exp" else "https:$videoUrlPart?md5=$md5&expires=$exp"
            ZenDeliveryEngine.deliverSmartLink(sourceName, "Videa $name", finalUrl, iframeSrc, quality, callback)
        }
        return true
    }

    private suspend fun extractVKAndMailRu(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        if (url.contains("my.mail.ru")) {
            val vidId = url.substringAfter("video/embed/").trim(); if (vidId.isBlank()) return false
            val videoReq = app.get("https://my.mail.ru/+/video/meta/$vidId", referer = url)
            val videoKey = videoReq.cookies["video_key"]?.toString().orEmpty()
            val videos = JSONObject(videoReq.text).optJSONArray("videos") ?: return false
            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i); var videoUrl = video.optString("url")
                if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
                if (videoKey.isNotBlank()) videoUrl += (if (videoUrl.contains("?")) "&" else "?") + "video_key=$videoKey"
                ZenDeliveryEngine.deliverSmartLink(sourceName, "MailRu", videoUrl, url, quality, callback)
            }
            return true
        }
        val headers = mapOf("Referer" to (referer ?: "https://vk.com/"), "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Sec-Fetch-Dest" to "iframe", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "cross-site")
        val html = app.get(url, headers = headers).text; if (html.length < 500) return false
        val unescape = { s: String -> s.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&") }
        Regex("""\\?"hls\\?":\\?"(https?:(?:\\/|[^"\\])+)""").find(html)?.groupValues?.get(1)?.let { raw ->
            val adaptiveUrl = ZenAdaptivePicker.selectBestVariant(unescape(raw), headers) ?: unescape(raw)
            ZenDeliveryEngine.deliverSmartLink(sourceName, "VK HLS", adaptiveUrl, url, quality, callback); return true
        }
        Regex("""\\?"url(\d{3,4})\\?":\\?"(https?:(?:\\/|[^"\\])+)""").findAll(html).forEach { m ->
            val q = m.groupValues[1].toIntOrNull() ?: 0
            ZenDeliveryEngine.deliverSmartLink(sourceName, "VK ${q}p", unescape(m.groupValues[2]), url, q, callback)
        }
        return true
    }

    private suspend fun extractVidmoly(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val script = app.get(url, referer = referer).document.select("script").find { it.data().contains("sources:") }?.data() ?: return false
        val match = Regex("""file\s*:\s*["'](http[^"']+\.m3u8[^"']*)["']""").find(script) ?: return false
        ZenDeliveryEngine.deliverSmartLink(sourceName, "Vidmoly", match.groupValues[1], referer, quality, callback); return true
    }

    private suspend fun extractLuluvid(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer ?: url).document.outerHtml()
        val packed = ZenCryptoAndObfuscation.findPackedJsInPage(html) ?: return false
        val decoded = ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
        val videoUrl = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(decoded)?.groupValues?.get(1) ?: return false
        ZenDeliveryEngine.deliverSmartLink(sourceName, "Luluvid", videoUrl.replace("\\/", "/"), referer, quality, callback); return true
    }

    private suspend fun extractStreamHG(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val text = app.get(url, referer = referer).text; val packed = ZenCryptoAndObfuscation.findPackedJsInPage(text) ?: return false
        val unpacked = ZenCryptoAndObfuscation.decodePackedJs(packed.first, packed.second, packed.third)
        val m3u8 = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(unpacked)?.value ?: return false
        ZenDeliveryEngine.deliverSmartLink(sourceName, "StreamHG", m3u8, referer, quality, callback); return true
    }

    private suspend fun extractVidguard(url: String, referer: String?, sourceName: String, quality: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val embedUrl = if (url.contains("/d/") || url.contains("/v/")) url.replace("/d/", "/e/").replace("/v/", "/e/") else url
        val script = app.get(embedUrl, referer = referer ?: "https://vidguard.to/").document.selectFirst("script:containsData(eval)")?.data() ?: return false
        val streamMatch = Regex("""stream\s*:\s*["']([^"']+)["']""").find(script)
        if (streamMatch != null) { ZenDeliveryEngine.deliverSmartLink(sourceName, "Vidguard", ZenCryptoAndObfuscation.sigDecode(streamMatch.groupValues[1]), referer, quality, callback); return true }
        return false
    }
}
