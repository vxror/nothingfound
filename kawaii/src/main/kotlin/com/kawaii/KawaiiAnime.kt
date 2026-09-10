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
import java.util.Calendar

class KawaiiAnime : MainAPI() {
    override var mainUrl = "https://kawaiianime.cc"
    override var name = "KawaiiAnime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        private const val TAG = "KawaiiAnime"
        private const val ANILIST_GQL = "https://graphql.anilist.co"
        private const val VIDEO_HOST = "https://video.kawaii-anime.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

        private val SHOW_FIELDS = """
            id
            title { romaji english native }
            coverImage { extraLarge large }
            startDate { year }
            format
        """
    }

    // ---- Log detector: shows up in `adb logcat -s KawaiiAnime:D` ----
    private fun log(msg: String) { Log.d(TAG, msg) }

    private fun logErr(stage: String, e: Throwable) { Log.e(TAG, "$stage: ${e.message}", e) }

    private suspend fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject? {
        return try {
            val body = JSONObject()
                .put("query", query)
                .put("variables", variables)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val res = app.post(
                ANILIST_GQL,
                requestBody = body,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                )
            ).text
            val data = JSONObject(res).optJSONObject("data")
            if (data == null) log("gql returned no data block")
            data
        } catch (e: Exception) {
            logErr("gql", e)
            null
        }
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

    override val mainPage = mainPageOf(
        "TRENDING_DESC"   to "Trending",
        "POPULARITY_DESC" to "Popular",
        "THIS_SEASON"     to "This Season",
        "UPDATED_AT_DESC" to "Recently Updated",
        "SCORE_DESC"      to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            log("getMainPage: section='${request.name}' data='${request.data}' page=$page")

            val perPage = 20
            val query: String
            val vars: JSONObject

            when (request.data) {
                "TRENDING_DESC" -> {
                    query = """
                        query (${'$'}page: Int, ${'$'}perPage: Int) {
                          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, sort: TRENDING_DESC, isAdult: false) { $SHOW_FIELDS }
                          }
                        }
                    """.trimIndent()
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }

                "POPULARITY_DESC" -> {
                    query = """
                        query (${'$'}page: Int, ${'$'}perPage: Int) {
                          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) { $SHOW_FIELDS }
                          }
                        }
                    """.trimIndent()
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }

                "THIS_SEASON" -> {
                    val (season, year) = currentSeason()
                    log("THIS_SEASON -> $season $year")
                    query = """
                        query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}season: MediaSeason, ${'$'}year: Int) {
                          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(
                              type: ANIME,
                              season: ${'$'}season,
                              seasonYear: ${'$'}year,
                              sort: POPULARITY_DESC,
                              isAdult: false
                            ) { $SHOW_FIELDS }
                          }
                        }
                    """.trimIndent()
                    vars = JSONObject()
                        .put("page", page)
                        .put("perPage", perPage)
                        .put("season", season)
                        .put("year", year)
                }

                "UPDATED_AT_DESC" -> {
                    query = """
                        query (${'$'}page: Int, ${'$'}perPage: Int) {
                          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(
                              type: ANIME,
                              status: RELEASING,
                              sort: UPDATED_AT_DESC,
                              isAdult: false
                            ) { $SHOW_FIELDS }
                          }
                        }
                    """.trimIndent()
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }

                "SCORE_DESC" -> {
                    query = """
                        query (${'$'}page: Int, ${'$'}perPage: Int) {
                          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            pageInfo { hasNextPage }
                            media(type: ANIME, sort: SCORE_DESC, isAdult: false) { $SHOW_FIELDS }
                          }
                        }
                    """.trimIndent()
                    vars = JSONObject().put("page", page).put("perPage", perPage)
                }

                else -> {
                    log("unknown section data='${request.data}' — returning empty")
                    return@withContext newHomePageResponse(
                        HomePageList(request.name, emptyList()),
                        hasNext = false
                    )
                }
            }

            val data = gql(query, vars)
            if (data == null) {
                log("AniList call failed for section '${request.name}'")
                return@withContext newHomePageResponse(
                    HomePageList(request.name, emptyList()),
                    hasNext = false
                )
            }

            val pageData = data.optJSONObject("Page")
            if (pageData == null) {
                log("no Page object in AniList response")
                return@withContext newHomePageResponse(
                    HomePageList(request.name, emptyList()),
                    hasNext = false
                )
            }

            val media = pageData.optJSONArray("media") ?: JSONArray()
            val hasNext = pageData.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
            val shows = parseShows(media)
            log("section '${request.name}' -> ${shows.size} shows, hasNext=$hasNext")

            newHomePageResponse(
                HomePageList(request.name, shows),
                hasNext
            )
        }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        log("search: '$query'")
        val q = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 40) {
                media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH, isAdult: false) {
                  id
                  title { romaji english native }
                  coverImage { extraLarge large }
                  startDate { year }
                  format
                }
              }
            }
        """.trimIndent()
        val data = gql(q, JSONObject().put("search", query)) ?: return@withContext emptyList()
        val media = data.optJSONObject("Page")?.optJSONArray("media") ?: return@withContext emptyList()
        val out = parseShows(media)
        log("search '$query' -> ${out.size} results")
        out
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        log("load: url='$url'")
        val anilistId = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("invalid id: $url")

        val q = """
            query (${'$'}id: Int) {
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
                streamingEpisodes { title thumbnail }
              }
            }
        """.trimIndent()

        val media = gql(q, JSONObject().put("id", anilistId))?.optJSONObject("Media")
            ?: throw ErrorLoadingException("AniList returned nothing for id $anilistId")

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

        val streamEps = media.optJSONArray("streamingEpisodes")
        fun epName(n: Int): String {
            val t = streamEps?.optJSONObject(n - 1)?.optString("title") ?: ""
            return t.ifEmpty { "Episode $n" }
        }
        fun epThumb(n: Int): String? {
            return streamEps?.optJSONObject(n - 1)?.optString("thumbnail")?.takeIf { it.isNotBlank() }
        }

        val episodes: List<Episode> = when {
            epsCount > 0 -> (1..epsCount).map { n ->
                newEpisode("$anilistId|$n") {
                    this.name = epName(n)
                    this.episode = n
                    this.posterUrl = epThumb(n)
                }
            }
            isMovie -> listOf(newEpisode("$anilistId|1") {
                this.name = "Movie"
                this.episode = 1
            })
            else -> emptyList()
        }

        log("load id=$anilistId title='$title' format=$format eps=${episodes.size}")

        newAnimeLoadResponse(title, "$anilistId", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = cover
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
            this.score = if (score > 0) Score.from10(score / 10.0) else null
            this.showStatus = when (status) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split("|")
        if (parts.size < 2) {
            log("loadLinks: bad data='$data'")
            return@withContext false
        }
        val showId = parts[0]
        val epNum = parts[1]
        val episodeId = "$showId-ep$epNum"
        log("loadLinks: showId=$showId ep=$epNum episodeId=$episodeId")

        // ---- Primary path: /api/miruro returns real sources + subtitles (verified live) ----
        try {
            val apiUrl = "$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub"
            log("GET $apiUrl")
            val res = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "$mainUrl/",
                    "Accept" to "*/*",
                    "Origin" to mainUrl
                )
            )
            log("miruro HTTP ${res.code} len=${res.text.length}")

            if (res.isSuccessful) {
                val root = JSONObject(res.text)
                val dataObj = root.optJSONObject("data") ?: root
                val headersObj = dataObj.optJSONObject("headers")
                val refFromApi = headersObj?.optString("Referer")?.ifBlank { null } ?: "$mainUrl/"

                val sources = dataObj.optJSONArray("sources")
                var emitted = 0
                if (sources != null && sources.length() > 0) {
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        val srcUrl = s.optString("url")
                        if (srcUrl.isBlank()) continue
                        val isM3u8 = s.optBoolean("isM3U8", false) || srcUrl.contains(".m3u8")
                        val qualityLabel = s.optString("quality")
                        val qualityInt = qualityLabel.filter { it.isDigit() }.toIntOrNull()
                            ?.let { if (it in 144..2160) it else null }
                            ?: Qualities.Unknown.value

                        log("emit source quality=$qualityLabel m3u8=$isM3u8 url=${srcUrl.take(80)}")
                        callback(newExtractorLink(
                            name, "Kawaii",
                            srcUrl,
                            if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
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
                } else {
                    log("no sources array in miruro response")
                }

                val subs = dataObj.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val sUrl = s.optString("url")
                        val sLang = s.optString("lang").ifEmpty { "English" }
                        if (sUrl.isNotBlank()) {
                            log("emit subtitle lang=$sLang url=${sUrl.take(80)}")
                            subtitleCallback(SubtitleFile(sLang, sUrl))
                        }
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

        // ---- Fallback: predict direct URL from the known pattern ----
        try {
            val direct = "$VIDEO_HOST/video/$episodeId"
            log("fallback direct: $direct")
            callback(newExtractorLink(name, "Kawaii (Direct)", direct, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "$mainUrl/"
                )
            })

            val subCandidates = listOf(
                "English" to "$VIDEO_HOST/subtitle/$episodeId-English-1.vtt",
                "Arabic"  to "$VIDEO_HOST/subtitle/$episodeId-Arabic-0.vtt"
            )
            for ((lang, url) in subCandidates) {
                try {
                    val head = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
                    if (head.isSuccessful && head.text.contains("WEBVTT", ignoreCase = true)) {
                        log("fallback subtitle ok lang=$lang")
                        subtitleCallback(SubtitleFile(lang, url))
                    }
                } catch (e: Exception) {
                    logErr("fallback sub $lang", e)
                }
            }
            return@withContext true
        } catch (e: Exception) {
            logErr("direct", e)
        }

        log("loadLinks exhausted — nothing emitted")
        false
    }
}
