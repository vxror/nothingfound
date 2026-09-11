package com.kawaii

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class KawaiiAnime : MainAPI() {
    override var mainUrl = "https://kawaiianime.cc"
    override var name = "KawaiiAnime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true

    companion object {
        private const val TAG = "KawaiiAnime"
        private const val VIDEO_HOST = "https://video.kawaii-anime.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        @Volatile private var homeFetchedAt = 0L
        private const val HOME_TTL_MS = 10 * 60_000L
        private val homeSections = ConcurrentHashMap<String, List<SearchResponse>>()
        private val mediaCache = ConcurrentHashMap<String, JSONObject>()

        private val SECTION_MAP = mapOf(
            "TRENDING"         to "trending",
            "POPULAR"          to "popular",
            "THIS_SEASON"      to "thisSeason",
            "RECENTLY_UPDATED" to "recentlyUpdated",
            "TOP_RATED"        to "topRated"
        )
    }

    private fun log(msg: String) { Log.d(TAG, msg) }
    private fun logErr(s: String, e: Throwable) { Log.e(TAG, "$s: ${e.message}", e) }
    private fun headers() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9"
    )

    // ── RSC extraction ────────────────────────────────────────────

    private fun extractRscRows(html: String): Map<String, Any> {
        val stream = StringBuilder()
        val chunkRx = Regex("""self\.__next_f\.push\(\[\s*\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*\]\)""")
        var chunks = 0
        chunkRx.findAll(html).forEach { m ->
            try {
                val unescaped = JSONObject("{\"s\":\"${m.groupValues[1]}\"}").optString("s")
                stream.append(unescaped).append('\n'); chunks++
            } catch (_: Exception) {}
        }
        log("rsc chunks=$chunks")

        val rows = HashMap<String, Any>()
        for (line in stream.toString().split('\n')) {
            val t = line.trim()
            if (t.length < 5) continue
            val ci = t.indexOf(':')
            if (ci <= 0 || ci > 6) continue
            val rid = t.substring(0, ci)
            if (!rid.matches(Regex("^[0-9a-fA-F]+$"))) continue
            val content = t.substring(ci + 1)
            try {
                rows[rid] = when {
                    content.startsWith("{") -> JSONObject(content)
                    content.startsWith("[") -> JSONArray(content)
                    else -> content
                }
            } catch (_: Exception) {}
        }
        log("rsc rows=${rows.size}")
        return rows
    }

    /** Resolve $N references recursively. */
    private fun resolve(v: Any?, rows: Map<String, Any>, depth: Int = 0): Any? {
        if (depth > 25 || v == null) return v
        return when (v) {
            is String -> {
                if (v.startsWith("$") && v.length > 1 && v[1] != '"' && v[1] != 'L') {
                    val t = rows[v.substring(1)] ?: return v
                    resolve(t, rows, depth + 1)
                } else v
            }
            is JSONArray -> {
                val o = JSONArray()
                for (i in 0 until v.length()) o.put(resolve(v.opt(i), rows, depth + 1))
                o
            }
            is JSONObject -> {
                val o = JSONObject()
                for (k in v.keys()) o.put(k, resolve(v.opt(k), rows, depth + 1))
                o
            }
            else -> v
        }
    }

    /** Recursively search any JSON structure for the first JSONObject matching pred. */
    private fun deepFind(root: Any?, pred: (JSONObject) -> Boolean, depth: Int = 0): JSONObject? {
        if (depth > 30 || root == null) return null
        return when (root) {
            is JSONObject -> {
                if (pred(root)) return root
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    deepFind(root.opt(k), pred, depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    deepFind(root.opt(i), pred, depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun resolveAllRows(rows: Map<String, Any>): List<Any> {
        val out = ArrayList<Any>(rows.size)
        for ((_, v) in rows) {
            val r = if (v is String && v.startsWith("$")) resolve(v, rows) ?: v else v
            out.add(r)
        }
        return out
    }

    private fun findSectionContainer(rows: Map<String, Any>): JSONObject? {
        val pred: (JSONObject) -> Boolean = { o ->
            o.has("trending") && o.has("popular") && o.has("thisSeason") &&
            o.has("recentlyUpdated") && o.has("topRated")
        }
        for (v in resolveAllRows(rows)) {
            deepFind(v, pred)?.let { return it }
        }
        return null
    }

    private fun findAnimeObject(rows: Map<String, Any>, targetId: Int): JSONObject? {
        val pred: (JSONObject) -> Boolean = { o ->
            val inner = o.optJSONObject("anime")
            (inner != null && inner.optInt("id", 0) == targetId && inner.has("title")) ||
            (o.optInt("id", 0) == targetId && o.has("title") && o.has("coverImage"))
        }
        for (v in resolveAllRows(rows)) {
            deepFind(v, pred)?.let { o ->
                val inner = o.optJSONObject("anime")
                if (inner != null && inner.optInt("id", 0) == targetId) return inner
                return o
            }
        }
        return null
    }

    private fun mediaToShow(m: JSONObject): SearchResponse? {
        val id = m.optInt("id", 0); if (id <= 0) return null
        val t = m.optJSONObject("title") ?: return null
        val title = t.optString("english")
            .ifEmpty { t.optString("romaji").ifEmpty { t.optString("native") } }
        if (title.isBlank()) return null
        val c = m.optJSONObject("coverImage")
        val cover = c?.optString("extraLarge")?.ifEmpty { c.optString("large") } ?: ""
        val fmt = m.optString("format")
        mediaCache[id.toString()] = m
        return newAnimeSearchResponse(title, id.toString(),
            if (fmt == "MOVIE") TvType.AnimeMovie else TvType.Anime) {
            posterUrl = cover
            this.year = m.optInt("seasonYear", 0).takeIf { it > 0 }
        }
    }

    // ── Home ──────────────────────────────────────────────────────

    private suspend fun ensureHomeCache(): Boolean {
        if (System.currentTimeMillis() - homeFetchedAt < HOME_TTL_MS && homeSections.isNotEmpty()) return true
        return try {
            val html = app.get(mainUrl, headers = headers()).text
            log("homepage len=${html.length}")
            val rows = extractRscRows(html)
            val container = findSectionContainer(rows) ?: run { log("no container found"); return false }
            log("container found")

            val found = HashMap<String, List<SearchResponse>>()
            for ((csKey, rscKey) in SECTION_MAP) {
                val raw = container.opt(rscKey) ?: continue
                val resolved = resolve(raw, rows)
                val arr = resolved as? JSONArray ?: continue
                val shows = ArrayList<SearchResponse>()
                for (i in 0 until arr.length()) {
                    val obj = arr.opt(i) as? JSONObject ?: continue
                    mediaToShow(obj)?.let { shows.add(it) }
                }
                log("$csKey <- $rscKey : ${shows.size}")
                if (shows.isNotEmpty()) found[csKey] = shows
            }
            if (found.isEmpty()) { log("0 sections"); return false }

            homeSections.clear(); homeSections.putAll(found)
            homeFetchedAt = System.currentTimeMillis()
            log("home cached: ${homeSections.size} sections, ${mediaCache.size} media")
            true
        } catch (e: Exception) { logErr("home", e); false }
    }

    override val mainPage = mainPageOf(
        "TRENDING"         to "الأكثر رواجاً",
        "POPULAR"          to "الأكثر شعبية",
        "THIS_SEASON"      to "هذا الموسم",
        "RECENTLY_UPDATED" to "أضيف حديثاً",
        "TOP_RATED"        to "الأعلى تقييماً"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            log("getMainPage: '${request.name}' key=${request.data}")
            if (!ensureHomeCache())
                return@withContext newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            val shows = homeSections[request.data] ?: emptyList()
            log("serving ${shows.size} for ${request.name}")
            newHomePageResponse(HomePageList(request.name, shows), hasNext = false)
        }

    // ── Search ────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        log("search: '$query'")
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/search?q=$enc", headers = headers()).text
            log("search html len=${html.length}")
            if (!html.contains("self.__next_f.push")) return@withContext emptyList()
            val rows = extractRscRows(html)
            val out = ArrayList<SearchResponse>()
            val seen = HashSet<Int>()
            for (v in resolveAllRows(rows)) {
                // walk every JSONObject that looks like a media record
                walkMedia(v) { m ->
                    val id = m.optInt("id", 0)
                    if (id > 0 && seen.add(id)) mediaToShow(m)?.let { out.add(it) }
                }
            }
            log("search -> ${out.size}")
            out
        } catch (e: Exception) { logErr("search", e); emptyList() }
    }

    private fun walkMedia(root: Any?, cb: (JSONObject) -> Unit, depth: Int = 0) {
        if (depth > 20 || root == null) return
        when (root) {
            is JSONObject -> {
                if (root.has("id") && root.has("title") && root.has("coverImage") &&
                    root.has("format") && root.has("status")) {
                    cb(root)
                }
                val keys = root.keys()
                while (keys.hasNext()) walkMedia(root.opt(keys.next()), cb, depth + 1)
            }
            is JSONArray -> {
                for (i in 0 until root.length()) walkMedia(root.opt(i), cb, depth + 1)
            }
        }
    }

    // ── Load ──────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val id = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("bad id: $url")
        log("load id=$id")

        mediaCache[id.toString()]?.let {
            log("load id=$id from media cache (title=${it.optJSONObject("title")?.optString("english")})")
            return@withContext buildLoadResponse(it, id)
        }

        try {
            val html = app.get("$mainUrl/anime/$id", headers = headers()).text
            log("detail html len=${html.length}")
            val rows = extractRscRows(html)
            val media = findAnimeObject(rows, id)
            if (media != null) {
                log("detail id=$id extracted title='${media.optJSONObject("title")?.optString("english")}' " +
                    "episodes=${media.optInt("episodes", 0)} status=${media.optString("status")}")
                mediaCache[id.toString()] = media
                return@withContext buildLoadResponse(media, id)
            }
            log("detail id=$id NOT found")
        } catch (e: Exception) { logErr("detail", e) }

        throw ErrorLoadingException("couldn't load anime $id")
    }

    private suspend fun buildLoadResponse(m: JSONObject, id: Int): LoadResponse {
        val tObj = m.optJSONObject("title")
        val title = tObj?.optString("english")
            ?.ifEmpty { tObj.optString("romaji").ifEmpty { tObj.optString("native") } }
            .orEmpty().ifEmpty { "Anime $id" }
        val cObj = m.optJSONObject("coverImage")
        val cover = cObj?.optString("extraLarge")?.ifEmpty { cObj.optString("large") } ?: ""
        val banner = m.optString("bannerImage").ifEmpty { null }
        val plot = stripHtml(m.optString("description"))
        val genres = m.optJSONArray("genres")?.let { g ->
            (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val status = m.optString("status")
        val format = m.optString("format")
        val isMovie = format == "MOVIE"
        val score = m.optInt("averageScore", 0)

        val episodesField = m.optInt("episodes", 0)
        val nextEp = m.optJSONObject("nextAiringEpisode")?.optInt("episode", 0) ?: 0
        val epsCount = maxOf(episodesField, (nextEp - 1).coerceAtLeast(0)).coerceAtLeast(0)

        log("buildLoadResponse id=$id title='$title' status=$status episodesField=$episodesField nextEp=$nextEp -> epsCount=$epsCount")

        val episodes: List<Episode> = when {
            epsCount > 0 -> (1..epsCount).map { n ->
                newEpisode("$id|$n") { this.name = "الحلقة $n"; this.episode = n }
            }
            isMovie -> listOf(newEpisode("$id|1") { this.name = "الفيلم"; this.episode = 1 })
            else -> emptyList()
        }

        log("emitting ${episodes.size} episodes")

        return newAnimeLoadResponse(title, "$id", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = cover
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = m.optInt("seasonYear", 0).takeIf { it > 0 }
            this.score = if (score > 0) Score.from10(score / 10.0) else null
            this.showStatus = when (status) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun stripHtml(s: String?): String =
        (s ?: "").replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
            .replace("&mdash;", "—").replace("&ndash;", "–").trim()

    // ── loadLinks ─────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split("|")
        if (parts.size < 2) return@withContext false
        val showId = parts[0]; val epNum = parts[1]
        log("loadLinks: showId=$showId ep=$epNum")

        try {
            val url = "$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub"
            val res = app.get(url, headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/",
                "Accept" to "*/*", "Origin" to mainUrl
            ))
            log("miruro HTTP ${res.code} len=${res.text.length}")
            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val d = root.optJSONObject("data") ?: root
                val ref = d.optJSONObject("headers")?.optString("Referer")
                    ?.ifBlank { null } ?: "$mainUrl/"

                var emitted = 0
                d.optJSONArray("sources")?.let { srcs ->
                    for (i in 0 until srcs.length()) {
                        val s = srcs.optJSONObject(i) ?: continue
                        val u = s.optString("url"); if (u.isBlank()) continue
                        val m3u8 = s.optBoolean("isM3U8", false) || u.contains(".m3u8")
                        val q = s.optString("quality").filter { it.isDigit() }.toIntOrNull()
                            ?.let { if (it in 144..2160) it else null }
                            ?: Qualities.Unknown.value
                        callback(newExtractorLink(name, "Kawaii", u,
                            if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = ref
                            this.quality = q
                            this.headers = mapOf("User-Agent" to UA, "Referer" to ref, "Origin" to mainUrl)
                        })
                        emitted++
                    }
                }
                d.optJSONArray("subtitles")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val u = s.optString("url"); if (u.isBlank()) continue
                        subtitleCallback(SubtitleFile(s.optString("lang").ifEmpty { "Arabic" }, u))
                    }
                }
                if (emitted > 0) { log("miruro OK: $emitted"); return@withContext true }
            }
        } catch (e: Exception) { logErr("miruro", e) }

        try {
            val res = app.get("$mainUrl/api/video-cache?episodeId=$showId-ep$epNum",
                headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"))
            if (res.isSuccessful) {
                val json = JSONObject(res.text)
                val u = json.optString("url")
                if (u.isNotBlank()) {
                    log("video-cache OK -> ${u.take(90)}")
                    callback(newExtractorLink(name, "Kawaii", u, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to UA)
                    })
                    json.optJSONArray("subtitles")?.let { subs ->
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val su = s.optString("url")
                            if (su.isNotBlank()) subtitleCallback(SubtitleFile(s.optString("lang", "ar"), su))
                        }
                    }
                    return@withContext true
                }
            }
        } catch (e: Exception) { logErr("video-cache", e) }

        val direct = "$VIDEO_HOST/video/$showId-ep$epNum"
        log("fallback direct: $direct")
        callback(newExtractorLink(name, "Kawaii (Direct)", direct, ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
            quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to UA)
        })
        true
    }
}
