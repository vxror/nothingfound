package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

    private val FRAMEWORK_HASH = "9933bd27-92ea-4ee9-807d-e612029d6318"

    // ==================== [INSTANT NEXT EPISODE: cache + prefetch] ====================
    companion object {
        private const val CACHE_TTL = 20 * 60 * 1000L   // links stay valid 20 min
        private const val CACHE_MAX = 12                // remember up to 12 episodes

        private class CachedLinks(val links: List<ExtractorLink>, val subs: List<SubtitleFile>, val ts: Long)

        private val linkCache = ConcurrentHashMap<String, CachedLinks>()
        private val prefetched = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val nextEpMap = ConcurrentHashMap<String, String>()
        private val prevEpMap = ConcurrentHashMap<String, String>()

        // survives after loadLinks returns — prefetch keeps running while you watch
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun trimCache() {
            while (linkCache.size > CACHE_MAX) {
                val oldest = linkCache.entries.minByOrNull { it.value.ts } ?: break
                linkCache.remove(oldest.key)
            }
        }
    }

    private fun schedulePrefetch(currentUrl: String) {
        if (prefetched.size > 60) prefetched.clear()
        // prefetch BOTH neighbors: next & previous (episode list order varies)
        val neighbors = listOfNotNull(nextEpMap[currentUrl], prevEpMap[currentUrl])
        for (n in neighbors) {
            if (linkCache.containsKey(n)) continue      // already cached
            if (!prefetched.add(n)) continue            // already prefetching/done
            bgScope.launch {
                try {
                    println("WitAnimeDebug: prefetch START $n")
                    prefetchEpisode(n)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun prefetchEpisode(url: String) {
        try {
            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
            val ok = extractLinks(url, { subs.add(it) }, { links.add(it) })
            // never cache local mega-proxy links — their token dies with the proxy
            val cacheable = links.filter { !it.url.startsWith("http://127.0.0.1") }
            if (ok && cacheable.isNotEmpty()) {
                linkCache[url] = CachedLinks(cacheable.toList(), subs.toList(), System.currentTimeMillis())
                trimCache()
                println("WitAnimeDebug: prefetched ${cacheable.size} links for $url")
            }
        } catch (_: Exception) {}
    }
    // ==================== [end cache + prefetch] ====================

    private val userAgent = EXTRACTOR_UA
    private val cfKiller = CloudflareKiller()
    private val wvResolver by lazy { WebViewResolver(interceptUrl = Regex("""witanime\.(you|cyou|net|tv|quest|red)""")) }

    @Suppress("DEPRECATION_ERROR")
    private fun renameLink(l: ExtractorLink, newName: String): ExtractorLink =
        ExtractorLink(l.source, newName, l.url, l.referer, l.quality, l.type, l.headers, l.extractorData)

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
        var epUrls = emptyList<String>()
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
                    if (eps != null) {
                        epUrls = eps.mapNotNull { it["url"]?.toString() }
                        episodes = eps.mapNotNull { ep ->
                            val epUrl = ep["url"]?.toString() ?: return@mapNotNull null
                            val epName = ep["number"]?.toString() ?: ep["title"]?.toString() ?: "حلقة"
                            // [NEXT-EP] parse real episode number (handles 5, "5", 5.0, "5.0")
                            // the app needs this to order episodes & enable next/prev + auto-next
                            val epNum = when (val raw = ep["number"]) {
                                is Int -> raw
                                is Long -> raw.toInt()
                                is Double -> raw.toInt()
                                else -> raw?.toString()?.trim()?.toDoubleOrNull()?.toInt()
                            }
                            newEpisode(epUrl) {
                                this.name = epName
                                this.episode = epNum
                            }
                        }
                    }
                }
            } catch (e: Exception) { logError(e) }
        }
        println("WitAnimeDebug: loaded '$title' episodes=${episodes.size}")

        // [PREFETCH] remember episode order (both directions) so we know what to preload
        for (i in epUrls.indices) {
            epUrls.getOrNull(i + 1)?.let { nextEpMap[epUrls[i]] = it }
            epUrls.getOrNull(i - 1)?.let { prevEpMap[epUrls[i]] = it }
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
        // [CACHE HIT] — instant: links were already resolved (prefetched or watched before)
        val cached = linkCache[data]
        if (cached != null && System.currentTimeMillis() - cached.ts < CACHE_TTL) {
            println("WitAnimeDebug: CACHE HIT — ${cached.links.size} links instantly for $data")
            cached.subs.forEach { subtitleCallback(it) }
            cached.links.forEach { callback(it) }
            schedulePrefetch(data)   // keep the chain rolling: preload the next-next episode
            return true
        }

        // Normal extraction, but capture everything emitted so we can cache it
        val capturedLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val capturedSubs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val capLink: (ExtractorLink) -> Unit = { capturedLinks.add(it); callback(it) }
        val capSub: (SubtitleFile) -> Unit = { capturedSubs.add(it); subtitleCallback(it) }

        val ok = extractLinks(data, capSub, capLink)

        // [CACHE STORE] — skip local mega-proxy links (their token expires)
        val cacheable = capturedLinks.filter { !it.url.startsWith("http://127.0.0.1") }
        if (cacheable.isNotEmpty()) {
            linkCache[data] = CachedLinks(cacheable.toList(), capturedSubs.toList(), System.currentTimeMillis())
            trimCache()
        }
        // [PREFETCH] — now that this episode is ready, preload its neighbors in background
        if (ok) schedulePrefetch(data)
        return ok
    }

    /** The full extraction pipeline (previously the body of loadLinks). */
    private suspend fun extractLinks(data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        fun cleanBase64Chars(s: String) = s.replace(Regex("[^A-Za-z0-9+/=]"), "")
        fun b64Bytes(i: String?) = if (i.isNullOrBlank()) ByteArray(0) else try { Base64.decode(i, Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        fun bytesStr(b: ByteArray) = if (b.isEmpty()) "" else try { String(b, Charsets.UTF_8) } catch (_: Exception) { try { String(b, Charset.forName("ISO-8859-1")) } catch (_: Exception) { b.joinToString("") { (it.toInt() and 0xFF).toChar().toString() } } }
        fun hexBytes(h: String?) = if (h.isNullOrBlank()) ByteArray(0) else { val c = h.replace(Regex("[^0-9a-fA-F]"), ""); if (c.length % 2 != 0) ByteArray(0) else c.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        fun xor(d: ByteArray, k: ByteArray) = if (k.isEmpty()) d else ByteArray(d.size) { i -> (d[i].toInt() xor k[i % k.size].toInt()).toByte() }
        fun trim(s: String?) = s?.replace(Regex("[\\x00\\u0000]"), "")?.trim() ?: ""

        suspend fun fetch(u: String) = try {
            app.get(u, headers = mapOf("User-Agent" to userAgent), referer = data, interceptor = cfKiller).text
        } catch (_: Exception) { "" }

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

        fun decodeWatch(raw: String, cfg: JSONObject?): String {
            return try {
                val cleaned = cleanBase64Chars(raw.reversed())
                val decoded = b64Bytes(cleaned)
                if (decoded.isEmpty()) return ""
                var off = 0
                if (cfg != null) {
                    val kIdx = String(b64Bytes(cfg.optString("k", ""))).trim().toIntOrNull() ?: 0
                    val d = cfg.optJSONArray("d")
                    if (d != null && kIdx in 0 until d.length()) off = d.optInt(kIdx)
                }
                val sliced = if (off in 1 until decoded.size) decoded.copyOf(decoded.size - off) else decoded
                trim(bytesStr(sliced))
            } catch (_: Exception) { "" }
        }

        fun decryptDownloads(html: String): List<String> {
            val out = mutableListOf<String>()
            try {
                val mR = Regex("""var\s+_m\s*=\s*\{\s*"r"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return emptyList()
                val secret = b64Bytes(mR)
                if (secret.isEmpty()) return emptyList()
                val pMap = mutableMapOf<Int, List<String>>()
                Regex("""var\s+_p(\d+)\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
                    val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                    pMap[n] = Regex(""""([^"]*)"""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
                }
                val seqList: List<String> = Regex("""var\s+_x\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                    ?.let { Regex(""""([^"]*)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
                    ?: Regex("""var\s+_s\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                    ?.let { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
                    ?: emptyList()
                for (i in seqList.indices) {
                    val chunks = pMap[i] ?: continue
                    val seqStr = trim(bytesStr(xor(hexBytes(seqList[i]), secret)))
                    val seqArr = try { JSONArray(seqStr) } catch (_: Exception) { null } ?: continue
                    val dec = chunks.map { trim(bytesStr(xor(hexBytes(it), secret))) }
                    val arranged = Array(seqArr.length()) { "" }
                    for (j in 0 until seqArr.length()) {
                        val pos = seqArr.optInt(j)
                        if (pos in arranged.indices) arranged[pos] = dec.getOrNull(j) ?: ""
                    }
                    out.add(arranged.joinToString(""))
                }
            } catch (e: Exception) { println("WitAnimeDebug: dl error: ${e.message}") }
            return out
        }

        return try {
            val html = fetch(data)
            if (html.isBlank()) { println("WitAnimeDebug: episode fetch EMPTY"); return false }

            val zT = Regex("""_zT\s*=\s*"([A-Za-z0-9+/=]{20,})"""").find(html)?.groupValues?.get(1)
            val zV = Regex("""_zV\s*=\s*"([A-Za-z0-9+/=]{20,})"""").find(html)?.groupValues?.get(1)
            val resArr = zT?.let { try { JSONArray(String(b64Bytes(it))) } catch (_: Exception) { null } }
            val cfgArr = zV?.let { try { JSONArray(String(b64Bytes(it))) } catch (_: Exception) { null } }
            val servers = findServers(html)
            val dlLinks = decryptDownloads(html).filter { !it.contains("mediafire", true) }
            println("WitAnimeDebug: resArr=${resArr?.length() ?: -1} servers=${servers.size} downloads=${dlLinks.size}")

            val semaphore = Semaphore(12)

            suspend fun decodeAndRoute(sid: String, label: String) {
                try {
                    val idx = sid.toIntOrNull() ?: -1
                    if (resArr == null || idx !in 0 until resArr.length()) { println("WitAnimeDebug: [$label] no registry"); return }
                    val link = decodeWatch(resArr.optString(idx), cfgArr?.optJSONObject(idx))
                    println("WitAnimeDebug: [$label] -> ${link.take(90)}")
                    if (link.isNotBlank()) {
                        val finalLink = if (link.matches(Regex("""^https://yonaplay\.net/embed\.php\?id=\d+$""")))
                            "$link&apiKey=$FRAMEWORK_HASH" else link
                        withTimeoutOrNull(20_000) {
                            routeLink(finalLink, data, subtitleCallback, callback)
                        } ?: println("WitAnimeDebug: [$label] TIMEOUT")
                    }
                } catch (_: Exception) {}
            }

            supervisorScope {
                val allJobs = mutableListOf<Deferred<Unit>>()

                servers.forEach { (sid, label) ->
                    allJobs += async(Dispatchers.IO) { semaphore.withPermit { decodeAndRoute(sid, label) } }
                }

                dlLinks.forEach { dl ->
                    allJobs += async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val idx = dl.indexOf("http")
                                val final = trim(if (idx >= 0) dl.substring(idx) else dl)
                                if (final.startsWith("http"))
                                    withTimeoutOrNull(20_000) { routeLink(final, data, subtitleCallback, callback) }
                            } catch (_: Exception) {}
                        }
                    }
                }

                allJobs.awaitAll()
            }
            true
        } catch (e: Exception) { logError(e); false }
    }

    private suspend fun routeLink(link: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, qLabel: String? = null) {
        val emit: (ExtractorLink) -> Unit = { l ->
            val cleaned = l.name
                .replace(Regex("""(?i)\b(4k|2160p|1440p|1080p|720p|480p|360p|240p|fhd|hd|sd)\b"""), "")
                .replace(Regex("""\(\s*\)"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            if (cleaned.isBlank() || cleaned == l.name) callback(l) else callback(renameLink(l, cleaned))
        }

        println("WitAnimeDebug: routing -> $link")
        val host = linkHost(link)
        when {
            host.contains("yonaplay") -> decodeYonaplayAndLoad(link, subtitleCallback, emit)
            host.contains("dotplay") -> DotPlayExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            host.contains("soraplay") -> SoraplayExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            isDirectCdnLink(link) -> emitDirectCdn(link, qLabel, emit)
            host.contains("videa.hu") -> VideaExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("videas.fr") -> VideasFrExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("ok.ru") || host.contains("odnoklassniki") -> OkRuExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("my.mail.ru") || link.contains("/video/embed/", true) -> MailruExtractor().getUrl(link, referer, subtitleCallback, emit)
            isMegaLink(link) -> MegaExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            isDoodLink(link) -> DoodExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("filemoon") -> FileMoonExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("4shared") -> FourSharedExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            host.contains("mediafire") -> { /* removed */ }
            isStreamWishLink(link) -> handleUnknownEmbed(link, referer, "StreamWish", subtitleCallback, emit)
            else -> {
                val ok = loadExtractor(link, "$mainUrl/", subtitleCallback, emit)
                if (!ok) handleUnknownEmbed(link, referer, "Stream", subtitleCallback, emit)
            }
        }
    }

    private suspend fun decodeYonaplayAndLoad(yonaplayUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = app.get(yonaplayUrl, referer = "$mainUrl/", headers = mapOf("User-Agent" to userAgent), interceptor = cfKiller)
            val html = res.text
            println("WitAnimeDebug: Yona len=${html.length} final=${res.url}")

            val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

            coroutineScope {
                val semaphore = Semaphore(10)
                val jobs = mutableListOf<Deferred<Unit>>()

                Regex(
                    """<li[^>]*onclick="go_to_player\('([A-Za-z0-9+/=]+)'\)"[^>]*>\s*(?:<img[^>]*>\s*)?<span>\s*([^<]*?)\s*</span>\s*<p>\s*([^<]*?)\s*</p>""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(html).forEach { m ->
                    val b64 = m.groupValues[1]
                    val hostLabel = m.groupValues[2].trim()
                    val qLabel = m.groupValues[3].trim().trimEnd('-', ' ').trim()
                    jobs += async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val decoded = String(Base64.decode(b64, Base64.DEFAULT)).trim()
                                if (decoded.startsWith("http") && seen.add(decoded)) {
                                    println("WitAnimeDebug: Yona [$hostLabel | $qLabel] -> ${decoded.take(90)}")
                                    if (decoded.contains("drive.google.com/file/d/")) {
                                        Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)?.groupValues?.get(1)?.let { fid ->
                                            callback(newExtractorLink("Yonaplay", "Google Drive ($qLabel)",
                                                "https://drive.usercontent.google.com/download?id=$fid&export=download&confirm=t", ExtractorLinkType.VIDEO) {
                                                referer = "https://drive.google.com/"; quality = labelQuality(qLabel)
                                            })
                                        }
                                    } else {
                                        withTimeoutOrNull(15_000) { routeLink(decoded, yonaplayUrl, subtitleCallback, callback, qLabel) }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""").findAll(html).map { it.groupValues[1] }.forEach { b64 ->
                    jobs += async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val decoded = String(Base64.decode(b64, Base64.DEFAULT)).trim()
                                if (decoded.startsWith("http") && seen.add(decoded)) {
                                    withTimeoutOrNull(15_000) { routeLink(decoded, yonaplayUrl, subtitleCallback, callback) }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                Regex("""https?://(?:mega\.nz|mega\.co\.nz|www\.4shared\.com|drive\.google\.com|workupload\.com|gofile\.io)/[^\s"'<>]+""").findAll(html).forEach {
                    val url = it.value
                    jobs += async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (seen.add(url)) withTimeoutOrNull(15_000) { routeLink(url, yonaplayUrl, subtitleCallback, callback) }
                        }
                    }
                }

                Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html).forEach { m ->
                    var src = m.groupValues[1]
                    if (src.startsWith("//")) src = "https:$src"
                    if (src.startsWith("http") && !src.contains("yonaplay") && !src.contains("dotplay")) {
                        val finalSrc = src
                        jobs += async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (seen.add(finalSrc)) withTimeoutOrNull(15_000) { routeLink(finalSrc, yonaplayUrl, subtitleCallback, callback) }
                            }
                        }
                    }
                }

                jobs.awaitAll()
            }

            Regex("""https?://[^\s"'<>]+""").findAll(html).map { it.value.trimEnd('"', '\'', ')', ';', ',') }
                .filter { isDirectCdnLink(it) }.distinct().forEach { cdn ->
                    if (seen.add(cdn)) emitDirectCdn(cdn, callback = callback)
                }

            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(html).forEach { m ->
                val url = m.groupValues[1]
                if (!url.contains("googleapis") && !url.contains("drive.google") && !isDirectCdnLink(url) && seen.add(url)) {
                    callback(newExtractorLink("Yonaplay", "Yonaplay Direct", url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = yonaplayUrl; quality = Qualities.Unknown.value
                    })
                }
            }
            println("WitAnimeDebug: Yona emitted=${seen.size}")
        } catch (e: Exception) { println("WitAnimeDebug: Yonaplay error: ${e.message}") }
    }
}
