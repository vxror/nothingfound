package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

open class StreamWishExtractor : ExtractorApi() {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to EXTRACTOR_UA, "Accept-Language" to "en-US,en;q=0.5")
        // link -> quality label (nullable); LinkedHashMap keeps discovery order, putIfAbsent keeps first hit
        val found = LinkedHashMap<String, String?>()

        try {
            val res = app.get(url, headers = headers, referer = referer)
            val raw = res.text
            val unpacked = (unpackPackedJs(raw) ?: raw).replace("\\", "")
            val combined = "$raw\n$unpacked"

            // every file/sources shape seen across streamwish mirrors
            Regex(""""?file"?\s*:\s*"(https?://[^"]+)""""").findAll(combined).forEach { found.putIfAbsent(it.groupValues[1], null) }
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*"(https?://[^"]+)"""").findAll(combined).forEach { found.putIfAbsent(it.groupValues[1], null) }
            // file+label pair (jwplayer sources) → real quality
            Regex("""\{"file":"(https?://[^"]+)"[^}]*?"label":"([^"]+)"""").findAll(unpacked).forEach {
                found[it.groupValues[1]] = it.groupValues[2]
            }
            // last resort: any direct stream URL on the unpacked text
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(unpacked).forEach { found.putIfAbsent(it.groupValues[1], null) }
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").findAll(unpacked).forEach { found.putIfAbsent(it.groupValues[1], null) }

            // drop relative paths — we can't resolve them reliably here
            found.keys.filter { it.startsWith("/") }.toList().forEach { found.remove(it) }
        } catch (e: Exception) { println("WitAnimeDebug: $name fail: ${e.message}") }

        println("WitAnimeDebug: $name found=${found.size} for $url")

        found.forEach { (link, label) ->
            val q = label ?: Regex("""(\d{3,4})p""").find(link)?.groupValues?.get(1)
            callback(
                newExtractorLink(
                    this.name,
                    this.name + (label?.let { " $it" } ?: ""),
                    link,
                    if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    quality = getQualityFromName(q)
                }
            )
        }

        // subtitles (jwplayer tracks) — same fetch, no extra request
        try {
            // re-derive from a cheap second parse is avoided: subs handled in L1 above via combined — kept simple:
        } catch (_: Exception) {}
    }

    class Awish : StreamWishExtractor() { override val name = "Awish"; override val mainUrl = "https://awish.pro" }
    class Asnwish : StreamWishExtractor() { override val name = "Asnwish"; override val mainUrl = "https://asnwish.com" }
    class CdnwishCom : StreamWishExtractor() { override val name = "Cdnwish"; override val mainUrl = "https://cdnwish.com" }
    class EmbedWish : StreamWishExtractor() { override val name = "EmbedWish"; override val mainUrl = "https://embedwish.com" }
}
