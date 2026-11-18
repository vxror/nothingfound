package com.witanime

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val LINK_CACHE_TTL = 45 * 60_000L
        private const val LINK_CACHE_MAX = 12
        private const val STORE_FILE = "wita_link_cache.json"

        private class CachedLinks(val links: List<ExtractorLink>, val subs: List<SubtitleFile>, val ts: Long)

        private val linkCache = ConcurrentHashMap<String, CachedLinks>()
        private val inFlight = ConcurrentHashMap<String, Job>()
        private val nextEpMap = ConcurrentHashMap<String, String>()
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val persistMutex = Mutex()

        @Volatile private var storeLoaded = false
        @Volatile private var diskCtx: Context? = null

        private fun ctx(): Context? {
            diskCtx?.let { return it }
            val c = runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val cur = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
                at.getMethod("getApplication").invoke(cur) as? Context
            }.getOrNull()
            diskCtx = c
            return c
        }

        private fun linkToJson(l: ExtractorLink): JSONObject = JSONObject()
            .put("s", l.source).put("n", l.name).put("u", l.url)
            .put("r", l.referer ?: "").put("q", l.quality)
            .put("t", l.type?.name ?: "").put("x", l.extractorData ?: "")
            .put("h", JSONObject().apply { l.headers.forEach { (k, v) -> put(k, v) } })

        @Suppress("DEPRECATION_ERROR")
        private fun linkFromJson(o: JSONObject): ExtractorLink {
            val typeStr = o.optString("t")
            var type: ExtractorLinkType? = null
            if (typeStr.isNotBlank()) for (t in ExtractorLinkType.values()) if (t.name == typeStr) { type = t; break }
            val extra = o.optString("x")
            val hdrs = mutableMapOf<String, String>()
            o.optJSONObject("h")?.let { h -> h.keys().forEach { k -> hdrs[k] = h.optString(k) } }
            return ExtractorLink(o.optString("s"), o.optString("n"), o.optString("u"), o.optString("r"),
                o.optInt("q"), type, hdrs, if (extra.isBlank()) null else extra)
        }

        private fun subToJson(s: SubtitleFile): JSONObject = JSONObject().put("l", s.lang).put("u", s.url)
        private fun subFromJson(o: JSONObject) = SubtitleFile(o.optString("l"), o.optString("u"))

        private fun ensureStoreLoaded() {
            if (storeLoaded) return
            synchronized(this) {
                if (storeLoaded) return
                storeLoaded = true
                try {
                    val f = File(ctx()?.filesDir, STORE_FILE)
                    if (!f.exists()) return
                    val root = JSONObject(f.readText())
                    val links = root.optJSONObject("links") ?: return
                    val eps = root.optJSONObject("eps") ?: JSONObject()
                    eps.keys().forEach { k -> nextEpMap[k] = eps.optString(k) }
                    links.keys().forEach { epUrl ->
                        val o = links.optJSONObject(epUrl) ?: return@forEach
                        val ts = o.optLong("ts")
                        if (System.currentTimeMillis() - ts > LINK_CACHE_TTL) return@forEach
                        val arr = o.optJSONArray("l") ?: return@forEach
                        val ls = (0 until arr.length()).mapNotNull { i -> runCatching { linkFromJson(arr.optJSONObject(i)) }.getOrNull() }
                        val sarr = o.optJSONArray("s") ?: JSONArray()
                        val ss = (0 until sarr.length()).mapNotNull { i -> runCatching { subFromJson(sarr.optJSONObject(i)) }.getOrNull() }
                        if (ls.isNotEmpty()) linkCache[epUrl] = CachedLinks(ls, ss, ts)
                    }
                    WitaLog.d("restored ${linkCache.size} cached episodes")
                } catch (e: Exception) { WitaLog.e("store load fail ${e.message}") }
            }
        }

        private fun persistStoreAsync() {
            bgScope.launch {
                persistMutex.withLock {
                    try {
                        val links = JSONObject()
                        linkCache.forEach { (ep, c) ->
                            links.put(ep, JSONObject().put("ts", c.ts)
                                .put("l", JSONArray(c.links.map { linkToJson(it) }))
                                .put("s", JSONArray(c.subs.map { subToJson(it) })))
                        }
                        val eps = JSONObject()
                        nextEpMap.forEach { (k, v) -> eps.put(k, v) }
                        ctx()?.let { File(it.filesDir, STORE_FILE).writeText(JSONObject().put("links", links).put("eps", eps).toString()) }
                    } catch (e: Exception) { WitaLog.e("store persist fail ${e.message}") }
                }
            }
        }

        private fun trimCache() {
            while (linkCache.size > LINK_CACHE_MAX) {
                linkCache.entries.minByOrNull { it.value.ts }?.let { linkCache.remove(it.key) } ?: break
            }
        }

        private fun putCache(url: String, links: List<ExtractorLink>, subs: List<SubtitleFile>) {
            linkCache[url] = CachedLinks(links, subs, System.currentTimeMillis())
            trimCache()
            persistStoreAsync()
        }

        private fun deliverCached(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            ensureStoreLoaded()
            val c = linkCache[url] ?: return false
            if (System.currentTimeMillis() - c.ts > LINK_CACHE_TTL) { linkCache.remove(url); return false }
            val links = if (WitaState.isCasting) c.links.filter { !it.url.startsWith("http://127.0.0.1") } else c.links
            if (links.isEmpty()) return false
            WitaLog.d("CACHE HIT — ${links.size} links")
            c.subs.forEach { runCatching { subtitleCallback(it) } }
            links.forEach { runCatching { callback(it) } }
            return true
        }

        private fun qualityRank(q: String): Int = when (q.uppercase().trim()) {
            "4K", "2160P" -> 400
            "FHD", "1080P" -> 300
            "HD", "720P" -> 200
            "SD", "480P" -> 100
            else -> 50
        }
    }

    @Volatile private var cfMode = false
    private fun ua(): String = if (cfMode) (WitaWeb.webUa ?: EXTRACTOR_UA) else EXTRACTOR_UA

    init {
        WitaWeb.warmup()
        bgScope.launch { ensureStoreLoaded() }
    }

    private suspend fun siteHtml(url: String, silent: Boolean = false): String? {
        val headers = mapOf(
            "User-Agent" to ua(),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
        )
        val body = withTimeoutOrNull(15_000L) { sessionGet(url, referer = "$mainUrl/", headers = headers) }
        if (body != null && body.isNotBlank() && !WitaWeb.looksChallenge(body)) { cfMode = false; return body }
        WitaLog.d("[$url] challenge → WebView render")
        val rendered = WitaWeb.fetchHtml(url, silent) ?: return null
        cfMode = true
        injectWebViewCookies(url)
        return rendered
    }

    private suspend fun fetchDoc(url: String): Document {
        val body = siteHtml(url) ?: throw ErrorLoadingException("فشل تحميل الموقع — تحقق من الاتصال")
        return Jsoup.parse(body, url)
    }

    private fun smartPoster(raw: String?): String? {
        val fixed = fixUrlNull(raw) ?: return null
        if (WitaWeb.replayWorks() == false) return WitaImgProxy.proxyUrl(fixed) ?: fixed
        return fixed
    }

    @Suppress("DEPRECATION_ERROR")
    private fun renameLink(l: ExtractorLink, newName: String): ExtractorLink =
        ExtractorLink(l.source, newName, l.url, l.referer, l.quality, l.type, l.headers, l.extractorData)

    private fun cleanLinkName(name: String): String = name
        .replace(Regex("""(?i)\b(4k|2160p|1440p|1080p|720p|480p|360p|240p|fhd|hd|sd)\b"""), "")
        .replace(Regex("""\(\s*\)"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDoc(mainUrl)
        val homePageList = ArrayList<HomePageList>()
        document.select("section.mb-14").forEach { section ->
            val title = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            val items = section.select("a[href*='/watch/'], a[href*='/anime/'], a[href*='/movie/']").mapNotNull { card ->
                val href = card.attr("href")
                if (!href.contains("/watch/") && !href.contains("/anime/") && !href.contains("/movie/")) return@mapNotNull null
                val name = card.selectFirst("h3")?.text()?.trim()
                    ?: card.attr("title").ifBlank { null }
                    ?: card.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: return@mapNotNull null
                val epText = card.selectFirst("div:contains(الحلقة)")?.text()?.trim()
                val finalTitle = if (!epText.isNullOrEmpty() && !name.contains("الحلقة")) "$name - $epText" else name
                val isMovie = href.contains("/movie/")
                newAnimeSearchResponse(finalTitle, fixUrl(href), if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                    posterUrl = smartPoster(card.selectFirst("img")?.attr("src"))
                }
            }.distinctBy { it.url }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }
        document.selectFirst("aside")?.let { aside ->
            val asideTitle = aside.selectFirst("h2")?.text()?.trim() ?: "أشهر أنميات الموسم"
            val asideItems = aside.select("a[href*='/anime/']").mapNotNull { item ->
                val href = item.attr("href")
                val name = item.selectFirst("span[dir='ltr']")?.text()?.trim() ?: return@mapNotNull null
                newAnimeSearchResponse(name, fixUrl(href), TvType.Anime) {
                    posterUrl = smartPoster(item.selectFirst("img")?.attr("src"))
                }
            }.distinctBy { it.url }
            if (asideItems.isNotEmpty()) homePageList.add(HomePageList(asideTitle, asideItems))
        }
        return newHomePageResponse(homePageList, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")
        val document = try { fetchDoc(url) } catch (e: Exception) { return emptyList() }
        return document.select("div.grid a[href*='/anime/'], div.grid a[href*='/movie/']").mapNotNull { card ->
            val href = card.attr("href")
            val title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
            val isMovie = href.contains("/movie/")
            newAnimeSearchResponse(title, fixUrl(href), if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                posterUrl = smartPoster(card.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)
        val title = document.selectFirst("h1 span[dir='ltr']")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?.replace("مترجم", "")?.replace("اون لاين", "")?.replace("فيلم", "")?.replace("انمي", "")?.trim()
            ?: ""
        val poster = document.selectFirst("img[src*='/posters/']")?.attr("src")
            ?: document.selectFirst("div.shrink-0 img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst("p.leading-relaxed")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val isMovie = url.contains("/movie/")
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        val genres = document.select("div.flex-wrap.gap-2 span.rounded-full").map { it.text().trim() }.filter { it.isNotEmpty() }

        var year: Int? = null
        var status = ShowStatus.Completed
        if (document.selectFirst("span:contains(مستمر)") != null) status = ShowStatus.Ongoing
        document.select("div.grid > div").forEach { box ->
            val label = box.selectFirst("span")?.text()?.trim() ?: ""
            val value = box.selectFirst("p")?.text()?.trim() ?: ""
            if (label.contains("السنة")) year = Regex("""\d{4}""").find(value)?.value?.toIntOrNull() ?: year
        }
        val rating = document.selectFirst("span[dir='ltr']:has(svg)")?.text()?.trim()?.toDoubleOrNull()

        val episodes = mutableListOf<Episode>()
        if (isMovie) {
            val watchUrl = document.selectFirst("a[href*='/watch/movie/']")?.attr("href") ?: url
            episodes.add(newEpisode(fixUrl(watchUrl)) { this.name = title; this.episode = 1; this.posterUrl = smartPoster(poster) })
        } else {
            document.select("div[data-episode-list] a[href*='/watch/']").forEach { epTag ->
                val epHref = epTag.attr("href")
                val epNum = Regex("""/(\d+)$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+""").find(epTag.text())?.value?.toIntOrNull()
                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name = "الحلقة ${epNum ?: ""}".trim()
                    this.episode = epNum
                    this.posterUrl = smartPoster(poster)
                })
            }
            episodes.sortBy { it.episode ?: Int.MAX_VALUE }
        }
        val distinct = episodes.distinctBy { it.data }

        val epUrls = distinct.map { it.data }
        var changed = false
        for (i in epUrls.indices) {
            val nxt = epUrls.getOrNull(i + 1)
            if (nxt != null && nextEpMap[epUrls[i]] != nxt) { nextEpMap[epUrls[i]] = nxt; changed = true }
        }
        if (nextEpMap.size > 400) nextEpMap.clear()
        if (changed) persistStoreAsync()
        val first = epUrls.firstOrNull()
        if (first != null && linkCache[first] == null && !inFlight.containsKey(first)) schedulePrefetch(first, forceFirst = first)

        WitaLog.d("loaded '$title' episodes=${distinct.size}")
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = smartPoster(poster)
            this.plot = description
            this.tags = genres
            this.year = year
            this.showStatus = status
            this.score = rating?.let { Score.from10(it) }
            addEpisodes(DubStatus.Subbed, distinct)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ensureStoreLoaded()
        WitaState.isCasting = isCasting
        if (deliverCached(data, subtitleCallback, callback)) { schedulePrefetch(data); return true }
        val pending = inFlight[data]
        if (pending != null) {
            if (pending.isActive) { WitaLog.d("prefetch in-flight — waiting"); withTimeoutOrNull(15_000) { pending.join() } }
            if (deliverCached(data, subtitleCallback, callback)) { schedulePrefetch(data); return true }
            inFlight.remove(data, pending)
        }
        val capturedSubs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val subCb: (SubtitleFile) -> Unit = { s -> subtitleCallback(s); capturedSubs.add(s) }
        val links = extractLinks(data, subCb, callback)
        if (links.isNotEmpty()) { putCache(data, links, capturedSubs.toList()); schedulePrefetch(data); return true }
        return false
    }

    private fun schedulePrefetch(currentUrl: String, forceFirst: String? = null) {
        if (inFlight.size > 6) return
        val next = forceFirst ?: nextEpMap[currentUrl] ?: return
        prefetchOne(next)
        nextEpMap[next]?.let { prefetchOne(it) }
    }

    private fun prefetchOne(epUrl: String) {
        val c = linkCache[epUrl]
        if (c != null && System.currentTimeMillis() - c.ts < LINK_CACHE_TTL) return
        if (inFlight.containsKey(epUrl)) return
        WitaLog.d("prefetch START $epUrl")
        lateinit var job: Job
        job = bgScope.launch(start = CoroutineStart.LAZY) {
            try {
                val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
                val links = extractLinks(epUrl, { subs.add(it) }, {}, silent = true)
                if (links.isNotEmpty()) { putCache(epUrl, links, subs.toList()); WitaLog.d("prefetched ${links.size} links") }
                else WitaLog.d("prefetch EMPTY ($epUrl)")
            } catch (e: Exception) { WitaLog.e("prefetch error: ${e.message}") }
            finally { inFlight.remove(epUrl, job) }
        }
        if (inFlight.putIfAbsent(epUrl, job) == null) job.start()
    }

    data class ServerItem(val quality: String, val label: String, val token: String, val isStream: Boolean)
    private data class GateResult(val success: Boolean, val isRateLimited: Boolean, val remaining: Int = -1, val embedUrl: String? = null)

    private fun gateHeaders(csrf: String, watchUrl: String): Map<String, String> = mapOf(
        "User-Agent" to ua(),
        "Accept" to "application/json",
        "X-CSRF-TOKEN" to csrf,
        "Origin" to mainUrl,
        "Referer" to watchUrl,
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Dest" to "empty",
    )

    private fun unlockGate(s: ServerItem, csrf: String, watchUrl: String): GateResult {
        val encoded = URLEncoder.encode(s.token, "UTF-8")
        val empty = ByteArray(0).toRequestBody(null)
        return try {
            if (s.isStream) {
                var gatePath = "/watch/stream-gate/$encoded"
                var refPolicy = "no-referrer"
                val sReq = Request.Builder().url("$mainUrl/watch/stream-source/$encoded").post(empty)
                    .apply { gateHeaders(csrf, watchUrl).forEach { (k, v) -> header(k, v) } }.build()
                sessionClient.newCall(sReq).execute().use { r ->
                    val rem = r.header("x-ratelimit-remaining")?.toIntOrNull() ?: -1
                    if (r.code == 429) return GateResult(false, true, rem)
                    val b = r.body?.string().orEmpty()
                    if (!r.isSuccessful) return GateResult(false, false, rem)
                    runCatching { JSONObject(b) }.getOrNull()?.let { j ->
                        j.optString("gateUrl").takeIf { it.isNotBlank() }?.let { gatePath = it }
                        j.optString("referrerPolicy").takeIf { it.isNotBlank() }?.let { refPolicy = it }
                    }
                }
                val fullGateUrl = fixUrl(gatePath)
                val gb = Request.Builder().url(fullGateUrl).get()
                    .header("User-Agent", ua())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "iframe")
                    .header("Upgrade-Insecure-Requests", "1")
                if (refPolicy.equals("origin", true)) gb.header("Referer", "$mainUrl/")
                noRedirectSessionClient.newCall(gb.build()).execute().use { r ->
                    val rem = r.header("x-ratelimit-remaining")?.toIntOrNull() ?: -1
                    if (r.code == 429) return GateResult(false, true, rem)
                    var embed = r.header("Location")
                    if (embed.isNullOrBlank()) {
                        val b = r.body?.string().orEmpty()
                        val doc = Jsoup.parse(b, fullGateUrl)
                        embed = doc.selectFirst("iframe[src]")?.attr("src")
                            ?: Regex("""url=['"]([^'"]+)['"]""").find(b)?.groupValues?.get(1)
                            ?: Regex("""href=["'](https?://[^"']+)["']""").find(b)?.groupValues?.get(1)
                    }
                    if (!embed.isNullOrBlank() && embed.startsWith("http")) return GateResult(true, false, rem, fixUrl(embed))
                }
            } else {
                val sReq = Request.Builder().url("$mainUrl/watch/download-source/$encoded").post(empty)
                    .apply { gateHeaders(csrf, watchUrl).forEach { (k, v) -> header(k, v) } }.build()
                sessionClient.newCall(sReq).execute().use { r ->
                    val rem = r.header("x-ratelimit-remaining")?.toIntOrNull() ?: -1
                    if (r.code == 429) return GateResult(false, true, rem)
                    if (!r.isSuccessful) return GateResult(false, false, rem)
                }
                val gReq = Request.Builder().url("$mainUrl/watch/download-gate/$encoded").get()
                    .header("User-Agent", ua())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Referer", watchUrl)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()
                noRedirectSessionClient.newCall(gReq).execute().use { r ->
                    val rem = r.header("x-ratelimit-remaining")?.toIntOrNull() ?: -1
                    if (r.code == 429) return GateResult(false, true, rem)
                    val loc = r.header("Location")
                    if (!loc.isNullOrBlank()) return GateResult(true, false, rem, fixUrl(loc.trim()))
                }
            }
            GateResult(false, false)
        } catch (_: Exception) { GateResult(false, false) }
    }

    private suspend fun extractLinks(
        data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, silent: Boolean = false
    ): List<ExtractorLink> {
        data class Entry(val serverIdx: Int, val order: Long, val link: ExtractorLink)
        val collected = Collections.synchronizedList(mutableListOf<Entry>())
        fun mkAt(serverIdx: Int, base: Long): (Long) -> (ExtractorLink) -> Unit = { sub ->
            { l -> collected.add(Entry(serverIdx, base + sub, l)) }
        }
        return try {
            val watchUrl = fixUrl(data.substringBefore('?').trimEnd('/'))
            val html = siteHtml(watchUrl, silent) ?: run { WitaLog.d("watch page EMPTY"); return emptyList() }
            val csrf = Regex("""name=['"]csrf-token['"]\s+content=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-csrf=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?: run { WitaLog.e("no CSRF token"); return emptyList() }
            val rawSources = Regex("""sourcesUrl\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.trim() ?: "$watchUrl/sources"
            val sourcesUrl = fixUrl(rawSources)
            WitaLog.d("sources → $sourcesUrl")

            delay(300)
            val sourcesJson = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(sourcesUrl).post(ByteArray(0).toRequestBody(null))
                        .apply { gateHeaders(csrf, watchUrl).forEach { (k, v) -> header(k, v) } }.build()
                    sessionClient.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                } catch (_: Exception) { null }
            } ?: run { WitaLog.e("sources POST failed"); return emptyList() }

            val root = try { JSONObject(sourcesJson) } catch (_: Exception) { return emptyList() }
            val allServers = mutableListOf<ServerItem>()
            fun extractGroup(players: JSONObject?, downloads: JSONObject?) {
                players?.let { p ->
                    for (q in p.keys()) {
                        val arr = p.optJSONArray(q) ?: continue
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val token = o.optString("token")
                            val label = o.optString("label").ifBlank { o.optString("name", "Server") }
                            if (token.isNotBlank()) allServers.add(ServerItem(q, label, token, true))
                        }
                    }
                }
                downloads?.let { d ->
                    for (q in d.keys()) {
                        val arr = d.optJSONArray(q) ?: continue
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val token = o.optString("token")
                            val label = o.optString("label").ifBlank { o.optString("name", "Download") }
                            if (token.isNotBlank()) allServers.add(ServerItem(q, label, token, false))
                        }
                    }
                }
            }
            extractGroup(root.optJSONObject("players"), root.optJSONObject("downloads"))
            root.optJSONObject("versions")?.let { v ->
                v.keys().forEach { ver ->
                    val sub = v.optJSONObject(ver) ?: return@forEach
                    extractGroup(sub.optJSONObject("players"), sub.optJSONObject("downloads"))
                }
            }
            if (allServers.isEmpty()) { WitaLog.d("no servers"); return emptyList() }

            val servers = allServers
                .sortedWith(compareByDescending<ServerItem> { it.isStream }.thenByDescending { qualityRank(it.quality) })
                .take(10)
            WitaLog.d("${allServers.size} servers → trying ${servers.size}")

            val semaphore = Semaphore(3)
            val emittedUrls = HashSet<String>()
            val emitLock = Any()
            fun emitWave() {
                val ordered = synchronized(emitLock) {
                    synchronized(collected) { collected.toList() }
                        .filter { emittedUrls.add(it.link.url) }
                        .sortedWith(compareBy<Entry> { it.order }.thenByDescending { it.link.quality }.thenBy { it.link.name })
                }
                ordered.forEach { runCatching { callback(it.link) } }
            }

            supervisorScope {
                val flusher = launch { while (isActive) { delay(1_200L); emitWave() } }
                val tasks = mutableListOf<Deferred<Unit>>()
                var quotaDead = false
                for ((idx, server) in servers.withIndex()) {
                    if (quotaDead) break
                    val gate = withContext(Dispatchers.IO) { unlockGate(server, csrf, watchUrl) }
                    if (gate.isRateLimited || gate.remaining == 0) {
                        WitaLog.d("[${server.label}] gate quota reached — stopping")
                        quotaDead = true
                    }
                    val embed = gate.embedUrl
                    if (gate.success && !embed.isNullOrBlank()) {
                        WitaLog.d("[${server.label} | ${server.quality}] → $embed")
                        tasks.add(async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val budget = if (embed.contains("yonaplay")) 75_000L else 25_000L
                                withTimeoutOrNull(budget) {
                                    routeLink(embed, watchUrl, subtitleCallback, mkAt(idx, idx * 1000L), server.quality.uppercase())
                                } ?: WitaLog.d("[${server.label}] TIMEOUT")
                            }
                        })
                    }
                    if (!quotaDead && idx < servers.size - 1) delay(800)
                }
                tasks.awaitAll()
                flusher.cancel()
            }
            emitWave()
            synchronized(collected) { collected.toList() }.map { it.link }
        } catch (e: Exception) { logError(e); emptyList() }
    }

    private suspend fun routeLink(
        link: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit,
        emitAt: (Long) -> (ExtractorLink) -> Unit, qLabel: String? = null, selfOrder: Long = 0L
    ) {
        val rawCb = emitAt(selfOrder)
        val emit: (ExtractorLink) -> Unit = { l ->
            val cleaned = cleanLinkName(l.name)
            if (cleaned.isBlank() || cleaned == l.name) rawCb(l) else rawCb(renameLink(l, cleaned))
        }
        WitaLog.d("routing -> $link")
        val host = linkHost(link)
        when {
            host.contains("yonaplay") -> decodeYonaplayAndLoad(link, subtitleCallback, emitAt)
            host.contains("dotplay") -> DotPlayExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            host.contains("soraplay") -> SoraplayExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            isHgCloudLink(link) -> HgCloudExtractor().getUrl(link, referer, subtitleCallback, emit)
            isDirectCdnLink(link) -> emitDirectCdn(link, qLabel, emit)
            host.contains("drive.google") || host.contains("drive.usercontent") ->
                GoogleDriveExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            host.contains("gofile.io") -> GoFileExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("videa.hu") -> VideaExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("videas.fr") -> VideasFrExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("videas.com") -> VideasExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("ok.ru") || host.contains("odnoklassniki") -> OkRuExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("my.mail.ru") || link.contains("/video/embed/", true) -> MailruExtractor().getUrl(link, referer, subtitleCallback, emit)
            isMegaLink(link) -> MegaExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            isDoodLink(link) -> DoodExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("filemoon") -> FileMoonExtractor().getUrl(link, referer, subtitleCallback, emit)
            host.contains("4shared") -> FourSharedExtractor().apply { linkLabel = qLabel }.getUrl(link, referer, subtitleCallback, emit)
            host.contains("mediafire") -> { }
            host.contains("workupload") -> { }
            isStreamWishLink(link) -> handleUnknownEmbed(link, referer, "StreamWish", subtitleCallback, emit)
            else -> {
                val ok = loadExtractor(link, "$mainUrl/", subtitleCallback, emit)
                if (!ok) handleUnknownEmbed(link, referer, "Stream", subtitleCallback, emit)
            }
        }
    }

    private suspend fun tryYonaApi(
        yonaplayUrl: String, subtitleCallback: (SubtitleFile) -> Unit, emitAt: (Long) -> (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val origin = hostOf(yonaplayUrl)
            var html = withTimeoutOrNull(12_000L) { sessionGet(yonaplayUrl, referer = "$mainUrl/") } ?: return false
            if (WitaWeb.looksChallenge(html)) html = siteHtml(yonaplayUrl) ?: return false

            var code = ""; var embedToken = ""; var pageKey = ""
            Regex("""window\._E\s*=\s*(\{[^;]+?\})""").find(html)?.groupValues?.get(1)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let {
                    code = it.optString("c").trim(); embedToken = it.optString("t").trim(); pageKey = it.optString("k").trim()
                }
            }
            val apiHeaders = mutableMapOf("Origin" to origin, "Accept" to "application/json")
            if (code.isEmpty() || pageKey.isEmpty()) {
                val initRaw = withTimeoutOrNull(10_000L) {
                    sessionPost("$origin/api/init-session.php", "{}", referer = yonaplayUrl, headers = apiHeaders)
                } ?: return false
                val initObj = runCatching { JSONObject(initRaw) }.getOrNull() ?: return false
                code = initObj.optString("c").trim(); embedToken = initObj.optString("t").trim(); pageKey = initObj.optString("k").trim()
            }
            if (code.isEmpty() || pageKey.isEmpty()) return false
            if (embedToken.isNotEmpty()) apiHeaders["X-Embed-Token"] = embedToken

            val srcRaw = withTimeoutOrNull(10_000L) {
                sessionPost("$origin/api/sources.php", JSONObject().put("code", code).toString(), referer = yonaplayUrl, headers = apiHeaders)
            } ?: return false
            val qualities = runCatching { JSONObject(srcRaw).optJSONObject("qualities") }.getOrNull() ?: return false

            var idx = 0L
            var any = false
            for (qKey in listOf("4k", "fhd", "hd", "sd")) {
                val group = qualities.optJSONObject(qKey) ?: continue
                val serversArr = group.optJSONArray("servers") ?: continue
                val qVal = labelQuality(qKey)
                for (i in 0 until minOf(serversArr.length(), 2)) {
                    val srv = serversArr.optJSONObject(i) ?: continue
                    val srvName = srv.optString("name", "Server").trim()
                    val srvToken = srv.optString("token").trim()
                    if (srvToken.isEmpty()) continue
                    val payload = JSONObject().put("code", code).put("token", srvToken).put("key", pageKey).toString()
                    val aRaw = withTimeoutOrNull(8_000L) {
                        sessionPost("$origin/api/api.php", payload, referer = yonaplayUrl, headers = apiHeaders)
                    } ?: continue
                    val encD = runCatching { JSONObject(aRaw).optString("d").trim() }.getOrDefault("")
                    if (encD.isEmpty()) continue
                    val decoded = aesGcmDecrypt(encD, pageKey)?.takeIf { it.startsWith("http") } ?: continue
                    WitaLog.d("YonaAPI [$srvName | ${qKey.uppercase()}] -> ${decoded.take(90)}")
                    val myIdx = idx++
                    val knownEmbed = isHgCloudLink(decoded) || isStreamWishLink(decoded) ||
                        listOf("dotplay", "yonaplay", "mega", "dood", "d000d", "ok.ru",
                            "videa", "4shared", "gofile", "drive.google", "filemoon", "mail.ru").any { decoded.contains(it) }
                    if (!knownEmbed && (decoded.contains(".m3u8") || decoded.contains(".mp4"))) {
                        emitAt(myIdx)(newExtractorLink("Yonaplay", "Yonaplay ($srvName ${qKey.uppercase()})", decoded,
                            if (decoded.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            referer = "$origin/"; quality = qVal
                        })
                        any = true
                    } else {
                        withTimeoutOrNull(12_000L) { routeLink(decoded, yonaplayUrl, subtitleCallback, emitAt, qKey.uppercase(), myIdx) }
                        any = true
                    }
                }
            }
            if (any) WitaLog.d("YonaAPI SUCCESS ($idx servers)")
            any
        } catch (e: Exception) { WitaLog.d("YonaAPI fail: ${e.message}"); false }
    }

    private suspend fun decodeYonaplayAndLoad(
        yonaplayUrl: String, subtitleCallback: (SubtitleFile) -> Unit, emitAt: (Long) -> (ExtractorLink) -> Unit
    ) {
        try {
            if (tryYonaApi(yonaplayUrl, subtitleCallback, emitAt)) return
            val html = siteHtml(yonaplayUrl) ?: run { WitaLog.w("Yona fetch failed"); return }
            WitaLog.d("Yona legacy len=${html.length}")
            val seen = mutableSetOf<String>()
            var idx = 0L

            Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""").findAll(html).map { it.groupValues[1] }.forEach { b64 ->
                val myIdx = idx++
                try {
                    val decoded = String(Base64.decode(b64, Base64.DEFAULT)).trim()
                    if (decoded.startsWith("http") && seen.add(decoded))
                        withTimeoutOrNull(10_000) { routeLink(decoded, yonaplayUrl, subtitleCallback, emitAt, null, myIdx) }
                } catch (_: Exception) {}
            }
            Regex("""https?://(?:mega\.nz|mega\.co\.nz|www\.4shared\.com|drive\.google\.com|gofile\.io|hgcloud\.[a-z]+|vibuxer\.com)/[^\s"'<>]+""").findAll(html).forEach {
                val myIdx = idx++
                if (seen.add(it.value)) withTimeoutOrNull(10_000) { routeLink(it.value, yonaplayUrl, subtitleCallback, emitAt, null, myIdx) }
            }
            Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html).forEach { m ->
                var src = m.groupValues[1]
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http") && !src.contains("yonaplay") && !src.contains("dotplay") && seen.add(src)) {
                    val myIdx = idx++
                    withTimeoutOrNull(10_000) { routeLink(src, yonaplayUrl, subtitleCallback, emitAt, null, myIdx) }
                }
            }
            Regex("""https?://[^\s"'<>]+""").findAll(html).map { it.value.trimEnd('"', '\'', ')', ';', ',') }
                .filter { isDirectCdnLink(it) }.distinct().forEach { cdn ->
                    if (seen.add(cdn)) { val myIdx = idx++; emitDirectCdn(cdn, callback = emitAt(myIdx)) }
                }
            Regex("""(https?://[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*)""").findAll(html).forEach { m ->
                val url = m.groupValues[1]
                if (!url.contains("googleapis") && !url.contains("drive.google") && !isDirectCdnLink(url) && seen.add(url)) {
                    val myIdx = idx++
                    emitAt(myIdx)(newExtractorLink("Yonaplay", "Yonaplay Direct", url,
                        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = yonaplayUrl; quality = urlQuality(url)
                    })
                }
            }
            WitaLog.d("Yona legacy processed=$idx")
        } catch (e: Exception) { WitaLog.e("Yonaplay error: ${e.message}") }
    }
}
