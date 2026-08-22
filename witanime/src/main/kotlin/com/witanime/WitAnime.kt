package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller   // ← FIX #1
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class WitAnime : MainAPI() {

    // ⚠️ PUT YOUR REAL WORKING DOMAIN HERE (https://..., no trailing slash)
    override var mainUrl = "https://witanime.net"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // FIX #1: direct init instead of `by lazy` (the lazy delegate caused the error cascade when the import was missing)
    private val cfKiller = CloudflareKiller()

    private suspend fun getDocument(url: String, referer: String? = null): Document? = try {
        val res = app.get(url, referer = referer, headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller)
        if (res.isSuccessful) res.document else null
    } catch (e: Exception) { logError(e); null }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val document = getDocument(mainUrl)
            ?: throw ErrorLoadingException("تعذر تحميل $mainUrl — الدومين خطأ أو الحماية تمنع الطلب")

        val homePageList = ArrayList<HomePageList>()
        document.select("div.main-widget").forEach { widget ->
            val title = widget.selectFirst("div.main-didget-head h3")?.text()?.trim() ?: return@forEach
            val isEpisodeList = title.contains("حلقات")
            val items = widget.select(if (isEpisodeList) "div.episodes-card-container" else "div.anime-card-container").mapNotNull {
                val a = if (isEpisodeList) it.selectFirst(".ep-card-anime-title a") else it.selectFirst("a.overlay")
                val itemUrl = fixUrl(a?.attr("href") ?: return@mapNotNull null)
                val itemName = (if (isEpisodeList) a?.text() else it.selectFirst(".anime-card-title a")?.text()) ?: ""
                val img = it.selectFirst("img")
                val itemPoster = img?.attr("src")?.ifBlank { img.attr("data-src") }
                val finalTitle = if (isEpisodeList) "$itemName - ${it.selectFirst(".episodes-card-title a")?.text() ?: ""}" else itemName
                newAnimeSearchResponse(finalTitle, itemUrl, TvType.Anime) { posterUrl = fixUrlNull(itemPoster) }
            }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }
        if (homePageList.isEmpty())
            throw ErrorLoadingException("الصفحة فتحت لكن ما في أقسام — الـ selectors تحتاج تحديث")
        return newHomePageResponse(homePageList, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val document = getDocument("$mainUrl/?search_param=animes&s=$q") ?: return emptyList()
        return document.select("div.anime-list-content div.anime-card-container").mapNotNull {
            val href = it.selectFirst("div.anime-card-poster a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("div.anime-card-title h3 a")?.text() ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                posterUrl = fixUrlNull(it.selectFirst("img.img-responsive")?.attr("src"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url) ?: throw ErrorLoadingException("تعذر تحميل $url")

        val title = document.selectFirst("h1.anime-details-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.anime-thumbnail img")?.attr("src")
        val description = document.selectFirst("p.anime-story")?.text()?.trim()
        val genres = document.select("ul.anime-genres li a").map { it.text() }

        var status = ShowStatus.Ongoing
        var tvType = TvType.Anime
        document.select(".anime-info").forEach {
            val infoText = it.text()
            if (infoText.startsWith("حالة الأنمي:")) status = if (infoText.contains("مكتمل")) ShowStatus.Completed else ShowStatus.Ongoing
            if (infoText.startsWith("النوع:")) tvType = if (infoText.contains("Movie")) TvType.AnimeMovie else TvType.Anime
        }

        var episodes = listOf<Episode>()
        val match = Regex("""var\s+processedEpisodeData\s*=\s*'([^']+)'""").find(document.html())
        val encodedData = match?.groupValues?.get(1)

        if (!encodedData.isNullOrBlank()) {
            try {
                val parts = encodedData.split(".")
                if (parts.size == 2) {
                    val p1 = String(Base64.decode(parts[0], Base64.DEFAULT))
                    val p2 = String(Base64.decode(parts[1], Base64.DEFAULT))
                    val decodedJson = StringBuilder()
                    for (i in p1.indices) decodedJson.append((p1[i].code xor p2[i % p2.length].code).toChar())
                    val episodesList = AppUtils.parseJson(decodedJson.toString()) as? List<Map<String, Any>>
                    if (episodesList != null) {
                        episodes = episodesList.mapNotNull { ep ->
                            val epUrl = ep["url"]?.toString() ?: return@mapNotNull null
                            val epName = ep["number"]?.toString() ?: ep["title"]?.toString() ?: "حلقة"
                            newEpisode(epUrl) { this.name = epName }
                        }
                    }
                }
            } catch (e: Exception) { logError(e) }
        }

        if (episodes.isEmpty()) {
            episodes = document.select("a[href*='/episode/'], div.episodes-card-container a").mapNotNull { el ->
                val href = fixUrl(el.attr("href")).takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val name = el.text().trim().ifBlank { el.selectFirst("h3,span")?.text()?.trim() ?: "" }
                newEpisode(href) { this.name = name }
            }.distinctBy { it.data }   // ← FIX #2: Episode has .data, not .url
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val FRAMEWORK_HASH = "1c0f3441-e3c2-4023-9e8b-bee77ff59adf"

        fun cleanBase64Chars(s: String) = s.replace(Regex("[^A-Za-z0-9+/=]"), "")
        fun b64Bytes(i: String?) = if (i.isNullOrBlank()) ByteArray(0) else try { Base64.decode(i, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        fun bytesStr(b: ByteArray) = if (b.isEmpty()) "" else try { String(b, Charsets.UTF_8) } catch (_: Exception) { try { String(b, Charset.forName("ISO-8859-1")) } catch (_: Exception) { b.joinToString("") { (it.toInt() and 0xFF).toChar().toString() } } }
        fun hexBytes(h: String?) = if (h.isNullOrBlank()) ByteArray(0) else { val c = h.replace(Regex("[^0-9a-fA-F]"), ""); if (c.length % 2 != 0) ByteArray(0) else c.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        fun xor(d: ByteArray, k: ByteArray) = if (k.isEmpty()) d else ByteArray(d.size) { i -> (d[i].toInt() xor k[i % k.size].toInt()).toByte() }
        fun trim(s: String?) = s?.replace(Regex("[\\x00\\u0000]"), "")?.trim() ?: ""

        suspend fun fetch(u: String) = try {
            val r = app.get(u, headers = mapOf("User-Agent" to userAgent), referer = data, interceptor = cfKiller)
            if (r.isSuccessful) r.text else ""
        } catch (_: Exception) { "" }

        fun paramOffset(config: Any?): Int {
            try {
                when (config) {
                    is Map<*, *> -> { val idx = bytesStr(b64Bytes(config["k"] as? String)).toIntOrNull() ?: return 0; val d = config["d"]; if (d is List<*>) return (d.getOrNull(idx) as? Number)?.toInt() ?: 0 }
                    is JSONObject -> { val k = if (config.has("k")) config.optString("k", "") else ""; if (k.isBlank()) return 0; val idx = bytesStr(b64Bytes(k)).toIntOrNull() ?: return 0; val d = if (config.has("d")) config.get("d") else return 0; if (d is JSONArray) return d.optInt(idx) }
                }
            } catch (_: Exception) {}
            return 0
        }

        fun decodeResource(raw: Any?, offset: Int): String {
            var s: String? = null
            when (raw) {
                is String -> s = raw
                is Map<*, *> -> s = (raw["r"] ?: raw["resource"] ?: raw["data"]) as? String
                is JSONObject -> s = listOf("r", "resource", "data").firstNotNullOfOrNull { raw.optString(it, "") }?.takeIf { it.isNotBlank() }
            }
            if (s.isNullOrBlank()) return ""
            val decoded = b64Bytes(cleanBase64Chars(s.reversed()))
            val slice = if (offset in 1..decoded.size) decoded.copyOf(decoded.size - offset) else decoded
            return trim(bytesStr(slice))
        }

        fun parsePx9(js: String): Triple<String?, List<String>, Map<String, List<String>>> {
            val mVal = Regex("""var\s+_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(js)?.groupValues?.get(1)
            val sList = Regex("""var\s+_s\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(js)?.let { Regex("\"([^\"]*)\"").findAll(it.groupValues[1]).map { m -> m.groupValues[1] }.toList() } ?: emptyList()
            val pMap = mutableMapOf<String, List<String>>()
            Regex("""var\s+(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""", RegexOption.DOT_MATCHES_ALL).findAll(js).forEach { m -> pMap[m.groupValues[1]] = Regex("\"([^\"]*)\"").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList() }
            return Triple(mVal, sList, pMap)
        }

        fun chunk(hex: String?, secret: ByteArray) = trim(bytesStr(xor(hexBytes(hex), secret)))

        fun decryptPx9(mr: String?, sList: List<String>, pDict: Map<String, List<String>>): List<String> {
            if (mr.isNullOrBlank()) return emptyList()
            val secret = b64Bytes(mr); val out = mutableListOf<String>()
            for (i in 0 until maxOf(sList.size, pDict.size)) {
                val chunks = pDict["_p$i"] ?: continue
                val decrypted = chunks.map { chunk(it, secret) }
                val seq = if (i < sList.size) try { val a = JSONArray(chunk(sList[i], secret)); IntArray(a.length()) { a.getInt(it) } } catch (_: Exception) { null } else null
                out.add(if (seq != null && seq.size == decrypted.size) {
                    val arr = Array(decrypted.size) { "" }; decrypted.indices.forEach { j -> if (seq[j] in arr.indices) arr[seq[j]] = decrypted[j] }; arr.joinToString("")
                } else decrypted.joinToString(""))
            }
            return out.map { trim(it) }
        }

        fun findServers(html: String): List<Pair<String, String>> {
            val items = mutableListOf<Pair<String, String>>()
            Regex("""(<a[^>]+class=["'][^"']*server-link[^"']*["'][^>]*>.*?</a>)""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
                val tag = m.groupValues[1]
                val sid = Regex("""data-server-id\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                val label = Regex("""<span[^>]+class=["'][^"']*ser[^"']*["'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
                if (sid != null) items.add(sid to (label ?: "server-$sid"))
            }
            return items
        }

        fun lookup(reg: Any?, sid: String): Any? {
            try {
                when (reg) {
                    is JSONObject -> return if (reg.has(sid)) reg.get(sid) else { val i = sid.toIntOrNull(); if (i != null && reg.has(i.toString())) reg.get(i.toString()) else null }
                    is JSONArray -> { val i = sid.toIntOrNull(); if (i != null && i in 0 until reg.length()) return reg.get(i) }
                    is Map<*, *> -> return reg[sid] ?: reg[sid.toIntOrNull()]
                }
            } catch (_: Exception) {}
            return null
        }

        return try {
            val html = fetch(data)
            if (html.isBlank()) return false
            var zG: String? = null; var zH: String? = null
            val inlineScripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()
            for (s in inlineScripts) {
                if (zG == null) zG = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
                if (zH == null) zH = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
                if (zG != null && zH != null) break
            }
            if (zG == null || zH == null) {
                Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                    if (zG != null && zH != null) return@forEach
                    val src = if (m.groupValues[1].startsWith("http")) m.groupValues[1] else try { java.net.URL(java.net.URL(data), m.groupValues[1]).toString() } catch (_: Exception) { m.groupValues[1] }
                    val js = fetch(src)
                    if (zG == null) zG = Regex("""var\s+_zG\s*=\s*\"([^\"]+)\"""").find(js)?.groupValues?.get(1)
                    if (zH == null) zH = Regex("""var\s+_zH\s*=\s*\"([^\"]+)\"""").find(js)?.groupValues?.get(1)
                }
            }
            fun toRegistry(b64: String?): Any? = try { val d = bytesStr(b64Bytes(b64)); try { JSONObject(d) } catch (_: Exception) { try { JSONArray(d) } catch (_: Exception) { null } } } catch (_: Exception) { null }
            val resourceReg = toRegistry(zG); val configReg = toRegistry(zH)
            val servers = findServers(html)
            val semaphore = Semaphore(6)

            supervisorScope {
                servers.map { (sid, _) -> async(Dispatchers.IO) { semaphore.withPermit { try {
                    val link = decodeResource(lookup(resourceReg, sid), paramOffset(lookup(configReg, sid)))
                    val finalLink = if (link.matches(Regex("""^https://yonaplay\.net/embed\.php\?id=\d+$"""))) "$link&apiKey=$FRAMEWORK_HASH" else link
                    if (finalLink.isNotBlank()) routeLink(finalLink, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {} } } }.awaitAll()
            }

            var px_mr: String? = null; var px_s = listOf<String>(); val px_p = mutableMapOf<String, List<String>>()
            for (s in inlineScripts) { if ("_m" in s && "_p0" in s) { val (m, sl, pm) = parsePx9(s); px_mr = m ?: px_mr; if (sl.isNotEmpty()) px_s = sl; px_p.putAll(pm); if (px_mr != null && px_p.isNotEmpty()) break } }
            if (px_p.isEmpty() || px_mr == null) {
                Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                    if (px_mr != null && px_p.isNotEmpty()) return@forEach
                    val src = if (m.groupValues[1].startsWith("http")) m.groupValues[1] else try { java.net.URL(java.net.URL(data), m.groupValues[1]).toString() } catch (_: Exception) { m.groupValues[1] }
                    val js = fetch(src); if (js.isBlank()) return@forEach
                    val (m2, sl2, pm2) = parsePx9(js); if (m2 != null && px_mr == null) px_mr = m2; if (sl2.isNotEmpty() && px_s.isEmpty()) px_s = sl2; if (pm2.isNotEmpty()) px_p.putAll(pm2)
                }
            }
            if (px_p.isEmpty()) { val (m3, sl3, pm3) = parsePx9(html); px_mr = m3 ?: px_mr; if (sl3.isNotEmpty()) px_s = sl3; px_p.putAll(pm3) }

            supervisorScope { decryptPx9(px_mr, px_s, px_p).map { dl -> async(Dispatchers.IO) { semaphore.withPermit { try {
                val idx = dl.indexOf("http"); val final = trim(if (idx >= 0) dl.substring(idx) else dl)
                if (final.startsWith("http")) routeLink(final, data, subtitleCallback, callback)
            } catch (_: Exception) {} } } }.awaitAll() }
            true
        } catch (e: Exception) { logError(e); false }
    }

    private suspend fun routeLink(link: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val host = try { java.net.URI(link).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        when {
            host.contains("yonaplay.net") -> decodeYonaplayAndLoad(link, subtitleCallback, callback)
            host.contains("videa.hu") -> VideaExtractor().getUrl(link, referer, subtitleCallback, callback)
            host.contains("my.mail.ru") || link.contains("/video/embed/", true) -> MailruExtractor().getUrl(link, referer, subtitleCallback, callback)
            else -> loadExtractor(link, mainUrl, subtitleCallback, callback)
        }
    }

    private suspend fun decodeYonaplayAndLoad(yonaplayUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(yonaplayUrl, referer = mainUrl, headers = mapOf("User-Agent" to userAgent)).text
            Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""").findAll(html).map { it.groupValues[1] }.forEach { encoded ->
                var fixed = encoded; val pad = encoded.length % 4; if (pad != 0) fixed += "=".repeat(4 - pad)
                try {
                    val decoded = String(Base64.decode(fixed, Base64.DEFAULT))
                    if (decoded.contains("drive.google.com/file/d/")) {
                        Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)?.groupValues?.get(1)?.let { fid ->
                            callback(newExtractorLink("Yonaplay", "Google Drive", "https://drive.usercontent.google.com/download?id=$fid&export=download&confirm=t", ExtractorLinkType.VIDEO) { referer = "https://drive.google.com/"; quality = Qualities.Unknown.value })
                            return@forEach
                        }
                    }
                    loadExtractor(decoded, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
