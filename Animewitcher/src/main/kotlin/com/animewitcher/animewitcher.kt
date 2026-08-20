package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AnimeWitcherProvider : MainAPI() {
    override var mainUrl = "https://animewitcher.com"
    override var name = "AnimeWitcher"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true

    private var algoliaAppId = "D8LH9I7ZL7"
    private var algoliaApiKey = "b56c01ef52540ef334bcdbaa00ded9e4"
    private val FIREBASE_PROJECT_ID = "animewitcher-1c66d"
    private val serverWordsCache = HashMap<String, ServerWords>()

    // 🆕 EPISODE CACHING
    private val episodesCache = ConcurrentHashMap<String, List<EpisodeInfo>>()
    private val episodeCacheTimestamps = ConcurrentHashMap<String, Long>()
    private val EPISODE_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    companion object {
        private const val FIREBASE_API_KEY = "AIzaSyC4UTcl1j5c0JG4emo1WQvsVWFKxhYEULI"
        private const val USER_EMAIL = ""
        private const val USER_PASSWORD = ""
        private var idToken: String? = null
        private var refreshToken: String? = null
        private var genEmail = ""
        private var genPassword = ""
    }

    init {
        MegaProxy.start()
        ZenCore.log("✅ AnimeWitcherProvider initialized")
    }

    data class EpisodeInfo(
        val id: String,
        val name: String?,
        val number: Int,
        val imageUrl: String? = null
    )

    data class ServerModel(
        val name: String?,
        val link: String?,
        val quality: String?,
        val originalLink: String?,
        val openBrowser: Boolean,
        val directLink: Boolean = false
    )

    data class ServerWords(
        val name: String,
        val word1: String?,
        val word2: String?,
        val word3: String?,
        val word4: String?
    )

    // ==================== 🆕 ENHANCED MAIN PAGE (6 Sections) ====================

    override val mainPage = mainPageOf(
        "recent_episodes" to "آخر الحلقات",
        "trending" to "الأكثر مشاهدة",
        "top_rated" to "الأعلى تقييماً",
        "dubbed" to "مدبلج",
        "movies" to "أفلام",
        "ongoing" to "يُعرض الآن"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val results = when (request.name) {
                "recent_episodes" -> fetchRecentEpisodes()
                "trending" -> fetchAlgoliaList("anime_list", "", sortBy = "views:desc")
                "top_rated" -> fetchAlgoliaList("anime_list", "", sortBy = "rating:desc")
                "dubbed" -> fetchAlgoliaList("anime_list", "", facetFilters = "dubbed:true")
                "movies" -> fetchAlgoliaList("anime_list", "", facetFilters = "type:movie")
                "ongoing" -> fetchAlgoliaList("anime_list", "", facetFilters = "state:يتم عرضه", sortBy = "updated_at:desc")
                else -> emptyList()
            }

            newHomePageResponse(request.name, results, hasNext = results.size >= 25)
        } catch (e: Exception) {
            ZenCore.logError("getMainPage", e)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchAlgoliaList("anime_list", query)
    }

    // ==================== LOAD (FIXED) ====================

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val dataParam = url.substringAfter("?data=", "")
            val animeData = if (dataParam.isNotEmpty()) {
                JSONObject(URLDecoder.decode(dataParam, "UTF-8"))
            } else {
                val animeId = sanitizeId(url)
                getAnimeFromFirestore(animeId) ?: return null
            }

            val title = animeData.optString("name")
            if (title.isEmpty()) return null

            val poster = posterFrom(animeData)
            val description = getArabicDescription(animeData)
            val year = animeData.optJSONObject("details")?.optString("year")?.toIntOrNull()

            // ✅ FIX: Keep rating as Double
            val ratingDouble = animeData.optJSONObject("details")?.optString("rating")
                ?.replace(",", ".")?.toDoubleOrNull()

            // Genres
            val genres = mutableListOf<String>()
            animeData.optJSONArray("tags")?.let { tags ->
                for (i in 0 until tags.length()) {
                    genres.add(tags.optString(i))
                }
            }

            val type = animeData.optString("type", "tv")
            val isMovie = type.contains("movie", true) || type.contains("فيلم", true)

            if (isMovie) {
                // ✅ FIXED: Movie load response
                return newMovieLoadResponse(
                    name = title,
                    url = url,
                    dataUrl = url,
                    type = TvType.AnimeMovie
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = ratingDouble?.let { Score(it * 10) }
                    this.plot = description
                    this.tags = genres
                }
            } else {
                // ✅ FIXED: TV Series load response
                val episodes = getEpisodes(animeData)
                return newTvSeriesLoadResponse(
                    name = title,
                    url = url,
                    showType = TvType.Anime,
                    episodes = episodes.map { ep ->
                        newEpisode("$url&ep=${ep.number}") {
                            this.name = ep.name ?: "الحلقة ${ep.number}"
                            this.episode = ep.number
                            this.posterUrl = ep.imageUrl
                        }
                    }
                ) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = ratingDouble?.let { Score(it * 10) }
                    this.plot = description
                    this.tags = genres
                    this.showStatus = getShowStatus(animeData)
                }
            }
        } catch (e: Exception) {
            ZenCore.logError("load", e)
            null
        }
    }

    // ==================== 🚀 FIXED LOAD LINKS (PARALLEL) ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startTime = System.currentTimeMillis()
        ZenCore.log("🚀 loadLinks started")

        return try {
            val servers = extractServersFromData(data)
            if (servers.isEmpty()) {
                ZenCore.log("⚠️ No servers found")
                return false
            }

            ZenCore.log("📡 Found ${servers.size} servers: ${servers.mapNotNull { it.name }}")

            // ✅ FIXED: PARALLEL EXTRACTION using coroutineScope directly
            val allLinks = mutableListOf<ExtractorLink>()
            val lock = Any()

            coroutineScope {
                val jobs = servers.map { server ->
                    launch(Dispatchers.IO) {
                        try {
                            extractFromServer(server, data, subtitleCallback) { link ->
                                synchronized(lock) { allLinks.add(link) }
                            }
                        } catch (e: Exception) {
                            ZenCore.logError("extractFromServer:${server.name}", e)
                        }
                    }
                }

                // Wait for all jobs with timeout
                withTimeoutOrNull(20000L) {
                    jobs.joinAll()
                }
            }

            // 🧹 DEDUPLICATE and sort by quality
            val deduplicated = ZenCore.deduplicateLinks(allLinks)

            // Deliver all unique links
            deduplicated.forEach(callback)

            val elapsed = System.currentTimeMillis() - startTime
            ZenCore.totalLinksExtracted.addAndGet(deduplicated.size)
            ZenCore.log("✅ loadLinks: ${deduplicated.size} unique links in ${elapsed}ms " +
                "(from ${servers.size} servers, ${allLinks.size} total before dedup)")

            deduplicated.isNotEmpty()
        } catch (e: Exception) {
            ZenCore.logError("loadLinks", e)
            false
        }
    }

    // ==================== HELPER METHODS ====================

    private fun extractServersFromData(data: String): List<ServerModel> {
        return try {
            val j = JSONObject(URLDecoder.decode(data.substringAfter("?data=", data), "UTF-8"))
            val serversArray = j.optJSONArray("servers")
                ?: j.optJSONArray("sources")
                ?: return emptyList()

            val servers = mutableListOf<ServerModel>()
            for (i in 0 until serversArray.length()) {
                val serverObj = serversArray.optJSONObject(i) ?: continue
                val name = serverObj.optString("name", "Server ${i + 1}")
                val link = serverObj.optString("link")
                val quality = serverObj.optString("quality")
                val openBrowser = serverObj.optBoolean("openBrowser", false)
                val directLink = serverObj.optBoolean("directLink", false)

                if (link.isBlank() || openBrowser) continue
                servers.add(ServerModel(name, link, quality, link, openBrowser, directLink))
            }
            servers
        } catch (e: Exception) {
            ZenCore.logError("extractServersFromData", e)
            emptyList()
        }
    }

    private suspend fun extractFromServer(
        server: ServerModel,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = server.link ?: return
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        val quality = getQualityAsInt(server.quality)

        try {
            // 1. Direct link
            if (server.directLink || url.contains(".mp4") || url.contains(".m3u8")) {
                ZenDeliveryEngine.deliverSmartLink(
                    server.name ?: "Direct", server.name ?: "Direct",
                    url, null, quality, callback
                )
                return
            }

            // 2. Mega.nz
            if (host.contains("mega.nz") || host.contains("mega.co.nz")) {
                MegaProxy.resolve(url)?.let { localUrl ->
                    callback(newExtractorLink(
                        server.name ?: "Mega", server.name ?: "Mega",
                        localUrl, ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    })
                    return
                }
            }

            // 3. MegaPlay/Vidwish/Vidtube AJAX
            if (host.contains("megaplay") || host.contains("vidwish") || host.contains("vidtube")) {
                MegaPlay.extractMegaPlayUrl(
                    url, null, "https://$host",
                    server.name ?: "MegaPlay", subtitleCallback, callback
                )
                return
            }

            // 4. Heavyweight extractors (25+ hosts)
            if (ZenHeavyweightExtractors.tryExtract(
                    host, url, null, server.name ?: host, quality, callback
                )) {
                return
            }

            // 5. VidTube with P.A.C.K.E.R.
            if (VidTubeExtractor.extract(url, server.name ?: "VidTube", quality, callback)) {
                return
            }

            // 6. Enhanced Universal sniffer
            ZenUniversalSniffer.deepScan(url, server.name ?: "Unknown", quality, callback)

        } catch (e: Exception) {
            ZenCore.logError("extractFromServer:${server.name}", e)
        }
    }

    // ==================== ALGOLIA & FIRESTORE ====================

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun algoliaUrl(index: String) =
        "https://${algoliaAppId}-dsn.algolia.net/1/indexes/$index/query"

    private fun firestoreDocUrl(path: String) =
        "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$path"

    private fun getQualityAsInt(quality: String?): Int =
        quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0

    private fun posterFrom(obj: JSONObject): String {
        val p = obj.optJSONObject("poster")
        val d = obj.optJSONObject("details")
        var url = obj.optString("poster_uri").ifEmpty { obj.optString("cover_uri") }
            .ifEmpty { obj.optString("image") }
            .ifEmpty { obj.optString("poster_url") }
            .ifEmpty { obj.optString("thumb_uri") }
            .ifEmpty { obj.optString("cover") }

        if (url.isEmpty()) url = p?.optString("large").orEmpty()
            .ifEmpty { p?.optString("medium").orEmpty() }
        if (url.isEmpty() && p == null) url = obj.optString("poster", "")
        if (url.isEmpty() && d != null) url = d.optString("poster_uri")
            .ifEmpty { d.optString("image") }
            .ifEmpty { d.optString("poster_url") }
        return url
    }

    private fun cleanId(raw: String): String {
        var s = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            if (s.startsWith("/")) { s = s.removePrefix("/"); changed = true }
            if (s.startsWith("anime_list/")) { s = s.removePrefix("anime_list/"); changed = true }
        }
        return s
    }

    private fun sanitizeId(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        try {
            if (s.contains("://")) {
                s = s.substringAfter("://").substringAfter('/')
                    .removePrefix("watch/").substringBefore('?')
                s = URLDecoder.decode(s, "UTF-8")
            }
        } catch (e: Exception) { }
        return cleanId(s)
    }

    private fun idFrom(obj: JSONObject): String =
        cleanId(obj.optString("path", ""))
            .ifEmpty { cleanId(obj.optString("doc_ref", "")) }
            .ifEmpty { cleanId(obj.optString("anime_id", obj.optString("objectID"))) }

    private fun getAlgoliaHeaders(): Map<String, String> = mapOf(
        "X-Algolia-Application-Id" to algoliaAppId,
        "X-Algolia-API-Key" to algoliaApiKey,
        "User-Agent" to "Algolia for Android (3.27.0); Android (14)",
        "Content-Type" to "application/json; charset=UTF-8"
    )

    private suspend fun login(email: String, password: String): String? {
        return try {
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .put("returnSecureToken", true)

            val res = app.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_API_KEY",
                requestBody = body.toString().toRequestBody("application/json".toMediaType())
            ).text

            val j = JSONObject(res)
            val newId = j.optString("idToken", "")
            if (newId.isNotEmpty()) {
                idToken = newId
                refreshToken = j.optString("refreshToken", "")
                newId
            } else null
        } catch (e: Exception) { null }
    }

    private suspend fun ensureToken(): String? {
        idToken?.let { return it }

        refreshToken?.let { rt ->
            try {
                val res = app.post(
                    "https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY",
                    requestBody = "grant_type=refresh_token&refresh_token=$rt"
                        .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                ).text

                val j = JSONObject(res)
                val newId = j.optString("id_token", "")
                if (newId.isNotEmpty()) {
                    idToken = newId
                    j.optString("refresh_token", "").let {
                        if (it.isNotEmpty()) refreshToken = it
                    }
                    return newId
                }
            } catch (e: Exception) { }
        }

        if (USER_EMAIL.isNotBlank() && USER_PASSWORD.isNotBlank()) {
            login(USER_EMAIL, USER_PASSWORD)?.let { return it }
        }

        if (genEmail.isNotBlank() && genPassword.isNotBlank()) {
            login(genEmail, genPassword)?.let { return it }
        }

        return try {
            val email = "aw${(100000..999999).random()}${System.currentTimeMillis() % 100000}@example.com"
            val pass = UUID.randomUUID().toString().replace("-", "").take(14) + "A1!"

            val body = JSONObject()
                .put("email", email)
                .put("password", pass)
                .put("returnSecureToken", true)

            val res = app.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_API_KEY",
                requestBody = body.toString().toRequestBody("application/json".toMediaType())
            ).text

            val j = JSONObject(res)
            val newId = j.optString("idToken", "")
            if (newId.isNotEmpty()) {
                genEmail = email
                genPassword = pass
                idToken = newId
                refreshToken = j.optString("refreshToken", "")
                newId
            } else null
        } catch (e: Exception) { null }
    }

    private suspend fun fsGet(url: String): Pair<Int, String> {
        val token = ensureToken()
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        val res = app.get(url, headers = headers)

        if (res.text.contains("\"error\"")) {
            idToken = null
            val t2 = ensureToken()
            val res2 = app.get(
                url,
                headers = if (t2 != null) mapOf("Authorization" to "Bearer $t2") else emptyMap()
            )
            return Pair(res2.code, res2.text)
        }
        return Pair(res.code, res.text)
    }

    private suspend fun firestoreGet(url: String): String = fsGet(url).second

    /**
     * Enhanced Algolia fetch with Arabic description support
     */
    private suspend fun fetchAlgoliaList(
        indexName: String,
        query: String,
        facetFilters: String = "",
        excludeUnreleased: Boolean = false,
        sortBy: String = ""
    ): List<SearchResponse> = withContext(Dispatchers.IO) {
        // Include "story" for Arabic description
        val attributes = enc("""["objectID","name","tags","poster_uri","order","path","doc_ref","type","poster","details","cover_uri","dubbed","anime_id","image","poster_url","thumb_uri","cover","story"]""")

        var params = "attributesToRetrieve=$attributes&hitsPerPage=25&page=0&query=" +
            URLEncoder.encode(query, "UTF-8")

        if (facetFilters.isNotEmpty()) {
            params += "&facetFilters=" + URLEncoder.encode(facetFilters, "UTF-8")
        }
        if (sortBy.isNotEmpty()) {
            params += "&sortBy=" + URLEncoder.encode(sortBy, "UTF-8")
        }

        val body = JSONObject().put("params", params)
            .toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = try {
            app.post(algoliaUrl(indexName), requestBody = body, headers = getAlgoliaHeaders()).text
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        val hits = (try { JSONObject(res) } catch (e: Exception) { JSONObject() })
            .optJSONArray("hits") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue

            if (excludeUnreleased) {
                val st = obj.optJSONObject("details")?.optString("state") ?: ""
                if (st.contains("لم يتم بثه")) continue
            }

            val animeId = sanitizeId(idFrom(obj))
            val url = "$mainUrl/watch/${enc(animeId)}?data=" +
                URLEncoder.encode(obj.toString(), "utf-8")

            list.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = posterFrom(obj)
            })
        }
        return@withContext list
    }

    /**
     * Fetch recent episodes
     */
    private suspend fun fetchRecentEpisodes(): List<SearchResponse> = withContext(Dispatchers.IO) {
        val attributes = enc("""["objectID","name","poster_uri","thumb_uri","episode_name","doc_ref","anime_id"]""")
        val params = "attributesToRetrieve=$attributes&hitsPerPage=25&page=0&query="

        val body = JSONObject().put("params", params)
            .toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = try {
            app.post(
                algoliaUrl("recent_episodes"),
                requestBody = body,
                headers = getAlgoliaHeaders()
            ).text
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        val hits = (try { JSONObject(res) } catch (e: Exception) { JSONObject() })
            .optJSONArray("hits") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue

            val animeId = sanitizeId(
                obj.optString("doc_ref", obj.optString("anime_id", obj.optString("objectID")))
            )
            val url = "$mainUrl/watch/${enc(animeId)}?data=" +
                URLEncoder.encode(obj.toString(), "utf-8")

            list.add(newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = obj.optString("poster_uri")
                    .ifEmpty { obj.optString("thumb_uri") }
            })
        }
        return@withContext list
    }

    /**
     * Get anime data from Firestore
     */
    private suspend fun getAnimeFromFirestore(animeId: String): JSONObject? {
        return try {
            val url = firestoreDocUrl("anime_list/$animeId")
            val text = firestoreGet(url)
            val j = JSONObject(text)

            // Convert Firestore format to simple JSON
            val fields = j.optJSONObject("fields") ?: return null
            val result = JSONObject()

            fields.keys().forEach { key ->
                val value = fields.optJSONObject(key)
                when {
                    value?.has("stringValue") == true -> result.put(key, value.optString("stringValue"))
                    value?.has("integerValue") == true -> result.put(key, value.optLong("integerValue"))
                    value?.has("doubleValue") == true -> result.put(key, value.optDouble("doubleValue"))
                    value?.has("booleanValue") == true -> result.put(key, value.optBoolean("booleanValue"))
                }
            }

            result
        } catch (e: Exception) {
            ZenCore.logError("getAnimeFromFirestore", e)
            null
        }
    }

    /**
     * Arabic description with fallback chain
     */
    private fun getArabicDescription(obj: JSONObject): String? {
        return obj.optString("story").ifBlank {
            obj.optString("description").ifBlank {
                obj.optString("plot").ifBlank {
                    obj.optJSONObject("details")?.optString("story").orEmpty().ifBlank {
                        obj.optJSONObject("details")?.optString("description").orEmpty().ifBlank {
                            obj.optJSONObject("details")?.optString("plot").orEmpty().ifBlank {
                                null
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Get show status
     */
    private fun getShowStatus(animeData: JSONObject): ShowStatus? {
        val state = animeData.optJSONObject("details")?.optString("state") ?: return null
        return when {
            state.contains("مكتمل") || state.contains("completed") -> ShowStatus.Completed
            state.contains("يتم عرضه") || state.contains("ongoing") || state.contains("airing") -> ShowStatus.Ongoing
            else -> null
        }
    }

    /**
     * Get episodes with caching
     */
    private suspend fun getEpisodes(animeData: JSONObject): List<EpisodeInfo> {
        val animeId = sanitizeId(idFrom(animeData))

        // Check cache
        episodesCache[animeId]?.let { cached ->
            episodeCacheTimestamps[animeId]?.let { ts ->
                if (System.currentTimeMillis() - ts < EPISODE_CACHE_TTL_MS) {
                    ZenCore.log("📦 Episodes cache hit for $animeId")
                    return cached
                }
            }
        }

        val episodes = mutableListOf<EpisodeInfo>()

        // Try to get episodes from Firestore
        try {
            val url = firestoreDocUrl("anime_list/$animeId")
            val text = firestoreGet(url)
            val j = JSONObject(text)
            val fields = j.optJSONObject("fields")

            // Look for episodes array
            val episodesField = fields?.optJSONObject("episodes")
            if (episodesField?.has("arrayValue") == true) {
                val arrayValue = episodesField.optJSONObject("arrayValue")
                val values = arrayValue?.optJSONArray("values")

                if (values != null) {
                    for (i in 0 until values.length()) {
                        val ep = values.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields")
                        if (ep != null) {
                            val number = ep.optJSONObject("number")?.optLong("integerValue")?.toInt() ?: (i + 1)
                            val name = ep.optJSONObject("name")?.optString("stringValue")
                            val imageUrl = ep.optJSONObject("image")?.optString("stringValue")

                            episodes.add(EpisodeInfo(
                                id = "$animeId-ep-$number",
                                name = name ?: "الحلقة $number",
                                number = number,
                                imageUrl = imageUrl
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ZenCore.logError("getEpisodes", e)
        }

        // If no episodes found, generate placeholder
        if (episodes.isEmpty()) {
            val epCount = animeData.optJSONObject("details")
                ?.optString("episodes")?.toIntOrNull() ?: 12
            for (i in 1..epCount) {
                episodes.add(EpisodeInfo(
                    id = "$animeId-ep-$i",
                    name = "الحلقة $i",
                    number = i
                ))
            }
        }

        // Cache result
        episodesCache[animeId] = episodes
        episodeCacheTimestamps[animeId] = System.currentTimeMillis()

        return episodes
    }
}
