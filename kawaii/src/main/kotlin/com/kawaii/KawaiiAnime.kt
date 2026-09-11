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

    // ─────────────────────────────────────────────────────────────
    // RSC parser
    // ─────────────────────────────────────────────────────────────

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
            val payload = t.substring(ci + 1)
            try {
                rows[rid] = when {
                    payload.startsWith("{") -> JSONObject(payload)
                    payload.startsWith("[") -> JSONArray(payload)
                    else -> payload
                }
            } catch (_: Exception) {}
        }
        log("rsc rows=${rows.size}")
        return rows
    }

    private fun resolve(v: Any?, rows: Map<String, Any>, depth: Int = 0): Any? {
        if (depth > 25 || v == null) return v
        return when (v) {
            is String -> {
                if (v.length > 1 && v[0] == '$' && v[1] != '"' && v[1] != 'L') {
                    val target = rows[v.substring(1)]
                    if (target != null) resolve(target, rows, depth + 1) else v
                } else v
            }
            is JSONArray -> {
                val o = JSONArray()
                for (i in 0 until v.length()) o.put(resolve(v.opt(i), rows, depth + 1))
                o
            }
            is JSONObject -> {
                val o = JSONObject()
                val it = v.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    o.put(k, resolve(v.opt(k), rows, depth + 1))
                }
                o
            }
            else -> v
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Recursive walkers — no lambda parameters, no inference traps
    // ─────────────────────────────────────────────────────────────

    private fun findSectionContainer(root: Any?, depth: Int = 0): JSONObject? {
        if (depth > 25 || root == null) return null
        when (root) {
            is JSONObject -> {
                if (root.has("trending") && root.has("popular") && root.has("thisSeason") &&
                    root.has("recentlyUpdated") && root.has("topRated")) return root
                val it = root.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val found = findSectionContainer(root.opt(k), depth + 1)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    val found = findSectionContainer(root.opt(i), depth + 1)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findAnimeMedia(root: Any?, targetId: Int, depth: Int = 0): JSONObject? {
        if (depth > 25 || root == null) return null
        when (root) {
            is JSONObject -> {
                if (root.optInt("id", 0) == targetId &&
                    root.has("title") && root.has("description") && root.has("episodes")) {
                    return root
                }
                val it = root.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val found = findAnimeMedia(root.opt(k), targetId, depth + 1)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    val found = findAnimeMedia(root.opt(i), targetId, depth + 1)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun collectMedia(root: Any?, out: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 25 || root == null) return
        when (root) {
            is JSONObject -> {
                if (root.has("id") && root.has("title") && root.has("coverImage") &&
                    root.has("format") && root.has("status") && root.has("episodes") &&
                    !root.has("idMal") // top-level anime has idMal too; nested recs don't
                ) {
                    // nothing — this is fine, keep as candidate either way
                }
                if (root.has("id") && root.has("title") && root.has("coverImage") &&
                    root.has("format") && root.has("status") && root.has("episodes")) {
                    out.add(root)
                }
                val it = root.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    collectMedia(root.opt(k), out, depth + 1)
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) collectMedia(root.opt(i), out, depth + 1)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Converters
    // ─────────────────────────────────────────────────────────────

    private fun mediaToShow(m: JSONObject): SearchResponse? {
        val id = m.optInt("id", 0)
        if (id <= 0) return null
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

    // ─────────────────────────────────────────────────────────────
    // Home
    // ─────────────────────────────────────────────────────────────

    private suspend fun ensureHomeCache(): Boolean {
        if (System.currentTimeMillis() - homeFetchedAt < HOME_TTL_MS && homeSections.isNotEmpty()) return true
        return try {
            val html = app.get(mainUrl, headers = headers()).text
            log("homepage len=${html.length}")
            val rows = extractRscRows(html)

            var container: JSONObject? = null
            outer@ for ((_, v) in rows) {
                val r: Any? = if (v is String && v.startsWith("$")) resolve(v, rows) else v
                val c = findSectionContainer(r)
                if (c != null) { container = c; break@outer }
            }
            if (container == null) { log("no section container"); return false }
            log("container found")

            val found = HashMap<String, List<SearchResponse>>()
            for ((csKey, rscKey) in SECTION_MAP) {
                val raw = container.opt(rscKey) ?: continue
                val arr = raw as? JSONArray ?: (resolve(raw, rows) as? JSONArray) ?: continue
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

    // ─────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        log("search: '$query'")
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/search?q=$enc", headers = headers()).text
            log("search html len=${html.length}")
            if (!html.contains("self.__next_f.push")) return@withContext emptyList()
            val rows = extractRscRows(html)

            val collected = ArrayList<JSONObject>()
            for ((_, v) in rows) {
                val r: Any? = if (v is String && v.startsWith("$")) resolve(v, rows) else v
                collectMedia(r, collected)
            }
            val out = ArrayList<SearchResponse>()
            val seen = HashSet<Int>()
            for (m in collected) {
                val id = m.optInt("id", 0)
                if (id > 0 && seen.add(id)) mediaToShow(m)?.let { out.add(it) }
            }
            log("search -> ${out.size}")
            out
        } catch (e: Exception) { logErr("search", e); emptyList() }
    }

    // ─────────────────────────────────────────────────────────────
    // Load
    // ─────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val id = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("bad id: $url")
        log("load id=$id")

        mediaCache[id.toString()]?.let {
            log("load id=$id from media cache")
            return@withContext buildLoadResponse(it, id)
        }

        try {
            val html = app.get("$mainUrl/anime/$id", headers = headers()).text
            log("detail html len=${html.length}")
            val rows = extractRscRows(html)

            var media: JSONObject? = null
            outer@ for ((_, v) in rows) {
                val r: Any? = if (v is String && v.startsWith("$")) resolve(v, rows) else v
                val m = findAnimeMedia(r, id)
                if (m != null) { media = m; break@outer }
            }

            if (media != null) {
                log("detail id=$id extracted title='${media.optJSONObject("title")?.optString("english")}' " +
                    "episodes=${media.optInt("episodes", 0)} status=${media.optString("status")}")
                mediaCache[id.toString()] = media
                return@withContext buildLoadResponse(media, id)
            }
            log("detail id=$id NOT found in RSC")
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

        log("buildLoadResponse id=$id title='$title' status=$status episodesField=$episodesField nextEp=$nextEp epsCount=$epsCount")

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

    // ─────────────────────────────────────────────────────────────
    // loadLinks
    // ─────────────────────────────────────────────────────────────

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
            val apiUrl = "$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub"
            val res = app.get(apiUrl, headers = mapOf(
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
                val sources = d.optJSONArray("sources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
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
                val subs = d.optJSONArray("subtitles")
                if (subs != null) {
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
                    val subs = json.optJSONArray("subtitles")
                    if (subs != null) {
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
