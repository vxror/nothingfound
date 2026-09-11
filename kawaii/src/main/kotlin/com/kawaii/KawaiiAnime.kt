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
        private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        @Volatile private var homeFetchedAt = 0L
        private const val HOME_TTL_MS = 10 * 60_000L
        private val homeSections = ConcurrentHashMap<String, List<SearchResponse>>()
        private val mediaCache = ConcurrentHashMap<String, JSONObject>()
        private val translationCache = ConcurrentHashMap<String, String>()

        private val SECTION_MAP = mapOf(
            "TRENDING"         to "trending",
            "POPULAR"          to "popular",
            "THIS_SEASON"      to "thisSeason",
            "RECENTLY_UPDATED" to "recentlyUpdated",
            "TOP_RATED"        to "topRated"
        )

        fun extractId(raw: String?): Int? {
            if (raw.isNullOrBlank()) return null
            return Regex("""(\d+)""").findAll(raw).lastOrNull()
                ?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun log(msg: String) { Log.d(TAG, msg) }
    private fun logErr(s: String, e: Throwable) { Log.e(TAG, "$s: ${e.message}", e) }
    private fun headers() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9"
    )

    // ── RSC parser (unchanged) ────────────────────────────────────

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
                if (root.optInt("id", 0) == targetId &&
                    root.has("title") && root.has("description") && root.has("episodes")) return root
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
                if (root.has("id") && root.has("title") && root.has("coverImage") &&
                    root.has("format") && root.has("status") && root.has("episodes")) out.add(root)
                val it = root.keys()
                while (it.hasNext()) collectMedia(root.opt(it.next()), out, depth + 1)
            }
            is JSONArray -> for (i in 0 until root.length()) collectMedia(root.opt(i), out, depth + 1)
        }
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
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id",
            if (fmt == "MOVIE") TvType.AnimeMovie else TvType.Anime) {
            posterUrl = cover
            this.year = m.optInt("seasonYear", 0).takeIf { it > 0 }
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

    // ── Search — RSC → /api/anilist GraphQL proxy → home cache ───

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        log("search: '$query'")

        // 1. try server-side AniList GraphQL proxy at /api/anilist
        try {
            val gqlBody = JSONObject()
                .put("query", """query (${'$'}search: String) {
                    Page(page: 1, perPage: 40) {
                        media(type: ANIME, search: ${'$'}search, sort: SEARCH_MATCH, isAdult: false) {
                            id title { romaji english native } coverImage { extraLarge large } startDate { year } format status episodes
                        }
                    }
                }""")
                .put("variables", JSONObject().put("search", query))
                .toString()
                .toRequestBody("application/json".toMediaType())

            for (endpoint in listOf("$mainUrl/api/anilist", "$mainUrl/api/graphql", "$mainUrl/api/proxy/anilist")) {
                try {
                    val res = app.post(endpoint, requestBody = gqlBody, headers = mapOf(
                        "User-Agent" to UA,
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    ))
                    log("search[proxy] $endpoint -> HTTP ${res.code} len=${res.text.length}")
                    if (!res.isSuccessful) continue
                    val json = JSONObject(res.text)
                    val data = json.optJSONObject("data") ?: json
                    val arr = data.optJSONObject("Page")?.optJSONArray("media")
                        ?: data.optJSONArray("media")
                        ?: continue
                    val out = ArrayList<SearchResponse>()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        mediaToShow(m)?.let { out.add(it) }
                    }
                    if (out.isNotEmpty()) { log("search[proxy] -> ${out.size}"); return@withContext out }
                } catch (e: Exception) { logErr("search[proxy] $endpoint", e) }
            }
        } catch (e: Exception) { logErr("search[proxy] init", e) }

        // 2. try the search page RSC
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
                    val id = m.optInt("id", 0)
                    if (id > 0 && seen.add(id)) mediaToShow(m)?.let { out.add(it) }
                }
                if (out.isNotEmpty()) { log("search[rsc] -> ${out.size}"); return@withContext out }
            }
        } catch (e: Exception) { logErr("search rsc", e) }

        // 3. home-cache substring fallback
        try {
            ensureHomeCache()
            val needle = query.lowercase().trim()
            val tokens = needle.split(Regex("\\s+")).filter { it.length >= 2 }
            val out = ArrayList<SearchResponse>()
            val seen = HashSet<Int>()
            for (m in mediaCache.values) {
                val t = m.optJSONObject("title") ?: continue
                val candidates = listOf(
                    t.optString("english"), t.optString("romaji"), t.optString("native")
                ).filter { it.isNotBlank() }.map { it.lowercase() }
                // match if any token appears in any title
                val hit = tokens.isNotEmpty() && candidates.any { c ->
                    tokens.all { tok -> c.contains(tok) } || tokens.any { tok -> c.contains(tok) }
                }
                if (!hit) continue
                val id = m.optInt("id", 0)
                if (id <= 0 || !seen.add(id)) continue
                mediaToShow(m)?.let { out.add(it) }
            }
            log("search[cache] -> ${out.size}")
            out
        } catch (e: Exception) { logErr("search cache", e); emptyList() }
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val id = extractId(url) ?: throw ErrorLoadingException("bad id: $url")
        mediaCache[id.toString()]?.let { return@withContext buildLoadResponse(it, id) }
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
                return@withContext buildLoadResponse(media, id)
            }
        } catch (e: Exception) { logErr("detail", e) }
        throw ErrorLoadingException("couldn't load anime $id")
    }

    // ── Translation ──────────────────────────────────────────────

    private suspend fun translateToAr(text: String): String {
        if (text.isBlank()) return text
        translationCache[text]?.let { return it }

        // Google Translate free endpoint. Response is [[["translated","src",...], ...], ...]
        val truncated = if (text.length > 4500) text.substring(0, 4500) else text
        try {
            val enc = URLEncoder.encode(truncated, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=en&tl=ar&dt=t&q=$enc"
            val res = app.get(url, headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "application/json"
            ))
            if (!res.isSuccessful) return text
            val arr = JSONArray(res.text)
            val outer = arr.optJSONArray(0) ?: return text
            val sb = StringBuilder()
            for (i in 0 until outer.length()) {
                val chunk = outer.optJSONArray(i) ?: continue
                val part = chunk.optString(0, "")
                if (part.isNotEmpty()) sb.append(part)
            }
            val result = sb.toString().ifBlank { return text }
            translationCache[text] = result
            result
        } catch (e: Exception) {
            logErr("translate", e); text
        }
    }

    private suspend fun buildLoadResponse(m: JSONObject, id: Int): LoadResponse {
        val tObj = m.optJSONObject("title")
        val title = tObj?.optString("english")
            ?.ifEmpty { tObj.optString("romaji").ifEmpty { tObj.optString("native") } }
            .orEmpty().ifEmpty { "Anime $id" }
        val cObj = m.optJSONObject("coverImage")
        val cover = cObj?.optString("extraLarge")?.ifEmpty { cObj.optString("large") } ?: ""
        val banner = m.optString("bannerImage").ifEmpty { null }
        val plotEn = stripHtml(m.optString("description"))
        val plot = if (plotEn.isNotBlank()) translateToAr(plotEn) else plotEn
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

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split("|")
        if (parts.size < 2) return@withContext false
        val showId = extractId(parts[0])?.toString() ?: return@withContext false
        val epNum = parts[1]
        log("loadLinks: showId=$showId ep=$epNum")

        for (attempt in 1..2) {
            try {
                val apiUrl = "$mainUrl/api/miruro?anilistId=$showId&ep=$epNum&category=sub"
                val res = app.get(apiUrl, headers = mapOf(
                    "User-Agent" to UA, "Referer" to "$mainUrl/",
                    "Accept" to "*/*", "Origin" to mainUrl
                ))
                if (!res.isSuccessful) continue
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
                if (emitted > 0) return@withContext true
            } catch (e: Exception) { logErr("miruro[$attempt]", e) }
        }

        val direct = "$VIDEO_HOST/video/$showId-ep$epNum"
        callback(newExtractorLink(name, "Kawaii (Direct)", direct, ExtractorLinkType.VIDEO) {
            this.referer = "$mainUrl/"
            quality = Qualities.Unknown.value
            this.headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/")
        })
        for ((lang, url) in listOf(
            "Arabic"  to "$VIDEO_HOST/subtitle/$showId-ep$epNum-Arabic-0.vtt",
            "English" to "$VIDEO_HOST/subtitle/$showId-ep$epNum-English-1.vtt"
        )) {
            try {
                val r = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
                if (r.isSuccessful && r.text.contains("WEBVTT", ignoreCase = true))
                    subtitleCallback(SubtitleFile(lang, url))
            } catch (_: Exception) {}
        }
        true
    }
}
