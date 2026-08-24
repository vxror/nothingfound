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

/** base encoder supporting radix up to 62 (Int.toString(radix) maxes at 36) */
internal fun encodeBase(v: Int, radix: Int): String {
    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if (v == 0) return "0"
    var n = v; val sb = StringBuilder()
    while (n > 0) { sb.insert(0, chars[n % radix]); n /= radix }
    return sb.toString()
}

/** p,a,c,k,e,d unpacker — radix-safe */
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
        "uqloads", "strwish", "flaswish", "mwish", "dwish").any { h.contains(it) }
}

internal fun isMegaLink(link: String): Boolean {
    val h = linkHost(link)
    return h.contains("mega.nz") || h.contains("mega.co.nz")
}

/** quality from a label like "HD", "FHD", "1080p" */
internal fun labelQuality(label: String?): Int = when {
    label == null -> Qualities.Unknown.value
    label.contains("1080") || label.contains("FHD", true) -> Qualities.P1080.value
    label.contains("720") || label.contains("HD", true) -> Qualities.P720.value
    label.contains("480") -> Qualities.P480.value
    label.contains("360") -> Qualities.P360.value
    else -> Qualities.Unknown.value
}

/** direct file CDNs that need no extraction — just emit */
internal fun isDirectCdnLink(link: String): Boolean {
    val h = linkHost(link)
    if (h.isBlank()) return false
    return h.endsWith("archive.org") ||
            h.endsWith("dl.dropboxusercontent.com") ||
            (h.endsWith("dropbox.com") && link.contains("raw=1")) ||
            h.endsWith("soraplay.xyz") ||
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
    callback(newExtractorLink("Direct", linkHost(link), link,
        if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
        quality = q
    })
}

/** ⚡ wildcard streamwish — EXACT replica of the built-in extractor, for UNREGISTERED wish domains */
internal suspend fun wishFallback(
    url: String, referer: String?, name: String,
    subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        val host = hostOf(url)
        val embed = when {
            url.contains("/f/") -> "$host/${url.substringAfter("/f/")}"
            url.contains("/e/") -> "$host/${url.substringAfter("/e/")}"
            else -> url
        }
        val headers = mapOf(
            "Accept" to "*/*", "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "cross-site",
            "Referer" to "$host/", "Origin" to "$host/",
            "User-Agent" to USER_AGENT
        )
        val page = app.get(embed, referer = referer)
        val data = when {
            !getPacked(page.text).isNullOrEmpty() -> getAndUnpack(page.text)
            page.document.select("script").any { it.html().contains("jwplayer(\"vplayer\").setup(") } ->
                page.document.select("script").firstOrNull { it.html().contains("jwplayer(\"vplayer\").setup(") }?.html()
            else -> page.document.selectFirst("script:containsData(sources:)")?.data()
        }
        if (JwPlayerHelper.extractStreamLinks(data.orEmpty(), name, host, callback, subtitleCallback, headers)) return true

        // WebView fallback — intercepts the master.txt / m3u8 request the player makes
        val resolver = WebViewResolver(
            interceptUrl = Regex("""txt|m3u8"""),
            additionalUrls = listOf(Regex("""txt|m3u8""")),
            useOkhttp = false,
            timeout = 15_000L
        )
        val intercepted = app.get(url, referer = referer, interceptor = resolver).url
        if (intercepted.isNotEmpty()) {
            M3u8Helper.generateM3u8(name, intercepted, host, headers = headers).forEach(callback)
            true
        } else false
    } catch (e: Exception) {
        println("WitAnimeDebug: wishFallback fail: ${e.message}")
        false
    }
}
