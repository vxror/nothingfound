package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    // ✅ local p,a,c,k,e,d unpacker — no library dependency, works on any CloudStream version
    private fun unpack(source: String): String = try {
        val m = Regex(
            """\}\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\s*\.\s*split\s*\(\s*'\|'\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(source) ?: return source
        val payload = m.groupValues[1]
        val radix = m.groupValues[2].toIntOrNull() ?: return source
        val words = m.groupValues[4].split("|")
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun unbase(v: String): Int = v.fold(0) { acc, c -> acc * radix + chars.indexOf(c) }

        payload.replace(Regex("""\b\w+\b""")) { mr ->
            val token = mr.value
            val idx = try { unbase(token) } catch (e: Exception) { -1 }
            if (idx in words.indices && words[idx].isNotEmpty()) words[idx] else token
        }
    } catch (e: Exception) { source }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val raw = try {
            app.get(url, referer = referer).text
        } catch (e: Exception) { return }

        val js = unpack(raw).replace("\\", "")

        val found = LinkedHashSet<String>()
        Regex("""file:"(https?://[^"]+\.m3u8[^"]*)"""").findAll(js).forEach { found.add(it.groupValues[1]) }
        Regex("""file":"(https?://[^"]+\.m3u8[^"]*)"""").findAll(js).forEach { found.add(it.groupValues[1]) }
        if (found.isEmpty()) Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(js).forEach { found.add(it.groupValues[1]) }

        found.forEach { link ->
            val q = Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)
                ?: Regex("""label":"(\d{3,4})"""").find(js)?.groupValues?.get(1)
            callback(
                newExtractorLink(this.name, this.name, link, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    quality = getQualityFromName(q)
                }
            )
        }

        Regex("""\{"file":"(https?://[^"]+\.(?:vtt|srt))"[^}]*?"label":"([^"]+)""""").findAll(js).forEach {
            subtitleCallback(SubtitleFile(it.groupValues[2], it.groupValues[1]))
        }
    }
}

class Awish : StreamWishExtractor() {
    override val name = "Awish"
    override val mainUrl = "https://awish.pro"
}

class Asnwish : StreamWishExtractor() {
    override val name = "Asnwish"
    override val mainUrl = "https://asnwish.com"
}

class CdnwishCom : StreamWishExtractor() {
    override val name = "Cdnwish"
    override val mainUrl = "https://cdnwish.com"
}

class MediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try { app.get(url, referer = referer).text } catch (_: Exception) { return }
        val link = Regex("""href="(https?://download[^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""(https?://download\d+\.mediafire\.com/[^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return
        callback(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
        })
    }
}
