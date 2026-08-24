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
    val urlQ = when {
        Regex("1080").containsMatchIn(link) -> Qualities.P1080.value
        Regex("720").containsMatchIn(link) -> Qualities.P720.value
        Regex("480").containsMatchIn(link) -> Qualities.P480.value
        Regex("FHD", RegexOption.IGNORE_CASE).containsMatchIn(link) -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }
    val q = if (urlQ != Qualities.Unknown.value) urlQ else labelQuality(qLabel)
    val host = linkHost(link)
    val shortHost = if (host.length > 30) host.substringAfter(".") else host
    callback(newExtractorLink("Direct", shortHost, link,
        if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
        quality = q
    })
}

/**
 * ⚡ UNIVERSAL EMBED HANDLER — works for ANY embed page from witanime:
 *   1. Follows loading-page redirects (hgcloud.to → hanerix.com)
 *   2. JWPlayer + packed JS → JwPlayerHelper extraction
 *   3. Raw m3u8/mp4 in page → emit directly
 *   4. WebView m3u8/txt/mp4 intercept fallback
 */
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

        // ═══ 1) LOADING PAGE DETECTION: follow redirect ═══
        if (html.contains("Page is loading") || html.contains("please wait") ||
            (html.length < 3000 && html.contains("/main.js"))) {
            println("WitAnimeDebug: UniversalEmbed: loading page detected")

            val redirectTarget = Regex("""(?:location\.href|window\.location)\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""http-equiv=["']refresh["'][^>]*url=([^"'>]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?: Regex(""""(https?://[^"]+/e/[^"]+)"""").find(html)?.groupValues?.get(1)

            if (redirectTarget != null && redirectTarget.startsWith("http")) {
                println("WitAnimeDebug: UniversalEmbed: redirect -> $redirectTarget")
                page = app.get(redirectTarget, headers = headers, referer = currentUrl)
                html = page.text
                currentUrl = page.url
            } else {
                val mainJsUrl = "${hostOf(currentUrl)}/main.js"
                val mainJs = try { app.get(mainJsUrl, headers = headers).text } catch (_: Exception) { "" }
                val jsRedirect = Regex("""(?:location\.href|window\.location)\s*=\s*["']([^"']+)["']""").find(mainJs)?.groupValues?.get(1)
                    ?: Regex(""""(https?://[^"]+)"""").find(mainJs)?.groupValues?.get(1)

                if (jsRedirect != null && jsRedirect.startsWith("http")) {
                    println("WitAnimeDebug: UniversalEmbed: js redirect -> $jsRedirect")
                    page = app.get(jsRedirect, headers = headers, referer = currentUrl)
                    html = page.text
                    currentUrl = page.url
                }
            }
        }

        println("WitAnimeDebug: UniversalEmbed: final host=${linkHost(currentUrl)} len=${html.length}")

        val host = hostOf(currentUrl)
        val authHeaders = mapOf(
            "Referer" to "$host/",
            "Origin" to host,
            "User-Agent" to USER_AGENT
        )
        var found = false

        // ═══ 2) JWPLAYER + PACKED JS extraction ═══
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
            println("WitAnimeDebug: UniversalEmbed: JWPlayer extraction SUCCESS")
        }

        // ═══ 3) RAW m3u8 in page or unpacked JS ═══
        if (!found) {
            val combined = "$html\n${unpackPackedJs(html) ?: ""}".replace("\\/", "/").replace("\\\"", "\"")
            val m3u8Links = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").findAll(combined)
                .map { it.groupValues[1] }.distinct().toList()
            if (m3u8Links.isNotEmpty()) {
                m3u8Links.forEach { m3u8 ->
                    println("WitAnimeDebug: UniversalEmbed: m3u8 -> ${m3u8.take(90)}")
                    M3u8Helper.generateM3u8(name, m3u8, host, headers = authHeaders).forEach(callback)
                }
                found = true
            }
        }
        if (!found) {
            val mp4Links = Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""").findAll(html)
                .map { it.groupValues[1] }.distinct().toList()
            if (mp4Links.isNotEmpty()) {
                mp4Links.forEach { mp4 ->
                    println("WitAnimeDebug: UniversalEmbed: mp4 -> ${mp4.take(90)}")
                    callback(newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) { referer = host })
                }
                found = true
            }
        }

        // ═══ 4) WEBVIEW fallback ═══
        if (!found) {
            println("WitAnimeDebug: UniversalEmbed: trying WebView intercept")
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""txt|m3u8|mp4"""),
                    additionalUrls = listOf(Regex("""txt|m3u8|mp4""")),
                    useOkhttp = false,
                    timeout = 15_000L
                )
                val intercepted = app.get(url, referer = referer, interceptor = resolver).url
                if (intercepted.isNotEmpty() && (intercepted.contains("m3u8") || intercepted.contains(".txt") || intercepted.contains(".mp4"))) {
                    println("WitAnimeDebug: UniversalEmbed: WebView intercepted -> ${intercepted.take(90)}")
                    if (intercepted.contains(".m3u8") || intercepted.contains(".txt")) {
                        M3u8Helper.generateM3u8(name, intercepted, hostOf(url), headers = authHeaders).forEach(callback)
                    } else {
                        callback(newExtractorLink(name, name, intercepted, ExtractorLinkType.VIDEO) { referer = hostOf(url) })
                    }
                    found = true
                }
            } catch (_: Exception) {}
        }

        if (!found) println("WitAnimeDebug: UniversalEmbed: nothing found for $url")
        found
    } catch (e: Exception) {
        println("WitAnimeDebug: UniversalEmbed error: ${e.message}")
        false
    }
}
