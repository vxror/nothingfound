package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val EXTRACTOR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

object WitaState { @Volatile var isCasting = false }

internal val CHROME_HINTS = mapOf(
    "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Windows\""
)

internal fun hostOf(url: String): String = try {
    val u = java.net.URI(url); "${u.scheme}://${u.host}"
} catch (e: Exception) { url }

internal fun linkHost(link: String): String = try {
    java.net.URI(link).host?.lowercase() ?: ""
} catch (e: Exception) { "" }

internal fun encodeBase(v: Int, radix: Int): String {
    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if (v == 0) return "0"
    var n = v; val sb = StringBuilder()
    while (n > 0) { sb.insert(0, chars[n % radix]); n /= radix }
    return sb.toString()
}

internal fun unpackPackedJs(html: String): String? {
    val m = Regex(
        """eval\(function\(p,a,c,k,e,\w\)\{.*?\}\s*\(\s*'((?:\\'|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\'|[^'])*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    ).find(html) ?: return null
    val p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
    val a = m.groupValues[2].toIntOrNull() ?: return null
    val c = m.groupValues[3].toIntOrNull() ?: return null
    if (a < 2 || a > 62) return null
    val k = m.groupValues[4].replace("\\'", "'").replace("\\\\", "\\").split("|")
    val dict = mutableMapOf<String, String>()
    var count = c
    while (count-- > 0) {
        val key = encodeBase(count, a)
        dict[key] = if (count < k.size && k[count].isNotEmpty()) k[count] else key
    }
    return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
}

internal fun aesGcmDecrypt(b64Data: String, pageKey: String): String? = try {
    val keyBytes = MessageDigest.getInstance("SHA-256").digest(pageKey.toByteArray(Charsets.UTF_8))
    val raw = Base64.decode(b64Data.trim(), Base64.DEFAULT)
    if (raw.size <= 28) null else {
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(12, 28)
        val ct = raw.copyOfRange(28, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct + tag), Charsets.UTF_8).trim()
    }
} catch (_: Exception) { null }

internal fun isDoodLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return h.contains("dood") || h.contains("d000d") || h.contains("ds2play") || h.contains("dstore") || h.contains("dstream")
}

internal fun isStreamWishLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return listOf("wish", "swhoi", "kswplayer", "swdyu", "streamhls", "neko-stream",
        "uqloads", "strwish", "flaswish", "mwish", "dwish").any { h.contains(it) }
}

internal fun isHgCloudLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return listOf("hgcloud", "vibuxer", "hanerix", "streamhg", "huntrexus").any { h.contains(it) }
}

internal fun isMegaLink(link: String): Boolean {
    val h = linkHost(link)
    return h.contains("mega.nz") || h.contains("mega.co.nz")
}

internal fun labelQuality(label: String?): Int {
    if (label == null) return Qualities.Unknown.value
    val l = label.uppercase()
    return when {
        l.contains("2160") || l.contains("4K") || l.contains("UHD") -> Qualities.P2160.value
        l.contains("1440") || l.contains("QHD") -> Qualities.P1440.value
        l.contains("1080") || l.contains("FHD") -> Qualities.P1080.value
        l.contains("720") || l.contains("HD") -> Qualities.P720.value
        l.contains("480") || l.contains("SD") -> Qualities.P480.value
        l.contains("360") -> Qualities.P360.value
        l.contains("240") -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
}

private fun heightToQuality(h: Int): Int = when {
    h >= 2160 -> Qualities.P2160.value
    h >= 1440 -> Qualities.P1440.value
    h >= 1080 -> Qualities.P1080.value
    h >= 720 -> Qualities.P720.value
    h >= 480 -> Qualities.P480.value
    h >= 360 -> Qualities.P360.value
    h > 0 -> Qualities.P240.value
    else -> Qualities.Unknown.value
}

internal fun urlQuality(url: String): Int {
    val u = url.lowercase()
    val maxRes = Regex("""(?<!\d)(2160|1440|1080|720|480|360|240)(?!\d)""")
        .findAll(u).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
    if (maxRes != null) return heightToQuality(maxRes)
    return when {
        u.contains("4k") || u.contains("uhd") -> Qualities.P2160.value
        u.contains("fhd") -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }
}

internal fun bestQuality(url: String, label: String?): Int {
    val uq = urlQuality(url)
    return if (uq != Qualities.Unknown.value) uq else labelQuality(label)
}

internal fun qualityName(url: String, label: String?): String? = when (urlQuality(url)) {
    Qualities.P2160.value -> "2160p"
    Qualities.P1440.value -> "1440p"
    Qualities.P1080.value -> "1080p"
    Qualities.P720.value -> "720p"
    Qualities.P480.value -> "480p"
    Qualities.P360.value -> "360p"
    Qualities.P240.value -> "240p"
    else -> label?.trim()?.ifBlank { null }
}

internal fun isDirectCdnLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return h.endsWith("archive.org") || h.endsWith("dl.dropboxusercontent.com") ||
            (h.endsWith("dropbox.com") && link.contains("raw=1")) ||
            h.contains("soraplay.") || h.endsWith("okcdn.ru")
}

internal suspend fun emitDirectCdn(link: String, qLabel: String? = null, callback: (ExtractorLink) -> Unit) {
    val cleanLink = link.trimEnd('#')
    val host = linkHost(link)
    val shortHost = if (host.length > 30) host.substringAfter(".") else host
    val qName = qualityName(cleanLink, qLabel)
    callback(newExtractorLink("Direct", shortHost + (qName?.let { " $it" } ?: ""), cleanLink,
        if (cleanLink.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
        quality = bestQuality(cleanLink, qLabel)
        this.headers = mapOf("User-Agent" to EXTRACTOR_UA)
    })
}

internal suspend fun verifyM3u8(url: String, referer: String? = null): Pair<Int, Boolean>? {
    return try {
        val body = app.get(url, headers = mapOf("User-Agent" to EXTRACTOR_UA, "Referer" to (referer ?: url)) + CHROME_HINTS).text
        if (!body.contains("#EXTM3U")) return null
        val maxH = Regex("""RESOLUTION=\d+x(\d+)""").findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        val quality = if (maxH != null) heightToQuality(maxH) else when {
            body.contains("3840x2160") -> Qualities.P2160.value
            body.contains("2560x1440") -> Qualities.P1440.value
            body.contains("1920x1080") || body.contains("1920x1088") -> Qualities.P1080.value
            body.contains("1280x720") -> Qualities.P720.value
            body.contains("854x480") || body.contains("856x480") -> Qualities.P480.value
            body.contains("640x360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        quality to true
    } catch (_: Exception) { null }
}

internal suspend fun pickWorkingHls(candidates: Map<String, String>, referer: String? = null): Pair<String, Int>? {
    val ordered = listOf("hls2", "hls4", "hls3", "hls1").mapNotNull { candidates[it] }.ifEmpty { candidates.values.toList() }
    for (url in ordered) {
        val result = verifyM3u8(url, referer)
        if (result != null && result.second) return url to result.first
    }
    return ordered.firstOrNull()?.let { it to Qualities.Unknown.value }
}

internal suspend fun handleUnknownEmbed(
    url: String, referer: String?, name: String,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.8",
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: url)
        ) + CHROME_HINTS

        var page = app.get(url, headers = headers, referer = referer)
        var html = page.text
        var currentUrl = page.url
        val originalPath = try { java.net.URI(url).path ?: "" } catch (_: Exception) { "" }

        fun looksLikePlayer(h: String): Boolean =
            h.contains("vplayer") || h.contains("jwplayer") || h.contains("sources:") || h.contains("file:")

        if (html.contains("Page is loading") || html.contains("please wait") ||
            (html.length < 3000 && html.contains("/main.js"))) {
            val redirectTarget = Regex("""(?:location\.href|location\.replace|window\.location)\s*\(?\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""http-equiv=["']refresh["'][^>]*url=([^"'>]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            if (redirectTarget != null && redirectTarget.startsWith("http")) {
                try { page = app.get(redirectTarget, headers = headers, referer = currentUrl); html = page.text; currentUrl = page.url } catch (_: Exception) {}
            }
            if (!looksLikePlayer(html)) {
                val mainJs = try { app.get("${hostOf(currentUrl)}/main.js", headers = headers, referer = currentUrl).text } catch (_: Exception) { "" }
                val skip = listOf("google", "cloudflare", "yandex", "gstatic", "w3.org", "facebook", "doubleclick", "twitter", "jquery", "fontawesome", "bootstrap")
                val candidates = Regex("""https?://([a-zA-Z0-9.-]+\.[a-z]{2,})""").findAll(mainJs)
                    .map { it.groupValues[1] }.distinct()
                    .filter { d -> skip.none { d.contains(it) } && d != linkHost(currentUrl) }.take(3).toList()
                for (d in candidates) {
                    try {
                        val p2 = app.get("https://$d$originalPath", headers = headers, referer = currentUrl)
                        if (looksLikePlayer(p2.text)) { page = p2; html = p2.text; currentUrl = p2.url; break }
                    } catch (_: Exception) {}
                }
            }
        }

        val host = hostOf(currentUrl)
        val authHeaders = mapOf("Referer" to currentUrl, "Origin" to host, "User-Agent" to USER_AGENT)
        val emittedUrls = HashSet<String>()
        var found = false
        val emitOnce: (ExtractorLink) -> Unit = { l -> if (emittedUrls.add(l.url)) { callback(l); found = true } }

        if (looksLikePlayer(html)) {
            val playerScriptData = when {
                !getPacked(html).isNullOrEmpty() -> getAndUnpack(html)
                html.contains("jwplayer(\"vplayer\").setup(") -> html.substringAfter("jwplayer(\"vplayer\").setup(").substringBefore(");")
                html.contains("jwplayer('vplayer').setup(") -> html.substringAfter("jwplayer('vplayer').setup(").substringBefore(");")
                else -> html
            }
            val unpacked = unpackPackedJs(html) ?: html
            val combined = "$html\n$unpacked".replace("\\/", "/").replace("\\\"", "\"")
            val hlsUrls = Regex("""(?:file|sources|src)\s*[":]\s*["'](https?://[^"']+(?:\.m3u8|\.txt)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(combined).map { it.groupValues[1] }.distinct().take(3).toList()
            if (hlsUrls.isNotEmpty()) {
                for (m3u8Url in hlsUrls) {
                    if (m3u8Url.contains(".txt") && verifyM3u8(m3u8Url, currentUrl) == null) continue
                    M3u8Helper.generateM3u8(name, m3u8Url, currentUrl, headers = authHeaders).forEach(emitOnce)
                }
            } else if (JwPlayerHelper.extractStreamLinks(playerScriptData.orEmpty(), name, host, emitOnce, subtitleCallback, authHeaders)) {
                found = true
            }
        }

        if (!found) {
            val combined = "$html\n${unpackPackedJs(html) ?: ""}".replace("\\/", "/").replace("\\\"", "\"")
            Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").findAll(combined)
                .map { it.groupValues[1] }.distinct().take(3).forEach { m3u8 ->
                    if (verifyM3u8(m3u8, currentUrl) != null)
                        M3u8Helper.generateM3u8(name, m3u8, currentUrl, headers = authHeaders).forEach(emitOnce)
                }
        }
        if (!found) {
            Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""").findAll(html)
                .map { it.groupValues[1].trimEnd('#') }.distinct().take(3).forEach { mp4 ->
                    emitOnce(newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) { this.referer = host })
                }
        }
        if (!found) {
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8|\.txt|\.mp4"""),
                    additionalUrls = listOf(Regex("""\.m3u8|\.txt|\.mp4""")),
                    useOkhttp = false, timeout = 12_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.contains(".m3u8") || intercepted.contains(".txt") || intercepted.contains(".mp4")) {
                    if (intercepted.contains(".mp4"))
                        emitOnce(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) { this.referer = host })
                    else M3u8Helper.generateM3u8(name, intercepted, currentUrl, headers = authHeaders).forEach(emitOnce)
                }
            } catch (_: Exception) {}
        }
        if (!found) WitaLog.d("UE: nothing found for $url")
        found
    } catch (e: Exception) {
        WitaLog.e("UE error: ${e.message}")
        false
    }
}
