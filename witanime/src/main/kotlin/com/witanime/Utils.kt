package com.witanime

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.JwPlayerHelper
import com.lagradost.cloudstream3.network.WebViewResolver

internal const val EXTRACTOR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    ).find(html) ?: return null
    val p = m.groupValues[1]
    val a = m.groupValues[2].toIntOrNull() ?: return null
    val c = m.groupValues[3].toIntOrNull() ?: return null
    if (a < 2 || a > 62) return null
    val k = m.groupValues[4].split("|")
    val dict = mutableMapOf<String, String>()
    var count = c
    while (count-- > 0) {
        val key = encodeBase(count, a)
        dict[key] = if (count < k.size && k[count].isNotEmpty()) k[count] else key
    }
    return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
}

internal fun isDoodLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return h.contains("dood") || h.contains("d000d") || h.contains("ds2play") ||
            h.contains("dstore") || h.contains("dstream")
}

internal fun isStreamWishLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return listOf("wish", "swhoi", "kswplayer", "swdyu", "streamhls", "neko-stream",
        "uqloads", "strwish", "flaswish", "mwish", "dwish", "hgcloud", "hanerix",
        "streamhg", "huntrexus").any { h.contains(it) }
}

internal fun isMegaLink(link: String): Boolean {
    val h = linkHost(link)
    return h.contains("mega.nz") || h.contains("mega.co.nz")
}

internal fun labelQuality(label: String?): Int = when {
    label == null -> Qualities.Unknown.value
    label.contains("1080") || label.contains("FHD", true) -> Qualities.P1080.value
    label.contains("720") || label.contains("HD", true) -> Qualities.P720.value
    label.contains("480") -> Qualities.P480.value
    label.contains("360") -> Qualities.P360.value
    else -> Qualities.Unknown.value
}

internal fun urlQuality(url: String): Int = when {
    Regex("""[._\- ]1080[._\- ]|[._\- ]2160[._\- ]|1080p|2160p|FHD""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Qualities.P1080.value
    Regex("""[._\- ]720[._\- ]|720p""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Qualities.P720.value
    Regex("""[._\- ]480[._\- ]|480p""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Qualities.P480.value
    Regex("""[._\- ]360[._\- ]|360p""", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Qualities.P360.value
    else -> Qualities.Unknown.value
}

internal fun bestQuality(url: String, label: String?): Int {
    val uq = urlQuality(url)
    if (uq != Qualities.Unknown.value) return uq
    return labelQuality(label)
}

internal fun qualityName(url: String, label: String?): String? {
    val uq = urlQuality(url)
    val fromUrl = when (uq) {
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        else -> null
    }
    if (fromUrl != null) return fromUrl
    if (!label.isNullOrBlank()) return label.trim()
    return null
}

internal fun isDirectCdnLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return h.endsWith("archive.org") ||
            h.endsWith("dl.dropboxusercontent.com") ||
            (h.endsWith("dropbox.com") && link.contains("raw=1")) ||
            h.contains("soraplay.") ||
            h.endsWith("okcdn.ru")
}

internal suspend fun emitDirectCdn(link: String, qLabel: String? = null, callback: (ExtractorLink) -> Unit) {
    val cleanLink = link.trimEnd('#')
    val host = linkHost(link)
    val shortHost = if (host.length > 30) host.substringAfter(".") else host
    val qName = qualityName(cleanLink, qLabel)
    callback(newExtractorLink("Direct", shortHost + (qName?.let { " $it" } ?: ""), cleanLink,
        if (cleanLink.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
        quality = bestQuality(cleanLink, qLabel)
    })
}

internal suspend fun verifyM3u8(url: String, referer: String? = null): Pair<Int, Boolean>? {
    return try {
        val body = app.get(url, headers = mapOf(
            "User-Agent" to EXTRACTOR_UA,
            "Referer" to (referer ?: url)
        )).text
        if (!body.contains("#EXTM3U")) return null

        val quality = when {
            body.contains("1920x1080") || body.contains("1920x1088") -> Qualities.P1080.value
            body.contains("1280x720") -> Qualities.P720.value
            body.contains("856x480") || body.contains("854x480") || body.contains("640x360") -> Qualities.P480.value
            body.contains("3840x2160") || body.contains("2560x1440") -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
        quality to true
    } catch (_: Exception) { null }
}

internal suspend fun pickWorkingHls(
    candidates: Map<String, String>,
    referer: String? = null
): Pair<String, Int>? {
    val ordered = listOf("hls2", "hls4", "hls3", "hls1")
        .mapNotNull { candidates[it] }
        .ifEmpty { candidates.values.toList() }

    for (url in ordered) {
        val result = verifyM3u8(url, referer)
        if (result != null && result.second) {
            println("WitAnimeDebug: verifyM3u8 OK quality=${result.first} -> ${url.take(80)}")
            return url to result.first
        }
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
        )

        var page = app.get(url, headers = headers, referer = referer)
        var html = page.text
        var currentUrl = page.url
        val originalPath = try { java.net.URI(url).path ?: "" } catch (_: Exception) { "" }

        fun looksLikePlayer(h: String): Boolean =
            h.contains("vplayer") || h.contains("jwplayer") || h.contains("sources:") || h.contains("file:")

        if (html.contains("Page is loading") || html.contains("please wait") ||
            (html.length < 3000 && html.contains("/main.js"))) {
            println("WitAnimeDebug: UE: loading page detected (${linkHost(currentUrl)})")

            val redirectTarget = Regex("""(?:location\.href|location\.replace|window\.location)\s*\(?\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""http-equiv=["']refresh["'][^>]*url=([^"'>]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

            if (redirectTarget != null && redirectTarget.startsWith("http")) {
                println("WitAnimeDebug: UE: html redirect -> $redirectTarget")
                try {
                    page = app.get(redirectTarget, headers = headers, referer = currentUrl)
                    html = page.text; currentUrl = page.url
                } catch (_: Exception) {}
            }

            if (!looksLikePlayer(html)) {
                val mainJsUrl = "${hostOf(currentUrl)}/main.js"
                val mainJs = try { app.get(mainJsUrl, headers = headers, referer = currentUrl).text } catch (_: Exception) { "" }
                val skip = listOf("google", "cloudflare", "yandex", "gstatic", "w3.org", "facebook",
                    "doubleclick", "twitter", "jquery", "fontawesome", "bootstrap")
                val candidates = Regex("""https?://([a-zA-Z0-9.-]+\.[a-z]{2,})""").findAll(mainJs)
                    .map { it.groupValues[1] }.distinct()
                    .filter { d -> skip.none { d.contains(it) } && d != linkHost(currentUrl) }
                    .take(3).toList()
                println("WitAnimeDebug: UE: main.js candidates=$candidates")

                for (d in candidates) {
                    val tryUrl = "https://$d$originalPath"
                    try {
                        val p2 = app.get(tryUrl, headers = headers, referer = currentUrl)
                        if (looksLikePlayer(p2.text)) {
                            println("WitAnimeDebug: UE: player found at $d")
                            page = p2; html = p2.text; currentUrl = p2.url
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        println("WitAnimeDebug: UE: final host=${linkHost(currentUrl)} len=${html.length} player=${looksLikePlayer(html)}")

        val host = hostOf(currentUrl)
        val authHeaders = mapOf(
            "Referer" to currentUrl,
            "Origin" to host,
            "User-Agent" to USER_AGENT
        )
        var found = false

        if (looksLikePlayer(html)) {
            val playerScriptData = when {
                !getPacked(html).isNullOrEmpty() -> getAndUnpack(html)
                html.contains("jwplayer(\"vplayer\").setup(") ->
                    html.substringAfter("jwplayer(\"vplayer\").setup(").substringBefore(");")
                html.contains("jwplayer('vplayer').setup(") ->
                    html.substringAfter("jwplayer('vplayer').setup(").substringBefore(");")
                else -> html
            }
            if (JwPlayerHelper.extractStreamLinks(playerScriptData.orEmpty(), name, host, callback, subtitleCallback, authHeaders)) {
                found = true
                println("WitAnimeDebug: UE: JWPlayer extraction SUCCESS")
            }
        }

        if (!found) {
            val combined = "$html\n${unpackPackedJs(html) ?: ""}".replace("\\/", "/").replace("\\\"", "\"")
            val m3u8Links = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").findAll(combined)
                .map { it.groupValues[1] }.distinct().toList()
            if (m3u8Links.isNotEmpty()) {
                m3u8Links.forEach { m3u8 ->
                    println("WitAnimeDebug: UE: m3u8 candidate -> ${m3u8.take(90)}")
                    val verified = verifyM3u8(m3u8, currentUrl)
                    if (verified != null) {
                        println("WitAnimeDebug: UE: m3u8 VERIFIED quality=${verified.first}")
                        M3u8Helper.generateM3u8(name, m3u8, currentUrl, headers = authHeaders).forEach(callback)
                        found = true
                    }
                }
            }
        }
        if (!found) {
            val mp4Links = Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""").findAll(html)
                .map { it.groupValues[1] }.distinct().toList()
            if (mp4Links.isNotEmpty()) {
                mp4Links.forEach { mp4 ->
                    println("WitAnimeDebug: UE: mp4 -> ${mp4.take(90)}")
                    callback(newExtractorLink(name, name, mp4.trimEnd('#'), ExtractorLinkType.VIDEO) { this.referer = host })
                }
                found = true
            }
        }

        if (!found) {
            println("WitAnimeDebug: UE: trying WebView intercept")
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""\.m3u8|\.txt|\.mp4"""),
                    additionalUrls = listOf(Regex("""\.m3u8|\.txt|\.mp4""")),
                    useOkhttp = false,
                    timeout = 8_000L   // ⚡ REDUCED from 12s
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.isNotEmpty() && (intercepted.contains(".m3u8") || intercepted.contains(".txt") || intercepted.contains(".mp4"))) {
                    println("WitAnimeDebug: UE: WebView intercepted -> ${intercepted.take(90)}")
                    if (intercepted.contains(".mp4")) {
                        callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) { this.referer = host })
                    } else {
                        M3u8Helper.generateM3u8(name, intercepted, currentUrl, headers = authHeaders).forEach(callback)
                    }
                    found = true
                }
            } catch (_: Exception) {}
        }

        if (!found) println("WitAnimeDebug: UE: nothing found for $url")
        found
    } catch (e: Exception) {
        println("WitAnimeDebug: UE error: ${e.message}")
        false
    }
}
