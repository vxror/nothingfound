package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val raw = try {
            app.get(url, referer = referer).text
        } catch (e: Exception) { return }

        // ✅ FIX: unpackJs works on the fetched text (getAndUnpack expects a URL — that was the bug)
        val js = (try { unpackJs(raw) } catch (_: Exception) { null } ?: raw).replace("\\", "")

        val found = LinkedHashSet<String>()
        listOf(
            """file":"(https?://[^"]+\.m3u8[^"]*)"""",
            """file:"(https?://[^"]+\.m3u8[^"]*)""""
        ).forEach { p -> Regex(p).findAll(js).forEach { found.add(it.groupValues[1]) } }
        if (found.isEmpty()) Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").findAll(js).forEach { found.add(it.groupValues[1]) }

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
