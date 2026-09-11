package com.kawaii

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

        // homepage RSC cache — one fetch serves all 5 sections
        @Volatile private var homeFetchedAt = 0L
        private const val HOME_TTL_MS = 10 * 60_000L
        private val homeSections = ConcurrentHashMap<String, List<SearchResponse>>()
        private val mediaCache = ConcurrentHashMap<String, JSONObject>()

        // map cloudstream section key -> RSC section key
        private val SECTION_MAP = mapOf(
            "TRENDING" to "trending",
            "POPULAR" to "popular",
            "THIS_SEASON" to "thisSeason",
            "RECENTLY_UPDATED" to "recentlyUpdated",
            "TOP_RATED" to "topRated"
        )
    }

    private fun log(msg: String) { Log.d(TAG, msg) }
    private fun logErr(stage: String, e: Throwable) { Log.e(TAG, "$stage: ${e.message}", e) }

    private fun baseHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ════════════════════════════════════════════════════════════
    //  RSC (React Server Components) parser
    // ════════════════════════════════════════════════════════════

    /** Pull every self.__next_f.push([1,"..."]) chunk, unescape, join into one string. */
    private fun extractRscStream(html: String): String {
        val out = StringBuilder()
        val rx = Regex("""self\.__next_f\.push\(\[\s*\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*\]\)""")
        var n = 0
        rx.findAll(html).forEach { m ->
            try {
                // wrap in a JSON string literal so JSONObject handles ALL escape sequences
                val unescaped = JSONObject("{\"s\":\"${m.groupValues[1]}\"}").optString("s")
                out.append(unescaped).append('\n')
                n++
            } catch (_: Exception) {}
        }
        log("rsc chunks: $n, stream len=${out.length}")
        return out.toString()
    }

    /** Parse "hexid:payload" lines into rowId -> Any(JSONObject|JSONArray|String). */
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
        log("rsc rows: ${rows.size}")
        return rows
    }

    /** Recursively resolve $N references against the row table. */
    private fun deepResolve(value: Any?, rows: Map<String, Any>, depth: Int = 0): Any? {
        if (depth > 20 || value == null) return value
        return when (value) {
            is String -> {
                if (value.startsWith("$") && value.length > 1 && value[1] != '"') {
                    val target = rows[value.substring(1)] ?: return value
                    deepResolve(target, rows, depth + 1)
                } else value
            }
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until value.length()) {
                    out.put(deepResolve(value.opt(i), rows, depth + 1))
                }
                out
            }
            is JSONObject -> {
                val out = JSONObject()
                for (k in value.keys()) {
                    out.put(k, deepResolve(value.opt(k), rows, depth + 1))
                }
                out
            }
            else -> value
        }
    }

    /** Find the object with {trending, popular, thisSeason, recentlyUpdated, topRated}. */
    private fun findSectionContainer(rows: Map<String, Any>): JSONObject? {
        for ((_, v) in rows) {
            val resolved = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
            if (resolved !is JSONObject) continue
            if (resolved.has("trending") && resolved.has("popular") &&
                resolved.has("thisSeason") && resolved.has("recentlyUpdated")) {
                return resolved
            }
        }
        return null
    }

    /** Convert one raw media JSONObject (already deep-resolved) into a SearchResponse. */
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

    /** Fetch homepage once, parse ALL sections, cache. */
    private suspend fun ensureHomeCache(): Boolean {
        if (System.currentTimeMillis() - homeFetchedAt < HOME_TTL_MS && homeSections.isNotEmpty()) {
            log("home cache hit (${homeSections.size} sections)")
            return true
        }
        return try {
            log("fetching homepage: $mainUrl")
            val html = app.get(mainUrl, headers = baseHeaders()).text
            log("homepage len=${html.length}")

            val stream = extractRscStream(html)
            val rows = parseRscRows(stream)
            val container = findSectionContainer(rows)
            if (container == null) {
                log("no section container found in RSC")
                return false
            }

            val found = HashMap<String, List<SearchResponse>>()
            for ((csKey, rscKey) in SECTION_MAP) {
                val shows = extractSection(container, rscKey, rows)
                log("section $csKey <- $rscKey : ${shows.size} shows")
                if (shows.isNotEmpty()) found[csKey] = shows
            }

            if (found.isEmpty()) {
                log("homepage RSC yielded 0 sections")
                return false
            }

            homeSections.clear()
            homeSections.putAll(found)
            homeFetchedAt = System.currentTimeMillis()
            log("home cache refreshed: ${homeSections.size} sections, ${mediaCache.size} media cached")
            true
        } catch (e: Exception) {
            logErr("ensureHomeCache", e)
            false
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Main page — everything comes from the RSC cache
    // ════════════════════════════════════════════════════════════

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

            if (!ensureHomeCache()) {
                return@withContext newHomePageResponse(
                    HomePageList(request.name, emptyList()), hasNext = false
                )
            }

            val shows = homeSections[request.data] ?: emptyList()
            log("serving ${shows.size} shows for ${request.name}")

            newHomePageResponse(HomePageList(request.name, shows), hasNext = false)
        }

    // ════════════════════════════════════════════════════════════
    //  Search — hit the site's search page RSC, with API fallback
    // ════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        log("search: '$query'")

        val encoded = URLEncoder.encode(query, "UTF-8")

        // try a few endpoint patterns in order
        val urls = listOf(
            "$mainUrl/api/anilist/search?q=$encoded",
            "$mainUrl/api/search?q=$encoded",
            "$mainUrl/search?q=$encoded"
        )

        for (url in urls) {
            try {
                val res = app.get(url, headers = baseHeaders())
                log("search try $url -> ${res.code} len=${res.text.length}")
                if (!res.isSuccessful) continue

                val text = res.text
                val out = ArrayList<SearchResponse>()

                // case A: JSON array / object with media objects
                if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                    try {
                        val json = if (text.trimStart().startsWith("[")) JSONObject("{\"r\":$text}").optJSONArray("r")
                                   else JSONObject(text).optJSONArray("results")
                                ?: JSONObject(text).optJSONArray("data")
                                ?: JSONObject(text).optJSONArray("media")
                        if (json != null) {
                            for (i in 0 until json.length()) {
                                val obj = json.optJSONObject(i) ?: continue
                                mediaToShow(obj)?.let { out.add(it) }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // case B: HTML with RSC payload
                if (out.isEmpty() && text.contains("self.__next_f.push")) {
                    val stream = extractRscStream(text)
                    val rows = parseRscRows(stream)
                    // walk every JSONObject for id+title+coverImage
                    for ((_, v) in rows) {
                        val resolved = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
                        if (resolved !is JSONObject) continue
                        if (!resolved.has("id") || !resolved.has("title")) continue
                        mediaToShow(resolved)?.let { out.add(it) }
                    }
                }

                if (out.isNotEmpty()) {
                    log("search '$query' -> ${out.size} results via $url")
                    return@withContext out
                }
            } catch (e: Exception) {
                logErr("search $url", e)
            }
        }

        log("search '$query' -> 0 results")
        emptyList()
    }

    // ════════════════════════════════════════════════════════════
    //  Load — cache hit first, then anime detail page RSC
    // ════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val id = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("invalid id: $url")
        log("load id=$id")

        // 1. try cached media from homepage / search
        mediaCache[id.toString()]?.let { cached ->
            log("load id=$id from media cache")
            return@withContext buildLoadResponse(cached, id)
        }

        // 2. fetch detail page and parse RSC
        val detailPaths = listOf(
            "$mainUrl/anime/$id",
            "$mainUrl/watch/$id",
            "$mainUrl/$id"
        )
        for (path in detailPaths) {
            try {
                val html = app.get(path, headers = baseHeaders()).text
                log("detail $path -> ${html.length} chars")
                val stream = extractRscStream(html)
                val rows = parseRscRows(stream)

                // find a JSONObject matching this id
                var found: JSONObject? = null
                for ((_, v) in rows) {
                    val resolved = if (v is String && v.startsWith("$")) deepResolve(v, rows) else v
                    if (resolved !is JSONObject) continue
                    if (resolved.optInt("id", 0) == id && resolved.has("title")) {
                        found = resolved
                        break
                    }
                }
                if (found != null) {
                    log("detail id=$id found in $path")
                    return@withContext buildLoadResponse(found, id)
                }
            } catch (e: Exception) {
                logErr("detail $path", e)
            }
        }

        throw ErrorLoadingException("couldn't load anime $id")
    }

    private suspend fun buildLoadResponse(m: JSONObject, id: Int): LoadResponse {
        val title = pickTitle(m.optJSONObject("title")).ifEmpty { "Anime $id" }
        val cover = m.optJSONObject("coverImage")?.let {
            it.optString("extraLarge").ifEmpty { it.optString("large") }
        } ?: ""
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

    private fun pickTitle(t: JSONObject?): String {
        if (t == null) return ""
        return t.optString("english")
            .ifEmpty { t.optString("romaji").ifEmpty { t.optString("native") } }
    }

    private fun stripHtml(s: String?): String =
        (s ?: "").replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .trim()

    // ════════════════════════════════════════════════════════════
    //  loadLinks — unchanged, /api/miruro verified live
    // ════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split("|")
        if (parts.size < 2) return@withContext false
        val showId = parts[0]
        val epNum = parts[1]
        val episodeId = "$showId-ep$epNum"
        log("loadLinks: showId=$showId ep=$epNum")

        try {
            val apiUrl = "$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub"
            val res = app.get(apiUrl, headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
                "Accept" to "*/*",
                "Origin" to mainUrl
            ))
            log("miruro HTTP ${res.code} len=${res.text.length}")

            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val dataObj = root.optJSONObject("data") ?: root
                val headersObj = dataObj.optJSONObject("headers")
                val refFromApi = headersObj?.optString("Referer")?.ifBlank { null } ?: "$mainUrl/"

                val sources = dataObj.optJSONArray("sources")
                var emitted = 0
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        val srcUrl = s.optString("url")
                        if (srcUrl.isBlank()) continue
                        val isM3u8 = s.optBoolean("isM3U8", false) || srcUrl.contains(".m3u8")
                        val qualityLabel = s.optString("quality")
                        val qualityInt = qualityLabel.filter { it.isDigit() }.toIntOrNull()
                            ?.let { if (it in 144..2160) it else null }
                            ?: Qualities.Unknown.value

                        callback(newExtractorLink(name, "Kawaii", srcUrl,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = refFromApi
                            this.quality = qualityInt
                            this.headers = mapOf(
                                "User-Agent" to UA,
                                "Referer" to refFromApi,
                                "Origin" to mainUrl
                            )
                        })
                        emitted++
                    }
                }

                val subs = dataObj.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val sUrl = s.optString("url")
                        val sLang = s.optString("lang").ifEmpty { "English" }
                        if (sUrl.isNotBlank()) subtitleCallback(SubtitleFile(sLang, sUrl))
                    }
                }

                if (emitted > 0) {
                    log("miruro success: $emitted sources")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            logErr("miruro", e)
        }

        try {
            val res = app.get("$mainUrl/api/video-cache?episodeId=$episodeId",
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
        } catch (e: Exception) {
            logErr("video-cache", e)
        }

        val direct = "$VIDEO_HOST/video/$episodeId"
        log("fallback direct: $direct")
        callback(newExtractorLink(name, "Kawaii (Direct)", direct, ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
            quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to UA)
        })
        true
    }
}
