package com.kawaii

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class KawaiiAnime : MainAPI() {
    override var mainUrl = "https://kawaiianime.cc"
    override var name = "KawaiiAnime"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    companion object {
        private const val ANILIST_GQL = "https://graphql.anilist.co"

        // video-cache API chain — new domain first, proven legacy fallback
        private val API_BASES = listOf(
            "https://kawaiianime.cc",
            "https://kawaii-anime.com"
        )
        private const val VIDEO_DIRECT = "https://video.kawaii-anime.com/video"
        private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"
    }

    private fun log(msg: String) { println("KawaiiDebug: $msg") }

    // ── AniList GraphQL layer (the site's entire catalog) ──

    private suspend fun gql(query: String, variables: JSONObject = JSONObject()): JSONObject? {
        return try {
            val body = JSONObject().put("query", query).put("variables", variables)
                .toString().toRequestBody("application/json".toMediaType())
            val res = app.post(ANILIST_GQL, requestBody = body,
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json")).text
            JSONObject(res).optJSONObject("data")
        } catch (e: Exception) { log("gql fail: ${e.message}"); null }
    }

    private fun stripHtml(s: String?): String =
        (s ?: "").replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
            .trim()

    private fun pickTitle(t: JSONObject?): String {
        if (t == null) return ""
        return t.optString("english").ifEmpty { t.optString("romaji").ifEmpty { t.optString("native") } }
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
            list.add(newAnimeSearchResponse(title, id, TvType.Anime) {
                posterUrl = cover
                this.year = m.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
            })
        }
        return list
    }

    // ── Main page: AniList sections ──

    override val mainPage = mainPageOf(
        "POPULARITY_DESC" to "Popular",
        "TRENDING_DESC" to "Trending",
        "SCORE_DESC" to "Top Rated",
        "RELEASING" to "Currently Airing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val sort = request.name
        val vars = JSONObject().put("page", page).put("perPage", 30)
        val mediaQuery = if (sort == "RELEASING") {
            """query (${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { hasNextPage }
                    media(type: ANIME, status: RELEASING, sort: POPULARITY_DESC) {
                        id title { romaji english native } coverImage { extraLarge large } startDate { year }
                    }
                }
            }"""
        } else {
            """query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort]) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { hasNextPage }
                    media(type: ANIME, sort: ${'$'}sort) {
                        id title { romaji english native } coverImage { extraLarge large } startDate { year }
                    }
                }
            }"""
        }
        if (sort != "RELEASING") vars.put("sort", sort)

        val data = gql(mediaQuery, vars) ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
        val pageData = data.optJSONObject("Page") ?: return@withContext newHomePageResponse(emptyList(), hasNext = false)
        val media = pageData.optJSONArray("media") ?: JSONArray()
        val hasNext = pageData.optJSONObject("pageInfo")?.optBoolean("hasNextPage") ?: false
        newHomePageResponse(parseShows(media), hasNext = hasNext)
    }

    // ── Search: AniList ──

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = """query (${'$'}search: String) {
            Page(page: 1, perPage: 40) {
                media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH) {
                    id title { romaji english native } coverImage { extraLarge large } startDate { year }
                }
            }
        }"""
        val data = gql(q, JSONObject().put("search", query)) ?: return@withContext emptyList()
        val media = data.optJSONObject("Page")?.optJSONArray("media") ?: return@withContext emptyList()
        parseShows(media)
    }

    // ── Load: AniList details + episode generation ──

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val anilistId = url.trim().removePrefix("/").substringBefore('?').toIntOrNull()
            ?: throw ErrorLoadingException("invalid id: $url")

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
                streamingEpisodes { title thumbnail }
            }
        }"""
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

        // episode titles from AniList streamingEpisodes when available
        val streamEps = media.optJSONArray("streamingEpisodes")
        fun epName(n: Int): String {
            val t = streamEps?.optJSONObject(n - 1)?.optString("title") ?: ""
            return t.ifEmpty { "Episode $n" }
        }

        val episodes: List<Episode> = when {
            epsCount > 0 -> (1..epsCount).map { n ->
                newEpisode("$anilistId|$n") { this.name = epName(n); this.episode = n }
            }
            isMovie -> listOf(newEpisode("$anilistId|1") { this.name = "Movie" })
            else -> emptyList()
        }

        newAnimeLoadResponse(title, "$anilistId", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = cover
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }
            this.rating = if (score > 0) score / 10.0 else null
            this.showStatus = when (status) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── loadLinks: the signed-URL chain ──

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split("|")
        if (parts.size < 2) return@withContext false
        val showId = parts[0]; val epNum = parts[1]
        val episodeId = "$showId-ep$epNum"

        // ── 1. video-cache API (returns signed mp4 + subtitles) ──
        for (base in API_BASES) {
            try {
                val res = app.get("$base/api/video-cache?episodeId=$episodeId",
                    headers = mapOf("User-Agent" to UA, "Referer" to "$base/", "Accept" to "application/json"))
                if (!res.isSuccessful) { log("video-cache $base -> HTTP ${res.code}"); continue }
                val json = try { JSONObject(res.text) } catch (_: Exception) { continue }
                val url = json.optString("url")
                if (url.isNotBlank()) {
                    log("video-cache OK via $base -> ${url.take(90)}")
                    callback(newExtractorLink(name, "Kawaii", url, ExtractorLinkType.VIDEO) {
                        this.referer = base
                        quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to UA)
                    })
                    // subtitles ride along in the same response
                    json.optJSONArray("subtitles")?.let { subs ->
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val sUrl = s.optString("url")
                            val sLang = s.optString("lang").ifEmpty { "en" }
                            if (sUrl.isNotBlank()) subtitleCallback(SubtitleFile(sLang, sUrl))
                        }
                    }
                    return@withContext true
                }
            } catch (e: Exception) { log("video-cache fail $base: ${e.message}") }
        }

        // ── 2. direct signed-URL fallback (works when the file is pre-cached) ──
        val direct = "$VIDEO_DIRECT/$episodeId"
        log("falling back to direct: $direct")
        callback(newExtractorLink(name, "Kawaii (Direct)", direct, ExtractorLinkType.VIDEO) {
            this.referer = "https://kawaii-anime.com/"
            quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to UA)
        })

        // ── 3. WebView intercept on the watch page (last resort) ──
        try {
            val rx = Regex("""video\.kawaii-anime\.com|downet\.net|\.mp4|\.m3u8""")
            val resolver = WebViewResolver(
                interceptUrl = rx, additionalUrls = listOf(rx),
                useOkhttp = false, timeout = 20_000L
            )
            val wv = app.get("https://kawaiianime.cc/watch/$showId?num=$epNum",
                referer = mainUrl, interceptor = resolver).url
            if (wv.isNotBlank() && (wv.contains(".mp4") || wv.contains(".m3u8") ||
                    wv.contains("video.kawaii") || wv.contains("downet"))) {
                log("WV intercepted -> ${wv.take(90)}")
                callback(newExtractorLink(name, "Kawaii (WV)", wv,
                    if (wv.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = mainUrl; quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) { log("WV fail: ${e.message}") }

        true
    }
}
