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
    override var lang = "en"
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
    private fun logErr(stage: String, e: Throwable) { Log.e(TAG, "$stage: ${e.message}", e) }
    private fun baseHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ── RSC parser ────────────────────────────────────────────────

    private fun extractRscStream(html: String): String {
        val out = StringBuilder()
        val rx = Regex("""self\.__next_f\.push\(\[\s*\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*\]\)""")
        var n = 0
        rx.findAll(html).forEach { m ->
            try {
                val unescaped = JSONObject("{\"s\":\"${m.groupValues[1]}\"}").optString("s")
                out.append(unescaped).append('\n'); n++
            } catch (_: Exception) {}
        }
        log("rsc chunks=$n len=${out.length}")
        return out.toString()
    }

    private fun parseRscRows(stream: String): Map<String, Any> {
        val rows = HashMap<String, Any>()
        for (line in stream.split('\n')) {
            val t = line.trim()
            if (t.length < 3) continue
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

    private fun deepResolve(value: Any?, rows: Map<String, Any>, depth: Int = 0): Any? {
        if (depth > 20 || value == null) return value
        return when (value) {
            is String -> {
                if (value.startsWith("$") && value.length > 1 && value[1] != '"') {
                    val t = rows[value.substring(1)] ?: return value
                    deepResolve(t, rows, depth + 1)
                } else value
            }
            is JSONArray -> {
                val o = JSONArray()
                for (i in 0 until value.length()) o.put(deepResolve(value.opt(i), rows, depth + 1))
                o
            }
            is JSONObject -> {
                val o = JSONObject()
                for (k in value.keys()) o.put(k, deepResolve(value.opt(k), rows, depth + 1))
                o
            }
            else -> value
        }
    }

    private fun findSectionContainer(rows: Map<String, Any>): JSONObject? {
        for ((_, v) in rows) {
            val r = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
            if (r !is JSONObject) continue
            if (r.has("trending") && r.has("popular") && r.has("thisSeason") &&
                r.has("recentlyUpdated") && r.has("topRated")) return r
        }
        return null
    }

    private fun mediaToShow(m: JSONObject): SearchResponse? {
        val id = m.optInt("id", 0)
        if (id <= 0) return null
        val titleObj = m.optJSONObject("title") ?: return null
        val title = titleObj.optString("english")
            .ifEmpty { titleObj.optString("romaji").ifEmpty { titleObj.optString("native") } }
        if (title.isBlank()) return null
        val coverObj = m.optJSONObject("coverImage")
        val cover = coverObj?.optString("extraLarge")?.ifEmpty { coverObj.optString("large") } ?: ""
        val format = m.optString("format")
        val type = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        mediaCache[id.toString()] = m
        return newAnimeSearchResponse(title, id.toString(), type) {
            posterUrl = cover
            this.year = m.optInt("seasonYear", 0).takeIf { it > 0 }
        }
    }

    private fun extractSection(container: JSONObject, key: String, rows: Map<String, Any>): List<SearchResponse> {
        val raw = container.opt(key) ?: return emptyList()
        val resolved = deepResolve(raw, rows) as? JSONArray ?: return emptyList()
        val out = ArrayList<SearchResponse>()
        for (i in 0 until resolved.length()) {
            val obj = resolved.opt(i) as? JSONObject ?: continue
            mediaToShow(obj)?.let { out.add(it) }
        }
        return out
    }

    private suspend fun ensureHomeCache(): Boolean {
        if (System.currentTimeMillis() - homeFetchedAt < HOME_TTL_MS && homeSections.isNotEmpty()) return true
        return try {
            val html = app.get(mainUrl, headers = baseHeaders()).text
            log("homepage len=${html.length}")
            val rows = parseRscRows(extractRscStream(html))
            val container = findSectionContainer(rows) ?: run { log("no container"); return false }
            val found = HashMap<String, List<SearchResponse>>()
            for ((csKey, rscKey) in SECTION_MAP) {
                val shows = extractSection(container, rscKey, rows)
                log("$csKey <- $rscKey : ${shows.size}")
                if (shows.isNotEmpty()) found[csKey] = shows
            }
            if (found.isEmpty()) { log("0 sections"); return false }
            homeSections.clear(); homeSections.putAll(found)
            homeFetchedAt = System.currentTimeMillis()
            log("home cache: ${homeSections.size} sections, ${mediaCache.size} media")
            true
        } catch (e: Exception) { logErr("home", e); false }
    }

    // ── Main page ─────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "TRENDING"         to "Trending",
        "POPULAR"          to "Popular",
        "THIS_SEASON"      to "This Season",
        "RECENTLY_UPDATED" to "Recently Updated",
        "TOP_RATED"        to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            log("getMainPage: '${request.name}' key=${request.data} page=$page")
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
        val encoded = URLEncoder.encode(query, "UTF-8")
        try {
            val html = app.get("$mainUrl/search?q=$encoded", headers = baseHeaders()).text
            log("search html len=${html.length}")
            if (!html.contains("self.__next_f.push")) return@withContext emptyList()
            val rows = parseRscRows(extractRscStream(html))
            val out = ArrayList<SearchResponse>()
            val seen = HashSet<Int>()
            for ((_, v) in rows) {
                val r = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
                if (r !is JSONObject) continue
                if (!r.has("id") || !r.has("title") || !r.has("coverImage")) continue
                val id = r.optInt("id", 0)
                if (id <= 0 || !seen.add(id)) continue
                mediaToShow(r)?.let { out.add(it) }
            }
            log("search '$query' -> ${out.size}")
            out
        } catch (e: Exception) { logErr("search", e); emptyList() }
    }

    // ── Load ──────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val id = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("invalid id: $url")
        log("load id=$id")

        mediaCache[id.toString()]?.let {
            log("load id=$id from cache")
            return@withContext buildLoadResponse(it, id)
        }

        try {
            val html = app.get("$mainUrl/anime/$id", headers = baseHeaders()).text
            log("detail page len=${html.length}")
            val rows = parseRscRows(extractRscStream(html))

            var media: JSONObject? = null
            for ((_, v) in rows) {
                val r = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
                if (r !is JSONObject) continue
                val inner = r.optJSONObject("anime")
                if (inner != null && inner.optInt("id", 0) == id) { media = inner; break }
            }
            if (media == null) {
                for ((_, v) in rows) {
                    val r = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
                    if (r !is JSONObject) continue
                    if (r.optInt("id", 0) == id && r.has("title") && r.has("coverImage")) { media = r; break }
                }
            }

            if (media != null) {
                log("detail id=$id extracted")
                mediaCache[id.toString()] = media
                return@withContext buildLoadResponse(media, id)
            }
            log("detail id=$id not found in RSC")
        } catch (e: Exception) { logErr("detail", e) }

        throw ErrorLoadingException("couldn't load anime $id")
    }

    private suspend fun buildLoadResponse(m: JSONObject, id: Int): LoadResponse {
        val titleObj = m.optJSONObject("title")
        val title = titleObj?.optString("english")
            ?.ifEmpty { titleObj.optString("romaji").ifEmpty { titleObj.optString("native") } }
            .orEmpty().ifEmpty { "Anime $id" }
        val coverObj = m.optJSONObject("coverImage")
        val cover = coverObj?.optString("extraLarge")?.ifEmpty { coverObj.optString("large") } ?: ""
        val banner = m.optString("bannerImage").ifEmpty { null }
        val plot = stripHtml(m.optString("description"))
        val genres = m.optJSONArray("genres")?.let { g ->
            (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val status = m.optString("status")
        val format = m.optString("format")
        val isMovie = format == "MOVIE"
        val score = m.optInt("averageScore", 0)

        val nextAiring = m.optJSONObject("nextAiringEpisode")
        val epsCount = when {
            status == "RELEASING" && nextAiring != null ->
                (nextAiring.optInt("episode", 0) - 1).coerceAtLeast(0)
            else -> m.optInt("episodes", 0)
        }

        val episodes: List<Episode> = when {
            epsCount > 0 -> (1..epsCount).map { n ->
                newEpisode("$id|$n") { this.name = "Episode $n"; this.episode = n }
            }
            isMovie -> listOf(newEpisode("$id|1") { this.name = "Movie"; this.episode = 1 })
            else -> emptyList()
        }

        log("loadResponse id=$id title='$title' eps=${episodes.size}")

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
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .trim()

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
            val apiUrl = "$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub"
            val res = app.get(apiUrl, headers = mapOf(
                "User-Agent" to UA, "Referer" to "$mainUrl/",
                "Accept" to "*/*", "Origin" to mainUrl
            ))
            log("miruro HTTP ${res.code} len=${res.text.length}")
            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val dataObj = root.optJSONObject("data") ?: root
                val refFromApi = dataObj.optJSONObject("headers")?.optString("Referer")
                    ?.ifBlank { null } ?: "$mainUrl/"

                var emitted = 0
                dataObj.optJSONArray("sources")?.let { sources ->
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        val srcUrl = s.optString("url"); if (srcUrl.isBlank()) continue
                        val isM3u8 = s.optBoolean("isM3U8", false) || srcUrl.contains(".m3u8")
                        val q = s.optString("quality").filter { it.isDigit() }.toIntOrNull()
                            ?.let { if (it in 144..2160) it else null }
                            ?: Qualities.Unknown.value
                        callback(newExtractorLink(name, "Kawaii", srcUrl,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = refFromApi
                            this.quality = q
                            this.headers = mapOf("User-Agent" to UA, "Referer" to refFromApi, "Origin" to mainUrl)
                        })
                        emitted++
                    }
                }
                dataObj.optJSONArray("subtitles")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val sUrl = s.optString("url"); if (sUrl.isBlank()) continue
                        subtitleCallback(SubtitleFile(s.optString("lang").ifEmpty { "English" }, sUrl))
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
                val url = json.optString("url")
                if (url.isNotBlank()) {
                    log("video-cache OK -> ${url.take(90)}")
                    callback(newExtractorLink(name, "Kawaii", url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to UA)
                    })
                    json.optJSONArray("subtitles")?.let { subs ->
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val sUrl = s.optString("url")
                            if (sUrl.isNotBlank()) subtitleCallback(SubtitleFile(s.optString("lang", "en"), sUrl))
                        }
                    }
                    return@withContext true
                }
            }
        } catch (e: Exception) { logErr("video-cache", e) }

        val direct = "$VIDEO_HOST/video/$showId-ep$epNum"
        log("fallback: $direct")
        callback(newExtractorLink(name, "Kawaii (Direct)", direct, ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
            quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to UA)
        })
        true
    }
}
