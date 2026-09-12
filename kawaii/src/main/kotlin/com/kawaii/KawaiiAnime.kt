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
    override var lang = "ar"
    override val hasMainPage = true

    companion object {
        private const val TAG = "KawaiiAnime"
        private const val VIDEO_HOST = "https://video.kawaii-anime.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

        @Volatile private var homeFetchedAt = 0L
        private const val HOME_TTL_MS = 10 * 60_000L
        private const val SEARCH_TTL_MS = 24 * 60 * 60_000L

        private val homeSections = ConcurrentHashMap<String, List<SearchResponse>>()
        private val mediaCache = ConcurrentHashMap<String, JSONObject>()
        private val translationCache = ConcurrentHashMap<String, String>()
        private val searchCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()

        private val SECTION_MAP = mapOf(
            "TRENDING"         to "trending",
            "POPULAR"          to "popular",
            "THIS_SEASON"      to "thisSeason",
            "RECENTLY_UPDATED" to "recentlyUpdated",
            "TOP_RATED"        to "topRated"
        )

        private val SITE_SEARCH_QUERY: String =
            "\n    query (\$page: Int, \$perPage: Int, \$search: String) {\n" +
            "      Page(page: \$page, perPage: \$perPage) {\n" +
            "        pageInfo { currentPage hasNextPage perPage }\n" +
            "        media(type: ANIME, search: \$search, sort: SEARCH_MATCH) { \n" +
            "  id\n" +
            "  idMal\n" +
            "  title { romaji english native }\n" +
            "  description(asHtml: false)\n" +
            "  coverImage { extraLarge large color }\n" +
            "  bannerImage\n" +
            "  genres\n" +
            "  format\n" +
            "  status\n" +
            "  episodes\n" +
            "  duration\n" +
            "  season\n" +
            "  seasonYear\n" +
            "  averageScore\n" +
            "  popularity\n" +
            "  trending\n" +
            "  favourites\n" +
            "  nextAiringEpisode { airingAt timeUntilAiring episode }\n" +
            " }\n" +
            "      }\n" +
            "    }\n" +
            "  "

        private val SITE_MEDIA_QUERY: String =
            "\n    query (\$id: Int) {\n" +
            "      Media(id: \$id, type: ANIME) { \n" +
            "  \n" +
            "  id\n" +
            "  idMal\n" +
            "  title { romaji english native }\n" +
            "  description(asHtml: false)\n" +
            "  coverImage { extraLarge large color }\n" +
            "  bannerImage\n" +
            "  genres\n" +
            "  format\n" +
            "  status\n" +
            "  episodes\n" +
            "  duration\n" +
            "  season\n" +
            "  seasonYear\n" +
            "  averageScore\n" +
            "  popularity\n" +
            "  trending\n" +
            "  favourites\n" +
            "  nextAiringEpisode { airingAt timeUntilAiring episode }\n" +
            "\n" +
            "  tags { name rank }\n" +
            "  source\n" +
            "  countryOfOrigin\n" +
            "  startDate { year month day }\n" +
            "  endDate { year month day }\n" +
            "  trailer { id site thumbnail }\n" +
            "  studios(isMain: true) { nodes { name } }\n" +
            "  characters(page: 1, perPage: 12, sort: ROLE) {\n" +
            "    edges {\n" +
            "      role\n" +
            "      node { id name { full native } image { large } }\n" +
            "      voiceActors(language: JAPANESE) { id name { full } image { large } }\n" +
            "    }\n" +
            "  }\n" +
            "  relations {\n" +
            "    edges {\n" +
            "      relationType\n" +
            "      node { id title { romaji english } type format coverImage { large } status }\n" +
            "    }\n" +
            "  }\n" +
            "  recommendations(page: 1, perPage: 8, sort: RATING_DESC) {\n" +
            "    nodes {\n" +
            "      mediaRecommendation { id title { romaji english } coverImage { large } averageScore }\n" +
            "    }\n" +
            "  }\n" +
            " }\n" +
            "    }\n" +
            "  "

        fun extractId(raw: String?): Int? {
            if (raw.isNullOrBlank()) return null
            val m = Regex("""/anime/(\d+)""").find(raw) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        private fun searchHeaders(referer: String) = mapOf(
            "accept" to "*/*",
            "accept-language" to "en-US,en;q=0.9",
            "content-type" to "application/json",
            "dnt" to "1",
            "origin" to "https://kawaiianime.cc",
            "priority" to "u=1, i",
            "referer" to referer,
            "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Quetta\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "sec-gpc" to "1",
            "user-agent" to UA
        )
    }

    private fun log(msg: String) { Log.d(TAG, msg) }
    private fun logErr(s: String, e: Throwable) { Log.e(TAG, "$s: ${e.message}", e) }
    private fun headers() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9"
    )

    // ════════════════════════════════════════════════════════════
    //  [v9] NULL-SAFE ACCESSORS — the API returns literal JSON null for
    //  english/description/bannerImage/averageScore etc. Android's optString
    //  on a null value can yield the STRING "null", which poisoned titles,
    //  posters and episode counts. Every read now goes through these.
    // ════════════════════════════════════════════════════════════

    /** optString that treats JSON null / "null" / blank as empty */
    private fun JSONObject.safeStr(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key).let { if (it == "null") "" else it }
    }

    /** optInt that treats JSON null / "null" as fallback */
    private fun JSONObject.safeInt(key: String, def: Int = 0): Int {
        if (!has(key) || isNull(key)) return def
        return try { optInt(key, def) } catch (_: Exception) { def }
    }

    /** optJSONObject that never returns a NULL-poisoned object */
    private fun JSONObject.safeObj(key: String): JSONObject? =
        if (has(key) && !isNull(key)) optJSONObject(key) else null

    /** optJSONArray, null-safe */
    private fun JSONObject.safeArr(key: String): JSONArray? =
        if (has(key) && !isNull(key)) optJSONArray(key) else null

    // ── RSC parser ────────────────────────────────────────────────

    private fun extractRscRows(html: String): Map<String, Any> {
        val stream = StringBuilder()
        val chunkRx = Regex("""self\.__next_f\.push\(\[\s*\d+\s*,\s*"((?:[^"\\]|\\.)*)"\s*\]\)""")
        chunkRx.findAll(html).forEach { m ->
            try {
                val unescaped = JSONObject("{\"s\":\"${m.groupValues[1]}\"}").optString("s")
                stream.append(unescaped).append('\n')
            } catch (_: Exception) {}
        }
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
        return rows
    }

    private fun resolve(v: Any?, rows: Map<String, Any>, depth: Int = 0): Any? {
        if (depth > 25 || v == null) return v
        return when (v) {
            is String -> {
                if (v.length > 1 && v[0] == '$' && v[1] != '"' && v[1] != 'L') {
                    val t = rows[v.substring(1)]
                    if (t != null) resolve(t, rows, depth + 1) else v
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

    private fun findSectionContainer(root: Any?, depth: Int = 0): JSONObject? {
        if (depth > 25 || root == null) return null
        when (root) {
            is JSONObject -> {
                if (root.has("trending") && root.has("popular") && root.has("thisSeason") &&
                    root.has("recentlyUpdated") && root.has("topRated")) return root
                val it = root.keys()
                while (it.hasNext()) {
                    val f = findSectionContainer(root.opt(it.next()), depth + 1)
                    if (f != null) return f
                }
            }
            is JSONArray -> for (i in 0 until root.length()) {
                val f = findSectionContainer(root.opt(i), depth + 1)
                if (f != null) return f
            }
        }
        return null
    }

    private fun findAnimeMedia(root: Any?, targetId: Int, depth: Int = 0): JSONObject? {
        if (depth > 25 || root == null) return null
        when (root) {
            is JSONObject -> {
                if (root.safeInt("id", -1) == targetId && isValidMedia(root)) return root
                val it = root.keys()
                while (it.hasNext()) {
                    val f = findAnimeMedia(root.opt(it.next()), targetId, depth + 1)
                    if (f != null) return f
                }
            }
            is JSONArray -> for (i in 0 until root.length()) {
                val f = findAnimeMedia(root.opt(i), targetId, depth + 1)
                if (f != null) return f
            }
        }
        return null
    }

    private fun collectMedia(root: Any?, out: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 25 || root == null) return
        when (root) {
            is JSONObject -> {
                if (isValidMedia(root)) out.add(root)
                val it = root.keys()
                while (it.hasNext()) collectMedia(root.opt(it.next()), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until root.length()) collectMedia(root.opt(i), out, depth + 1)
        }
    }

    /** [v9] the single validity gate — null-safe fields only */
    private fun isValidMedia(m: JSONObject): Boolean {
        val id = m.safeInt("id", -1)
        if (id <= 0) return false
        val t = m.safeObj("title") ?: return false
        val titleOk = t.safeStr("english").isNotBlank() ||
            t.safeStr("romaji").isNotBlank() ||
            t.safeStr("native").isNotBlank()
        if (!titleOk) return false
        val c = m.safeObj("coverImage")
        val coverOk = c != null && (
            c.safeStr("extraLarge").startsWith("http") ||
            c.safeStr("large").startsWith("http")
        )
        return coverOk
    }

    private fun mediaToShow(m: JSONObject): SearchResponse? {
        if (!isValidMedia(m)) return null
        val id = m.safeInt("id", 0)
        val t = m.safeObj("title") ?: return null
        val title = t.safeStr("english")
            .ifEmpty { t.safeStr("romaji").ifEmpty { t.safeStr("native") } }
        if (title.isBlank()) return null
        val c = m.safeObj("coverImage")
        val cover = c?.safeStr("extraLarge")?.ifEmpty { c.safeStr("large") } ?: ""
        val fmt = m.safeStr("format")
        mediaCache[id.toString()] = m
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id",
            if (fmt == "MOVIE") TvType.AnimeMovie else TvType.Anime) {
            posterUrl = cover
            this.year = m.safeInt("seasonYear", 0).takeIf { it > 0 }
        }
    }

    private suspend fun ensureHomeCache(): Boolean {
        if (System.currentTimeMillis() - homeFetchedAt < HOME_TTL_MS && homeSections.isNotEmpty()) return true
        return try {
            val html = app.get(mainUrl, headers = headers()).text
            val rows = extractRscRows(html)
            var container: JSONObject? = null
            outer@ for ((_, v) in rows) {
                val r: Any? = if (v is String && v.startsWith("$")) resolve(v, rows) else v
                val c = findSectionContainer(r)
                if (c != null) { container = c; break@outer }
            }
            if (container == null) return false
            val found = HashMap<String, List<SearchResponse>>()
            for ((csKey, rscKey) in SECTION_MAP) {
                val raw = container.opt(rscKey) ?: continue
                val arr = raw as? JSONArray ?: (resolve(raw, rows) as? JSONArray) ?: continue
                val shows = ArrayList<SearchResponse>()
                for (i in 0 until arr.length()) {
                    val obj = arr.opt(i) as? JSONObject ?: continue
                    mediaToShow(obj)?.let { shows.add(it) }
                }
                if (shows.isNotEmpty()) found[csKey] = shows
            }
            if (found.isEmpty()) return false
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
            if (!ensureHomeCache())
                return@withContext newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
            val shows = homeSections[request.data] ?: emptyList()
            newHomePageResponse(HomePageList(request.name, shows), hasNext = false)
        }

    // ── Search ────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val key = query.lowercase().trim()
        log("search: '$query'")

        searchCache[key]?.let { (ts, cached) ->
            if (System.currentTimeMillis() - ts < SEARCH_TTL_MS) {
                log("search[cached] -> ${cached.size}")
                return@withContext cached
            }
            searchCache.remove(key)
        }

        try {
            val vars = JSONObject()
                .put("page", 1)
                .put("perPage", 20)
                .put("search", query)
            val body = JSONObject()
                .put("query", SITE_SEARCH_QUERY)
                .put("variables", vars)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val enc = URLEncoder.encode(query, "UTF-8")
            val referer = "https://kawaiianime.cc/search?q=$enc"
            val res = app.post("$mainUrl/api/anilist", requestBody = body, headers = searchHeaders(referer))
            log("search[proxy] HTTP ${res.code} len=${res.text.length}")

            if (res.isSuccessful && res.text.length > 200) {
                val arr = JSONObject(res.text).safeObj("data")
                    ?.safeObj("Page")?.safeArr("media")
                if (arr != null) {
                    val out = ArrayList<SearchResponse>()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        mediaToShow(m)?.let { out.add(it) }
                    }
                    if (out.isNotEmpty()) {
                        searchCache[key] = System.currentTimeMillis() to out
                        log("search[proxy] -> ${out.size} (cached)")
                        return@withContext out
                    }
                }
            }
        } catch (e: Exception) { logErr("search proxy", e) }

        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val html = app.get("$mainUrl/search?q=$enc", headers = headers()).text
            if (html.contains("self.__next_f.push")) {
                val rows = extractRscRows(html)
                val collected = ArrayList<JSONObject>()
                for ((_, v) in rows) {
                    val r: Any? = if (v is String && v.startsWith("$")) resolve(v, rows) else v
                    collectMedia(r, collected)
                }
                val out = ArrayList<SearchResponse>()
                val seen = HashSet<Int>()
                for (m in collected) {
                    val id = m.safeInt("id", 0)
                    if (id > 0 && seen.add(id)) mediaToShow(m)?.let { out.add(it) }
                }
                if (out.isNotEmpty()) {
                    searchCache[key] = System.currentTimeMillis() to out
                    log("search[rsc] -> ${out.size}")
                    return@withContext out
                }
            }
        } catch (e: Exception) { logErr("search rsc", e) }

        ensureHomeCache()
        val tokens = key.split(Regex("\\s+")).filter { it.length >= 2 }
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<Int>()
        for (m in mediaCache.values) {
            val t = m.safeObj("title") ?: continue
            val titles = listOf(t.safeStr("english"), t.safeStr("romaji"), t.safeStr("native"))
                .filter { it.isNotBlank() }.map { it.lowercase() }
            val hit = tokens.isNotEmpty() && titles.any { ti ->
                tokens.all { tok -> ti.contains(tok) } || tokens.any { tok -> ti.contains(tok) }
            }
            if (!hit) continue
            val id = m.safeInt("id", 0)
            if (id <= 0 || !seen.add(id)) continue
            mediaToShow(m)?.let { out.add(it) }
        }
        log("search[cache] -> ${out.size}")
        out
    }

    // ── Load ──────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val id = extractId(url) ?: throw ErrorLoadingException("bad id: $url")
        mediaCache[id.toString()]?.let {
            log("load id=$id from cache")
            return@withContext buildLoadResponse(it, id)
        }
        try {
            val html = app.get("$mainUrl/anime/$id", headers = headers()).text
            val rows = extractRscRows(html)
            var media: JSONObject? = null
            outer@ for ((_, v) in rows) {
                val r: Any? = if (v is String && v.startsWith("$")) resolve(v, rows) else v
                val m = findAnimeMedia(r, id)
                if (m != null) { media = m; break@outer }
            }
            if (media != null) {
                mediaCache[id.toString()] = media
                log("load id=$id from RSC")
                return@withContext buildLoadResponse(media, id)
            }
        } catch (e: Exception) { logErr("load rsc", e) }

        try {
            val body = JSONObject()
                .put("query", SITE_MEDIA_QUERY)
                .put("variables", JSONObject().put("id", id))
                .toString()
                .toRequestBody("application/json".toMediaType())
            val referer = "https://kawaiianime.cc/anime/$id"
            val res = app.post("$mainUrl/api/anilist", requestBody = body, headers = searchHeaders(referer))
            log("load[proxy] HTTP ${res.code} len=${res.text.length}")
            if (res.isSuccessful) {
                val m = JSONObject(res.text).safeObj("data")?.safeObj("Media")
                if (m != null) {
                    mediaCache[id.toString()] = m
                    return@withContext buildLoadResponse(m, id)
                }
            }
        } catch (e: Exception) { logErr("load proxy", e) }

        throw ErrorLoadingException("couldn't load anime $id")
    }

    private suspend fun translateToAr(text: String, id: Int): String {
        if (text.isBlank()) return text
        translationCache[text]?.let { return it }
        try {
            val enc = URLEncoder.encode(text, "UTF-8")
            val url = "$mainUrl/api/translate-description?id=$id&text=$enc"
            val res = app.get(url, headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "*/*",
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            ))
            log("translate HTTP ${res.code}")
            if (res.isSuccessful) {
                val ar = JSONObject(res.text).safeStr("arabic")
                if (ar.isNotBlank()) {
                    translationCache[text] = ar
                    return ar
                }
            }
        } catch (e: Exception) { logErr("translate", e) }
        return text
    }

    private suspend fun buildLoadResponse(m: JSONObject, id: Int): LoadResponse {
        val tObj = m.safeObj("title")
        val title = tObj?.safeStr("english")
            ?.ifEmpty { tObj.safeStr("romaji").ifEmpty { tObj.safeStr("native") } }
            .orEmpty().ifEmpty { "Anime $id" }
        val cObj = m.safeObj("coverImage")
        val cover = cObj?.safeStr("extraLarge")?.ifEmpty { cObj.safeStr("large") } ?: ""
        val banner = m.safeStr("bannerImage").ifEmpty { null }
        val plotEn = stripHtml(m.safeStr("description"))
        val plot = if (plotEn.isNotBlank()) translateToAr(plotEn, id) else plotEn
        val genres = m.safeArr("genres")?.let { g ->
            (0 until g.length()).map { g.optString(it) }.filter { it.isNotBlank() && it != "null" }
        } ?: emptyList()
        val status = m.safeStr("status")
        val format = m.safeStr("format")
        val isMovie = format == "MOVIE"
        val score = m.safeInt("averageScore", 0)

        val episodesField = m.safeInt("episodes", 0)
        val nextEp = m.safeObj("nextAiringEpisode")?.safeInt("episode", 0) ?: 0
        val epsCount = maxOf(episodesField, (nextEp - 1).coerceAtLeast(0)).coerceAtLeast(0)

        log("buildLoadResponse id=$id epsField=$episodesField nextEp=$nextEp -> $epsCount")

        val episodes: List<Episode> = when {
            epsCount > 0 -> (1..epsCount).map { n ->
                newEpisode("$id|$n") { this.name = "الحلقة $n"; this.episode = n }
            }
            isMovie -> listOf(newEpisode("$id|1") { this.name = "الفيلم"; this.episode = 1 })
            else -> emptyList()
        }

        return newAnimeLoadResponse(title, "$id", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = cover
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = m.safeInt("seasonYear", 0).takeIf { it > 0 }
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
        val showId = extractId(parts[0])?.toString()
            ?: Regex("""^(\d+)$""").find(parts[0].trim())?.groupValues?.get(1)
            ?: return@withContext false
        val epNum = parts[1]
        log("loadLinks: showId=$showId ep=$epNum")

        val subHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )

        for (attempt in 1..2) {
            try {
                val res = app.get("$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub",
                    headers = mapOf(
                        "User-Agent" to UA, "Referer" to "$mainUrl/",
                        "Accept" to "*/*", "Origin" to mainUrl
                    ))
                if (!res.isSuccessful) continue
                val root = JSONObject(res.text)
                val d = root.safeObj("data") ?: root
                val ref = d.safeObj("headers")?.safeStr("Referer")
                    ?.ifBlank { null } ?: "$mainUrl/"
                var emitted = 0
                d.safeArr("sources")?.let { srcs ->
                    for (i in 0 until srcs.length()) {
                        val s = srcs.optJSONObject(i) ?: continue
                        val u = s.safeStr("url"); if (u.isBlank()) continue
                        val m3u8 = s.optBoolean("isM3U8", false) || u.contains(".m3u8")
                        val q = s.safeStr("quality").filter { it.isDigit() }.toIntOrNull()
                            ?.let { if (it in 144..2160) it else null }
                            ?: Qualities.Unknown.value
                        callback(newExtractorLink(name, "Kawaii", u,
                            if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = ref; this.quality = q
                            this.headers = mapOf("User-Agent" to UA, "Referer" to ref, "Origin" to mainUrl)
                        })
                        emitted++
                    }
                }
                d.safeArr("subtitles")?.let { subs ->
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val u = s.safeStr("url"); if (u.isBlank()) continue
                        val lang = s.safeStr("lang").ifEmpty { "Arabic" }
                        val styled = KawaiiSubs.styledSubtitleUrl(u, lang) ?: u
                        log("subtitle emit: $lang")
                        subtitleCallback(SubtitleFile(lang, styled))
                    }
                }
                if (emitted > 0) return@withContext true
            } catch (_: Exception) {}
        }

        callback(newExtractorLink(name, "Kawaii (Direct)",
            "$VIDEO_HOST/video/$showId-ep$epNum", ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
            quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
        })
        for ((lang, vttUrl) in listOf(
            "Arabic"  to "$VIDEO_HOST/subtitle/$showId-ep$epNum-Arabic-0.vtt",
            "English" to "$VIDEO_HOST/subtitle/$showId-ep$epNum-English-1.vtt"
        )) {
            try {
                val r = app.get(vttUrl, headers = subHeaders)
                if (r.isSuccessful && r.text.contains("WEBVTT", ignoreCase = true)) {
                    val styled = KawaiiSubs.styledSubtitleUrl(vttUrl, lang) ?: vttUrl
                    subtitleCallback(SubtitleFile(lang, styled))
                }
            } catch (_: Exception) {}
        }
        true
    }
}
