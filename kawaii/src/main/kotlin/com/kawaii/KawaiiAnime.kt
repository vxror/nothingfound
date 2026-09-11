package com.kawaii

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
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

        private val GQL_ENDPOINTS = listOf(
            "https://graphql.anilist.co",
            "https://kawaiianime.cc/api/anilist"
        )

        private const val GQL_CACHE_TTL_MS = 5 * 60_000L
        private val gqlCache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

        // [v4] flight-parsed media cache — full AniList media objects from the
        // homepage's server-rendered RSC payload, keyed by anilist id. these
        // survive AniList API outages completely — load() builds from them
        // with zero API calls.
        private val flightMedia = ConcurrentHashMap<String, JSONObject>()

        @Volatile private var flightFetchedAt = 0L
        private const val FLIGHT_TTL_MS = 10 * 60_000L
    }

    private fun log(msg: String) { Log.d(TAG, msg) }
    private fun logErr(stage: String, e: Throwable) { Log.e(TAG, "$stage: ${e.message}", e) }

    // ════════════════════════════════════════════════════════════
    //  FLIGHT PARSER — the outage-proof data source
    // ════════════════════════════════════════════════════════════

    private suspend fun fetchFlightShows(): List<SearchResponse> {
        if (System.currentTimeMillis() - flightFetchedAt < FLIGHT_TTL_MS && flightMedia.isNotEmpty()) {
            log("flight cache hit — ${flightMedia.size} media objects")
            return flightMedia.values.mapNotNull { m -> flightToShow(m) }
        }
        return try {
            val res = app.get(mainUrl, headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "ar,en;q=0.9"
            ))
            val html = res.text
            log("homepage fetched, ${html.length} chars")
            val shows = parseFlight(html)
            flightFetchedAt = System.currentTimeMillis()
            log("flight parse -> ${shows.size} shows, ${flightMedia.size} cached media")
            shows
        } catch (e: Exception) {
            logErr("fetchFlight", e)
            flightMedia.values.mapNotNull { m -> flightToShow(m) }
        }
    }

    private fun parseFlight(html: String): List<SearchResponse> {
        val out = ArrayList<SearchResponse>()
        try {
            // extract + unescape all flight chunks (the JSON-parse trick
            // handles every JS string escape correctly)
            val stream = StringBuilder()
            val chunkRx = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
            chunkRx.findAll(html).forEach { m ->
                try {
                    val unescaped = JSONObject("{\"s\":\"${m.groupValues[1]}\"}").optString("s")
                    stream.append(unescaped).append('\n')
                } catch (_: Exception) {}
            }

            // build row table: rowId -> parsed JSON
            val rows = HashMap<String, Any>()
            for (line in stream.toString().split('\n')) {
                val trimmed = line.trim()
                if (trimmed.length < 5) continue
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx <= 0 || colonIdx > 4) continue
                val rowId = trimmed.substring(0, colonIdx)
                if (!rowId.matches(Regex("^[0-9a-f]+$"))) continue
                val content = trimmed.substring(colonIdx + 1)
                try {
                    rows[rowId] = when {
                        content.startsWith("{") -> JSONObject(content)
                        content.startsWith("[") -> JSONArray(content)
                        else -> content
                    }
                } catch (_: Exception) {}
            }
            log("flight rows: ${rows.size}")

            // find media rows (id + idMal + title = AniList media)
            for ((_, value) in rows) {
                if (value !is JSONObject) continue
                if (!value.has("id") || !value.has("idMal") || !value.has("title")) continue
                val id = value.optInt("id")
                if (id <= 0) continue

                // resolve $N references (title, coverImage, genres, etc.)
                val resolved = JSONObject()
                for (key in value.keys()) {
                    val v = value.opt(key)
                    if (v is String && v.startsWith("$") && v.length > 1) {
                        resolved.put(key, rows[v.substring(1)] ?: JSONObject.NULL)
                    } else {
                        resolved.put(key, v)
                    }
                }

                flightMedia[id.toString()] = resolved
                flightToShow(resolved)?.let { out.add(it) }
            }
        } catch (e: Exception) {
            logErr("parseFlight", e)
        }
        return out
    }

    private fun flightToShow(m: JSONObject): SearchResponse? {
        val titleObj = m.optJSONObject("title") ?: return null
        val title = titleObj.optString("english")
            .ifEmpty { titleObj.optString("romaji").ifEmpty { titleObj.optString("native") } }
        if (title.isBlank()) return null
        val coverObj = m.optJSONObject("coverImage")
        val cover = coverObj?.optString("extraLarge")?.ifEmpty { coverObj.optString("large") } ?: ""
        val id = m.optInt("id").toString()
        val format = m.optString("format")
        val type = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, id, type) {
            posterUrl = cover
            this.year = m.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
                ?: m.optInt("seasonYear", 0).takeIf { it > 0 }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AniList GraphQL chain (primary when healthy)
    // ════════════════════════════════════════════════════════════

    private suspend fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject? {
        val cacheKey = "${query.hashCode()}|${variables}"
        gqlCache[cacheKey]?.let { (ts, cached) ->
            if (System.currentTimeMillis() - ts < GQL_CACHE_TTL_MS) {
                log("gql cache hit")
                return cached
            }
            gqlCache.remove(cacheKey)
        }

        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val headers = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://anilist.co",
            "Referer" to "https://anilist.co/"
        )

        for ((idx, endpoint) in GQL_ENDPOINTS.withIndex()) {
            try {
                val res = app.post(endpoint, requestBody = body, headers = headers)
                val text = res.text
                log("gql[$idx] $endpoint -> HTTP ${res.code} len=${text.length}")

                if (res.code == 429 && idx == 0) {
                    val waitSec = res.headers["Retry-After"]?.toLongOrNull() ?: 3L
                    log("gql 429 — waiting ${waitSec}s")
                    delay((waitSec * 1000L).coerceAtMost(10_000L))
                }

                if (!res.isSuccessful) {
                    log("gql[$idx] FAIL: ${text.take(200)}")
                    continue
                }

                val json = JSONObject(text)
                val data = json.optJSONObject("data")
                if (data == null) {
                    log("gql[$idx] no data — errors: ${json.optJSONArray("errors")?.toString()?.take(200) ?: "none"}")
                    continue
                }

                gqlCache[cacheKey] = System.currentTimeMillis() to data
                return data
            } catch (e: Exception) {
                logErr("gql[$idx] $endpoint", e)
            }
        }

        log("gql: ALL endpoints failed")
        return null
    }

    private fun stripHtml(s: String?): String =
        (s ?: "").replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .trim()

    private fun pickTitle(t: JSONObject?): String {
        if (t == null) return ""
        return t.optString("english")
            .ifEmpty { t.optString("romaji").ifEmpty { t.optString("native") } }
    }

    private fun parseShows(media: JSONArray): List<SearchResponse> {
        val list = ArrayList<SearchResponse>()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val title = pickTitle(m.optJSONObject("title"))
            if (title.isBlank()) continue
            val cover = m.optJSONObject("coverImage")?.let {
                it.optString("extraLarge").ifEmpty { it.optString("large") }
            } ?: ""
            val id = m.optInt("id").toString()
            val format = m.optString("format")
            val type = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
            list.add(newAnimeSearchResponse(title, id, type) {
                posterUrl = cover
                this.year = m.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
            })
        }
        return list
    }

    private fun currentSeason(): Pair<String, Int> {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        return when (cal.get(Calendar.MONTH)) {
            Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "WINTER" to year
            Calendar.MARCH, Calendar.APRIL, Calendar.MAY             -> "SPRING" to year
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST            -> "SUMMER" to year
            else                                                     -> "FALL"   to year
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Main page — Trending = flight parse (always works);
    //  other sections = AniList chain
    // ════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "TRENDING"        to "Trending",
        "POPULARITY_DESC" to "Popular",
        "THIS_SEASON"     to "This Season",
        "UPDATED_AT_DESC" to "Recently Updated",
        "SCORE_DESC"      to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            log("getMainPage: section='${request.name}' page=$page")

            // Trending = homepage's own server-rendered data — outage-proof
            if (request.data == "TRENDING" && page == 1) {
                val shows = fetchFlightShows()
                log("Trending (flight) -> ${shows.size} shows")
                return@withContext newHomePageResponse(
                    HomePageList(request.name, shows),
                    hasNext = false
                )
            }

            val perPage = 20
            val query: String
            val vars: JSONObject

            when (request.data) {
                "POPULARITY_DESC" -> {
                    query = """query (${'$'}page: Int, ${'$'}perPage: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                                id title { romaji english native } coverImage { extraLarge large } startDate { year } format
                            }
                        }
                    }"""
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }
                "THIS_SEASON" -> {
                    val (season, year) = currentSeason()
                    query = """query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}season: MediaSeason, ${'$'}year: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, season: ${'$'}season, seasonYear: ${'$'}year, sort: POPULARITY_DESC, isAdult: false) {
                                id title { romaji english native } coverImage { extraLarge large } startDate { year } format
                            }
                        }
                    }"""
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                        .put("season", season).put("year", year)
                }
                "UPDATED_AT_DESC" -> {
                    query = """query (${'$'}page: Int, ${'$'}perPage: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, status: RELEASING, sort: UPDATED_AT_DESC, isAdult: false) {
                                id title { romaji english native } coverImage { extraLarge large } startDate { year } format
                            }
                        }
                    }"""
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }
                "SCORE_DESC" -> {
                    query = """query (${'$'}page: Int, ${'$'}perPage: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, sort: SCORE_DESC, isAdult: false) {
                                id title { romaji english native } coverImage { extraLarge large } startDate { year } format
                            }
                        }
                    }"""
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }
                else -> {
                    return@withContext newHomePageResponse(
                        HomePageList(request.name, emptyList()), hasNext = false
                    )
                }
            }

            val data = gql(query, vars)
            if (data == null) {
                log("AniList failed for '${request.name}'")
                return@withContext newHomePageResponse(
                    HomePageList(request.name, emptyList()), hasNext = false
                )
            }

            val pageData = data.optJSONObject("Page")
                ?: return@withContext newHomePageResponse(
                    HomePageList(request.name, emptyList()), hasNext = false
                )

            val media = pageData.optJSONArray("media") ?: JSONArray()
            val hasNext = pageData.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
            val shows = parseShows(media)
            log("section '${request.name}' -> ${shows.size} shows")
            newHomePageResponse(HomePageList(request.name, shows), hasNext)
        }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        log("search: '$query'")
        val q = """query (${'$'}search: String) {
            Page(page: 1, perPage: 40) {
                media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH, isAdult: false) {
                    id title { romaji english native } coverImage { extraLarge large } startDate { year } format
                }
            }
        }"""
        val data = gql(q, JSONObject().put("search", query)) ?: return@withContext emptyList()
        val media = data.optJSONObject("Page")?.optJSONArray("media") ?: return@withContext emptyList()
        parseShows(media)
    }

    // ════════════════════════════════════════════════════════════
    //  Load — flight cache first (outage-proof), then AniList
    // ════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val anilistId = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("invalid id: $url")

        // 1. flight cache (survives AniList outages)
        flightMedia[anilistId.toString()]?.let { media ->
            log("load id=$anilistId from FLIGHT cache")
            return@withContext buildLoadResponse(media, anilistId)
        }

        // 2. AniList chain
        val q = """query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                id
                title { romaji english native }
                description(asHtml: false)
                coverImage { extraLarge large }
                bannerImage
                genres
                status
                format
                episodes
                averageScore
                startDate { year }
                nextAiringEpisode { episode }
            }
        }"""
        val media = gql(q, JSONObject().put("id", anilistId))?.optJSONObject("Media")
            ?: throw ErrorLoadingException("couldn't load anime $anilistId")

        buildLoadResponse(media, anilistId)
    }

    private fun buildLoadResponse(media: JSONObject, anilistId: Int): LoadResponse {
        val title = pickTitle(media.optJSONObject("title")).ifEmpty { "Anime $anilistId" }
        val cover = media.optJSONObject("coverImage")?.let {
            it.optString("extraLarge").ifEmpty { it.optString("large") }
        } ?: ""
        val banner = media.optString("bannerImage").ifEmpty { null }
        val plot = stripHtml(media.optString("description"))
        val genres = media.optJSONArray("genres")?.let { g ->
            (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val status = media.optString("status")
        val format = media.optString("format")
        val isMovie = format == "MOVIE"
        val score = media.optInt("averageScore", 0)

        val nextAiring = media.optJSONObject("nextAiringEpisode")
        val epsCount = when {
            status == "RELEASING" && nextAiring != null ->
                (nextAiring.optInt("episode", 0) - 1).coerceAtLeast(0)
            else -> media.optInt("episodes", 0)
        }

        val episodes: List<Episode> = when {
            epsCount > 0 -> (1..epsCount).map { n ->
                newEpisode("$anilistId|$n") {
                    this.name = "Episode $n"
                    this.episode = n
                }
            }
            isMovie -> listOf(newEpisode("$anilistId|1") {
                this.name = "Movie"
                this.episode = 1
            })
            else -> emptyList()
        }

        newAnimeLoadResponse(title, "$anilistId", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = cover
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
                ?: media.optInt("seasonYear", 0).takeIf { it > 0 }
            this.score = if (score > 0) Score.from10(score / 10.0) else null
            this.showStatus = when (status) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  loadLinks — /api/miruro (unaffected by AniList outages)
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

        // ── primary: /api/miruro (verified live) ──
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

        // ── fallback: video-cache API ──
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
                            if (sUrl.isNotBlank()) {
                                subtitleCallback(SubtitleFile(s.optString("lang", "en"), sUrl))
                            }
                        }
                    }
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            logErr("video-cache", e)
        }

        // ── last resort: direct URL pattern ──
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
