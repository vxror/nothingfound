package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import kotlin.random.Random

object WitExtractors {

    private val JUNK = listOf("doubleclick.net", "googlesyndication", "google-analytics", "facebook.com", "captcha", ".css", ".js", ".png", ".jpg", ".gif", ".svg", ".ico", ".woff")
    private fun isJunk(l: String) = l.length <= 20 || JUNK.any { l.contains(it, true) }
    private fun quality(url: String) = when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    suspend fun tryExtract(host: String, url: String, referer: String?, sourceName: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            when {
                host.contains("dood") || host.contains("dstream") || host.contains("ds2play") -> dood(url, referer, sourceName, callback)
                host.contains("streamtape") || host.contains("stape") || host.contains("tape") -> streamTape(url, referer, sourceName, callback)
                host.contains("vidguard") || host.contains("listeamed") -> vidGuard(url, referer, sourceName, callback)
                host.contains("voe") -> voe(url, referer, sourceName, callback)
                host.contains("filemoon") || host.contains("moonplayer") -> fileMoon(url, referer, sourceName, callback)
                host.contains("streamsb") || host.contains("sbplay") -> streamSB(url, referer, sourceName, callback)
                host.contains("mp4upload") -> mp4Upload(url, referer, sourceName, callback)
                host.contains("ok.ru") || host.contains("odnoklassniki") -> okRu(url, referer, sourceName, callback)
                host.contains("uqload") -> uqload(url, referer, sourceName, callback)
                host.contains("streamwish") || host.contains("wishfast") -> streamWish(url, referer, sourceName, callback)
                host.contains("vidhide") || host.contains("filelions") -> vidHide(url, referer, sourceName, callback)
                else -> universalSniffer(url, referer, sourceName, callback)
            }
        } catch (_: Exception) { false }
    }

    private suspend fun emit(sourceName: String, label: String, url: String, type: ExtractorLinkType, referer: String?, q: Int, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(sourceName, label, url, type) { this.referer = referer ?: ""; this.quality = q })
    }

    private suspend fun dood(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val embed = url.replace("/d/", "/e/").replace("/w/", "/e/")
        val html = app.get(embed, referer = referer).text
        val md5 = Regex("""'/pass_md5/([^']+)'""").find(html)?.groupValues?.get(1) ?: return false
        val base = URI(embed).let { "${it.scheme}://${it.host}" }
        val pass = app.get("$base/pass_md5/$md5", referer = embed).text
        val token = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(62)] }.joinToString("")
        emit(sn, "DoodStream", "$pass$token", ExtractorLinkType.VIDEO, embed, Qualities.Unknown.value, cb); return true
    }

    private suspend fun streamTape(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val m = Regex("""getElementById\('norobotlink'\)\.innerHTML\s*=\s*'([^']+)'""").find(html)
            ?: Regex("""id=["']norobotlink["'][^>]*>([^<]+)<""").find(html) ?: return false
        var link = m.groupValues[1].trim()
        if (link.startsWith("//")) link = "https:$link"
        if (!link.contains("&stream=1")) link += "&stream=1"
        emit(sn, "StreamTape", link, ExtractorLinkType.VIDEO, url, Qualities.Unknown.value, cb); return true
    }

    private suspend fun vidGuard(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val embed = if (url.contains("/d/") || url.contains("/v/")) url.replace("/d/", "/e/").replace("/v/", "/e/") else url
        val script = app.get(embed, referer = referer ?: "https://vidguard.to/").document.selectFirst("script:containsData(eval)")?.data() ?: return false
        val unpacked = JsUnpacker(script).unpack() ?: script
        val m = Regex("""stream\s*:\s*["']([^"']+)["']""").find(unpacked) ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked) ?: return false
        var link = m.groupValues[1]
        if (link.length % 4 != 0) link += "=".repeat(4 - link.length % 4)
        val final = try { val d = String(Base64.decode(link, Base64.DEFAULT)); if (d.startsWith("http")) d else link } catch (_: Exception) { link }
        val type = if (final.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        emit(sn, "VidGuard", final, type, embed, quality(final), cb); return true
    }

    private suspend fun voe(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val text = app.get(url, referer = url).text
        val link = Regex("""https?://[^"\' ]+\.m3u8[^"\' ]*""").find(text)?.value
            ?: Regex("""file:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1) ?: return false
        emit(sn, "Voe", link, ExtractorLinkType.M3U8, url, Qualities.Unknown.value, cb); return true
    }

    private suspend fun fileMoon(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(unpacked)?.groupValues?.get(1) ?: return false
        emit(sn, "FileMoon", m3u8, ExtractorLinkType.M3U8, url, quality(m3u8), cb); return true
    }

    private suspend fun streamSB(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val m = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)
            ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(html) ?: return false
        emit(sn, "StreamSB", m.groupValues[1].replace("\\/", "/"), ExtractorLinkType.M3U8, url, quality(m.groupValues[1]), cb); return true
    }

    private suspend fun mp4Upload(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val mp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(unpacked)?.groupValues?.get(1) ?: return false
        emit(sn, "MP4Upload", mp4, ExtractorLinkType.VIDEO, url, quality(mp4), cb); return true
    }

    private suspend fun okRu(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        var found = false
        Regex("""\{"name":"([^"]+)","url":"([^"]+)"\}""").findAll(html).forEach { m ->
            val link = m.groupValues[2].replace("\\/", "/")
            if (link.contains(".mp4") || link.contains(".m3u8")) {
                val type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                emit(sn, "OK.ru ${m.groupValues[1]}", link, type, url, m.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value, cb)
                found = true
            }
        }
        return found
    }

    private suspend fun uqload(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val m = Regex("""sources\s*:\s*\[\s*["']([^"']+\.mp4[^"']*)["']""").find(html)
            ?: Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(html) ?: return false
        emit(sn, "Uqload", m.groupValues[1].replace("\\/", "/"), ExtractorLinkType.VIDEO, url, quality(m.groupValues[1]), cb); return true
    }

    private suspend fun streamWish(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val m = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)
            ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(unpacked) ?: return false
        emit(sn, "StreamWish", m.groupValues[1], ExtractorLinkType.M3U8, url, quality(m.groupValues[1]), cb); return true
    }

    private suspend fun vidHide(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(url, referer = referer).text
        val unpacked = JsUnpacker(html).unpack() ?: html
        val m = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(unpacked) ?: return false
        emit(sn, "VidHide", m.groupValues[1], ExtractorLinkType.M3U8, url, quality(m.groupValues[1]), cb); return true
    }

    // 🧠 ADVANCED UNIVERSAL SNIFFER
    private fun deepUnwrap(html: String): String {
        var result = html; var depth = 0
        while (depth < 5) {
            var changed = false
            JsUnpacker(result).unpack()?.let { result += "\n" + it; changed = true }
            Regex("""eval\s*\(\s*atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)\s*\)""").findAll(result).forEach { m ->
                try { result = result.replace(m.value, String(Base64.decode(m.groupValues[1], Base64.DEFAULT))); changed = true } catch (_: Exception) {}
            }
            Regex("""String\.fromCharCode\s*\(\s*(\d+(?:\s*,\s*\d+)+)\s*\)""").findAll(result).forEach { m ->
                try { result = result.replace(m.value, "\"${m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }.map { it.toChar() }.joinToString("")}\""); changed = true } catch (_: Exception) {}
            }
            if (result.contains("\\x")) { val d = result.replace(Regex("""\\x([0-9a-fA-F]{2})""")) { m -> (m.groupValues[1].toInt(16).toChar()).toString() }; if (d != result) { result = d; changed = true } }
            if (result.contains("\\u")) { val d = result.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { m -> (m.groupValues[1].toInt(16).toChar()).toString() }; if (d != result) { result = d; changed = true } }
            Regex("""(?:unescape|decodeURIComponent)\s*\(\s*["'](%[^"']+)["']""").findAll(result).forEach { m ->
                try { result = result.replace(m.value, URLDecoder.decode(m.groupValues[1], "UTF-8")); changed = true } catch (_: Exception) {}
            }
            if (!changed) break
            depth++
        }
        return result
    }

    private val PATTERNS = listOf(
        Regex("""(?:file|source|src|url)\s*:\s*["'](https?://[^"']+?(?:\.m3u8|\.mp4|\.mkv)[^"']*?)["']""", RegexOption.IGNORE_CASE),
        Regex("""<source\s+[^>]*src=["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""data-(?:video|url|src|embed|file)\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""(https?://[^"'\s\\<>]+\.(?:m3u8|mp4|mkv)[^"'\s\\<>]*)""", RegexOption.IGNORE_CASE)
    )

    suspend fun universalSniffer(url: String, referer: String?, sn: String, cb: (ExtractorLink) -> Unit): Boolean {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        val response = app.get(url, headers = headers, referer = referer ?: url, allowRedirects = true)
        var text = deepUnwrap(response.text)
        Regex("""atob\s*\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""").findAll(text).forEach { m ->
            try { text += "\n" + String(Base64.decode(m.groupValues[1], Base64.DEFAULT)) } catch (_: Exception) {}
        }
        val links = mutableSetOf<String>()
        fun collect(t: String) { PATTERNS.forEach { p -> p.findAll(t).forEach { m -> val l = m.groupValues[1].replace("\\/", "/"); if (!isJunk(l) && l.startsWith("http")) links.add(l) } } }
        collect(text)
        if (links.isEmpty()) {
            var count = 0
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(response.text).forEach { m ->
                if (count >= 2) return@forEach
                var src = m.groupValues[1]; if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http") && !isJunk(src)) { try { collect(deepUnwrap(app.get(src, headers = headers, referer = url).text)); count++ } catch (_: Exception) {} }
            }
        }
        if (links.isEmpty()) return false
        links.forEach { l ->
            val type = if (l.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            emit(sn, "$sn Sniffed", l, type, url, quality(l), cb)
        }
        return true
    }
}
