package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.you"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val userAgent = EXTRACTOR_UA
    private val cfKiller = CloudflareKiller()
    private val wvResolver by lazy { WebViewResolver(interceptUrl = Regex("""witanime\.(you|cyou|net|tv|quest|red)""")) }

    private fun isChallenge(doc: Document): Boolean {
        val h = doc.html()
        return h.contains("Just a moment") || h.contains("challenge-platform") || h.contains("cf-chl")
    }

    private suspend fun fetchDoc(url: String): Document {
        val fast = try {
            val d = app.get(url, headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller).document
            if (!isChallenge(d)) d else null
        } catch (e: Exception) { null }
        return fast ?: app.get(url, interceptor = wvResolver).document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = try { fetchDoc(mainUrl) } catch (e: Exception) {
            throw ErrorLoadingException("فشل تحميل الموقع: ${e.message}")
        }
        println("WitAnimeDebug: title=${document.title()} widgets=${document.select("div.main-widget").size}")

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
        return newHomePageResponse(homePageList, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?search_param=animes&s=" + URLEncoder.encode(query, "UTF-8")
        val document = try { fetchDoc(url) } catch (e: Exception) { return emptyList() }
        return document.select("div.anime-list-content div.anime-card-container").mapNotNull {
            val href = it.selectFirst("div.anime-card-poster a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("div.anime-card-title h3 a")?.text() ?: return@mapNotNull null
            newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
                posterUrl = fixUrlNull(it.selectFirst("img.img-responsive")?.attr("src"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)

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
                    val sb = StringBuilder()
                    for (i in p1.indices) sb.append((p1[i].code xor p2[i % p2.length].code).toChar())
                    val eps = AppUtils.parseJson(sb.toString()) as? List<Map<String, Any>>
                    if (eps != null) episodes = eps.mapNotNull { ep ->
                        val epUrl = ep["url"]?.toString() ?: return@mapNotNull null
                        val epName = ep["number"]?.toString() ?: ep["title"]?.toString() ?: "حلقة"
                        newEpisode(epUrl) { this.name = epName }
                    }
                }
            } catch (e: Exception) { logError(e) }
        }
        println("WitAnimeDebug: loaded '$title' episodes=${episodes.size}")

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // loadLinks v108 — DIAGNOSTIC + RESILIENT:
    //   📸 dumpPage   → logs page structure (server anchor HTML, script list)
    //   path 1        → classic _zG/_zH registries (inline or external js)
    //   path 2        → generic "id":{"r":"b64"} registry anywhere
    //   🌐 WV retry   → if static page had no registries, solve CF + refetch
    //   path 3        → server anchor href fallback
    //   path 4        → scan html for known embed hosts
    //   path 5        → px9 download links
    // ════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val FRAMEWORK_HASH = "1c0f3441-e3c2-4023-9e8b-bee77ff59adf"

        fun cleanBase64Chars(s: String) = s.replace(Regex("[^A-Za-z0-9+/=]"), "")
        fun b64Bytes(i: String?) = if (i.isNullOrBlank()) ByteArray(0) else try { Base64.decode(i, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        fun bytesStr(b: ByteArray) = if (b.isEmpty()) "" else try { String(b, Charsets.UTF_8) } catch (_: Exception) { try { String(b, Charset.forName("ISO-8859-1")) } catch (_: Exception) { b.joinToString("") { (it.toInt() and 0xFF).toChar().toString() } } }
        fun hexBytes(h: String?) = if (h.isNullOrBlank()) ByteArray(0) else { val c = h.replace(Regex("[^0-9a-fA-F]"), ""); if (c.length % 2 != 0) ByteArray(0) else c.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        fun xor(d: ByteArray, k: ByteArray) = if (k.isEmpty()) d else ByteArray(d.size) { i -> (d[i].toInt() xor k[i % k.size].toInt()).toByte() }
        fun trim(s: String?) = s?.replace(Regex("[\\x00\\u0000]"), "")?.trim() ?: ""

        suspend fun fetch(u: String) = try {
            app.get(u, headers = mapOf("User-Agent" to userAgent), referer = data, interceptor = cfKiller).text
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

        fun findServers(html: String): List<Triple<String, String, String?>> {
            val items = mutableListOf<Triple<String, String, String?>>()
            Regex("""(<a[^>]+class=["'][^"']*server-link[^"']*["'][^>]*>.*?</a>)""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
                val tag = m.groupValues[1]
                val sid = Regex("""data-server-id\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                val label = Regex("""<span[^>]+class=["'][^"']*ser[^"']*["'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
                val href = Regex("""href\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                if (sid != null) items.add(Triple(sid, label ?: "server-$sid", href))
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

        fun sanitize(l: String): String {
            val m = Regex("""https?://\S+""").find(l.trim()) ?: return ""
            return m.value.trimEnd('"', '\'', ')', ';', ',')
        }

        // 📸 page dumper — tells us where the data lives now
        fun dumpPage(tag: String, h: String) {
            for (mk in listOf("server-link", "_zG", "data-server-id", "<iframe", "processedEpisodeData")) {
                val i = h.indexOf(mk)
                println("WitAnimeDebug: $tag [$mk] idx=$i")
                if (i >= 0) println("WitAnimeDebug: $tag ctx=<<" + h.substring(maxOf(0, i - 150), minOf(h.length, i + 500)).replace("\n", " ") + ">>")
            }
            println("WitAnimeDebug: $tag flags atob=${h.contains("atob")} eval=${h.contains("eval(function")} iframe=${h.contains("<iframe")}")
            Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(h).take(10).forEachIndexed { n, m ->
                println("WitAnimeDebug: $tag script[$n]=${m.groupValues[1]}")
            }
        }

        return try {
            val html = fetch(data)
            if (html.isBlank()) { println("WitAnimeDebug: episode fetch EMPTY"); return false }
            println("WitAnimeDebug: html len=${html.length}")
            dumpPage("STATIC", html)

            val zRxG = Regex("""_zG\s*=\s*\"([^\"]+)\"""")
            val zRxH = Regex("""_zH\s*=\s*\"([^\"]+)\"""")
            var zG: String? = zRxG.find(html)?.groupValues?.get(1)
            var zH: String? = zRxH.find(html)?.groupValues?.get(1)

            val genericRes = mutableMapOf<String, String>()
            val genericCfg = mutableMapOf<String, Pair<String, String>>()
            fun scanGeneric(text: String) {
                Regex(""""([^"]{1,40})"\s*:\s*\{\s*"r"\s*:\s*"([A-Za-z0-9+/=]{20,})"""").findAll(text).forEach { genericRes[it.groupValues[1]] = it.groupValues[2] }
                Regex(""""([^"]{1,40})"\s*:\s*\{\s*"k"\s*:\s*"([^"]*)"\s*,\s*"d"\s*:\s*(\[[^\]]*\])""").findAll(text).forEach { genericCfg[it.groupValues[1]] = it.groupValues[2] to it.groupValues[3] }
            }

            val externalJs = StringBuilder()
            val scriptSrcs = Regex("""<script[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).map { m ->
                if (m.groupValues[1].startsWith("http")) m.groupValues[1]
                else try { java.net.URL(java.net.URL(data), m.groupValues[1]).toString() } catch (_: Exception) { m.groupValues[1] }
            }.toList()
            scriptSrcs.forEach { src ->
                val js = fetch(src)
                externalJs.append("\n").append(js)
                if (zG == null) zG = zRxG.find(js)?.groupValues?.get(1)
                if (zH == null) zH = zRxH.find(js)?.groupValues?.get(1)
            }
            scanGeneric("$html\n$externalJs")
            println("WitAnimeDebug: extScripts=${scriptSrcs.size} zG=${zG != null} zH=${zH != null} genericRes=${genericRes.size}")

            // 🌐 WebView retry if the static page gave us nothing
            var bestHtml = html
            if (zG == null && genericRes.isEmpty()) {
                println("WitAnimeDebug: ⚠️ no registries in static html → WebView retry")
                try { app.get(data, headers = mapOf("User-Agent" to userAgent), interceptor = wvResolver) } catch (_: Exception) {}
                val html2 = fetch(data)
                if (html2.isNotBlank() && html2 != html) {
                    println("WitAnimeDebug: retry len=${html2.length}")
                    dumpPage("WVRETRY", html2)
                    if (zG == null) zG = zRxG.find(html2)?.groupValues?.get(1)
                    if (zH == null) zH = zRxH.find(html2)?.groupValues?.get(1)
                    scanGeneric(html2)
                    bestHtml = html2
                }
            }

            fun toRegistry(b64: String?): Any? = try { val d = bytesStr(b64Bytes(b64)); try { JSONObject(d) } catch (_: Exception) { try { JSONArray(d) } catch (_: Exception) { null } } } catch (_: Exception) { null }
            val resourceReg = toRegistry(zG); val configReg = toRegistry(zH)

            var servers = findServers(bestHtml)
            if (servers.isEmpty()) servers = findServers(html)
            println("WitAnimeDebug: servers=${servers.size}")

            val semaphore = Semaphore(6)

            suspend fun decodeAndRoute(sid: String, anchorHref: String?) {
                try {
                    var link = ""
                    // path 1: classic registries
                    val res = lookup(resourceReg, sid)
                    if (res != null) link = sanitize(decodeResource(res, paramOffset(lookup(configReg, sid))))
                    // path 2: generic registry
                    if (link.isBlank() && genericRes.isNotEmpty()) {
                        val raw = genericRes[sid] ?: genericRes[sid.toIntOrNull()?.toString() ?: ""]
                        if (raw != null) {
                            var off = 0
                            genericCfg[sid]?.let { (k, d) ->
                                val idx = bytesStr(b64Bytes(k)).trim().toIntOrNull() ?: 0
                                val nums = Regex("-?\\d+").findAll(d).mapNotNull { it.value.toIntOrNull() }.toList()
                                off = nums.getOrNull(idx) ?: 0
                            }
                            link = sanitize(decodeResource(raw, off))
                            if (link.isBlank()) link = sanitize(decodeResource(raw, 0))
                        }
                    }
                    // path 3: anchor href
                    if (link.isBlank() && anchorHref != null && anchorHref.startsWith("http")) link = anchorHref
                    println("WitAnimeDebug: server $sid -> $link")
                    if (link.isNotBlank()) {
                        val finalLink = if (link.matches(Regex("""^https://yonaplay\.net/embed\.php\?id=\d+$"""))) "$link&apiKey=$FRAMEWORK_HASH" else link
                        routeLink(finalLink, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }

            supervisorScope {
                servers.map { (sid, _, href) -> async(Dispatchers.IO) { semaphore.withPermit { decodeAndRoute(sid, href) } } }.awaitAll()
            }

            // path 4: last resort — scan for known embed hosts
            if (servers.isEmpty() || (resourceReg == null && genericRes.isEmpty())) {
                println("WitAnimeDebug: ⚠️ scanning html for embed links")
                val known = listOf("videa", "dood", "wish", "mail.ru", "ok.ru", "mega.nz", "4shared", "mediafire.com", "filemoon", "mp4upload", "uqload", "streamtape", "yonaplay", "videas")
                supervisorScope {
                    Regex("""https?://[^\s"'<>]+""").findAll(bestHtml).map { it.value.trimEnd('"', '\'', ')', ';', ',') }
                        .filter { l -> known.any { l.contains(it, true) } }
                        .distinct().take(15).toList()
                        .map { l -> async(Dispatchers.IO) { semaphore.withPermit { try { routeLink(l, data, subtitleCallback, callback) } catch (_: Exception) {} } } }
                        .awaitAll()
                }
            }

            // path 5: px9 download links
            var px_mr: String? = null; var px_s = listOf<String>(); val px_p = mutableMapOf<String, List<String>>()
            Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(bestHtml).map { it.groupValues[1] }.forEach { s ->
                if ("_m" in s && "_p0" in s) { val (m, sl, pm) = parsePx9(s); px_mr = m ?: px_mr; if (sl.isNotEmpty()) px_s = sl; px_p.putAll(pm) }
            }
            supervisorScope { decryptPx9(px_mr, px_s, px_p).map { dl -> async(Dispatchers.IO) { semaphore.withPermit { try {
                val idx = dl.indexOf("http"); val final = trim(if (idx >= 0) dl.substring(idx) else dl)
                if (final.startsWith("http")) routeLink(final, data, subtitleCallback, callback)
            } catch (_: Exception) {} } } }.awaitAll() }
            true
        } catch (e: Exception) { logError(e); false }
    }

    /** ⚡ THE ROUTER — wildcard matching, videas.fr included, full logging */
    private suspend fun routeLink(link: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("WitAnimeDebug: routing -> $link")
        when {
            linkHost(link).contains("yonaplay") -> decodeYonaplayAndLoad(link, subtitleCallback, callback)
            linkHost(link).contains("videa.hu") -> VideaExtractor().getUrl(link, referer, subtitleCallback, callback)
            linkHost(link).contains("videas.fr") -> VideasFrExtractor().getUrl(link, referer, subtitleCallback, callback)
            linkHost(link).contains("my.mail.ru") || link.contains("/video/embed/", true) -> MailruExtractor().getUrl(link, referer, subtitleCallback, callback)
            isMegaLink(link) -> MegaExtractor().getUrl(link, referer, subtitleCallback, callback)
            isDoodLink(link) -> DoodExtractor().getUrl(link, referer, subtitleCallback, callback)
            isStreamWishLink(link) -> StreamWishExtractor().getUrl(link, referer, subtitleCallback, callback)
            linkHost(link).contains("filemoon") -> FileMoonExtractor().getUrl(link, referer, subtitleCallback, callback)
            linkHost(link).contains("4shared") -> FourSharedExtractor().getUrl(link, referer, subtitleCallback, callback)
            linkHost(link).contains("mediafire") -> MediaFireExtractor().getUrl(link, referer, subtitleCallback, callback)
            else -> {
                val ok = loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                if (!ok) {
                    println("WitAnimeDebug: ⚠️ no extractor matched: $link — trying UniversalSniffer")
                    UniversalExtractor().getUrl(link, referer, subtitleCallback, callback)
                }
            }
        }
    }

    /** Yonaplay = router: b64 players, iframes, mega/4shared hrefs ALL recurse into routeLink */
    private suspend fun decodeYonaplayAndLoad(yonaplayUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(yonaplayUrl, referer = "$mainUrl/", headers = mapOf("User-Agent" to userAgent)).text
            val seen = mutableSetOf<String>()

            fun qualityOf(label: String) = when {
                label.contains("1080") || label.contains("FHD") -> Qualities.P1080.value
                label.contains("720") || label.contains("HD") -> Qualities.P720.value
                label.contains("480") -> Qualities.P480.value
                label.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            // 1) <source src label>
            Regex("""<source[^>]*src=["']([^"']+)["'][^>]*label=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val url = m.groupValues[1]; val label = m.groupValues[2]
                if (seen.add(url) && url.startsWith("http")) {
                    callback(newExtractorLink("Yonaplay", "Yonaplay $label", url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = yonaplayUrl; this.quality = qualityOf(label)
                    })
                }
            }

            // 2) go_to_player('b64') → may decode to 4shared / mega / gdrive / anything
            Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""").findAll(html).map { it.groupValues[1] }.forEach { encoded ->
                var fixed = encoded; val pad = encoded.length % 4; if (pad != 0) fixed += "=".repeat(4 - pad)
                try {
                    val decoded = String(Base64.decode(fixed, Base64.DEFAULT)).trim()
                    if (decoded.contains("drive.google.com/file/d/")) {
                        Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)?.groupValues?.get(1)?.let { fid ->
                            val g = "https://drive.usercontent.google.com/download?id=$fid&export=download&confirm=t"
                            if (seen.add(g)) callback(newExtractorLink("Yonaplay", "Google Drive", g, ExtractorLinkType.VIDEO) {
                                referer = "https://drive.google.com/"; quality = Qualities.Unknown.value
                            })
                        }
                    } else if (decoded.startsWith("http") && seen.add(decoded)) {
                        routeLink(decoded, yonaplayUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }

            // 3) plain mega/4shared/mediafire hrefs
            Regex("""https?://(?:mega\.nz|www\.4shared\.com|www\.mediafire\.com)/[^\s"'<>]+""").findAll(html).forEach {
                if (seen.add(it.value)) routeLink(it.value, yonaplayUrl, subtitleCallback, callback)
            }

            // 4) iframes → recurse
            Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html).forEach { m ->
                var src = m.groupValues[1]
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http") && !src.contains("yonaplay") && seen.add(src)) {
                    routeLink(src, yonaplayUrl, subtitleCallback, callback)
                }
            }

            // 5) direct mp4/m3u8
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(html).forEach { m ->
                val url = m.groupValues[1]
                if (!url.contains("googleapis") && !url.contains("drive.google") && seen.add(url)) {
                    callback(newExtractorLink("Yonaplay", "Yonaplay Direct", url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = yonaplayUrl; quality = Qualities.Unknown.value
                    })
                }
            }
        } catch (e: Exception) { println("WitAnimeDebug: Yonaplay error: ${e.message}") }
    }
}
