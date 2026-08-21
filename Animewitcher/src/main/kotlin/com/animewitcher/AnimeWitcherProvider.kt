package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*

class AnimeWitcherProvider : MainAPI() {
    override var mainUrl = "https://animewitcher.com"
    override var name = "AnimeWitcher"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true

    private var algoliaAppId = "D8LH9I7ZL7"
    private var algoliaApiKey = "b56c01ef52540ef334bcdbaa00ded9e4"
    private val FIREBASE_PROJECT_ID = "animewitcher-1c66d"
    private var debugInfo = ""
    private var lastServerRaw = ""

    private val episodesCache = HashMap<String, Pair<Long, List<EpisodeInfo>>>()
    private val EPISODE_CACHE_TTL_MS = 5 * 60 * 1000L

    companion object {
        private const val FIREBASE_API_KEY = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"
        private const val USER_EMAIL = ""; private const val USER_PASSWORD = ""
        private var idToken: String? = null; private var refreshToken: String? = null
        private var genEmail = ""; private var genPassword = ""
    }

    init { MegaProxy.start() }

    data class EpisodeInfo(val id: String, val name: String?, val number: Int, val imageUrl: String? = null)
    data class ServerModel(val name: String?, val link: String?, val quality: String?, val originalLink: String?, val openBrowser: Boolean, val directLink: Boolean = false)

    // ==================== UTILITIES ====================

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun algoliaUrl(index: String) = "https://${algoliaAppId}-dsn.algolia.net/1/indexes/$index/query"
    private fun firestoreDocUrl(path: String) = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$path"
    private fun getQualityAsInt(quality: String?): Int = quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0

    // 🗓️ DYNAMIC SEASON CALCULATOR (Supports Past, Present, and Future!)
    private fun getSeasonFilter(monthOffset: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, monthOffset)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val year = cal.get(java.util.Calendar.YEAR)
        val seasonAr = when (month) {
            12, 1, 2 -> "شتاء"
            3, 4, 5 -> "ربيع"
            6, 7, 8 -> "صيف"
            9, 10, 11 -> "خريف"
            else -> "شتاء"
        }
        return "[\"details.season:$seasonAr عام $year\"]"
    }

    private fun posterFrom(obj: JSONObject): String {
        val p = obj.optJSONObject("poster"); val d = obj.optJSONObject("details")
        var url = obj.optString("poster_uri").ifEmpty { obj.optString("cover_uri") }.ifEmpty { obj.optString("image") }.ifEmpty { obj.optString("poster_url") }.ifEmpty { obj.optString("thumb_uri") }.ifEmpty { obj.optString("cover") }
        if (url.isEmpty()) url = p?.optString("large").orEmpty().ifEmpty { p?.optString("medium").orEmpty() }
        if (url.isEmpty() && p == null) url = obj.optString("poster", "")
        if (url.isEmpty() && d != null) url = d.optString("poster_uri").ifEmpty { d.optString("image") }.ifEmpty { d.optString("poster_url") }
        return url
    }

    private fun cleanId(raw: String): String {
        var s = raw.trim(); var changed = true
        while (changed) { changed = false; if (s.startsWith("/")) { s = s.removePrefix("/"); changed = true }; if (s.startsWith("anime_list/")) { s = s.removePrefix("anime_list/"); changed = true } }
        return s
    }

    private fun sanitizeId(raw: String): String {
        var s = raw.trim(); if (s.isEmpty()) return s
        try { if (s.contains("://")) { s = s.substringAfter("://").substringAfter('/').removePrefix("watch/").substringBefore('?'); s = URLDecoder.decode(s, "UTF-8") } } catch (e: Exception) { }
        return cleanId(s)
    }

    private fun idFrom(obj: JSONObject): String = cleanId(obj.optString("path", "")).ifEmpty { cleanId(obj.optString("doc_ref", "")) }.ifEmpty { cleanId(obj.optString("anime_id", obj.optString("objectID"))) }

    private fun getAlgoliaHeaders(): Map<String, String> = mapOf(
        "X-Algolia-Application-Id" to algoliaAppId,
        "X-Algolia-API-Key" to algoliaApiKey,
        "User-Agent" to "Algolia for Android (3.27.0); Android (14)",
        "Content-Type" to "application/json; charset=UTF-8"
    )

    // ==================== FIREBASE AUTH ====================

    private suspend fun login(email: String, password: String): String? {
        return try {
            val body = JSONObject().put("email", email).put("password", password).put("returnSecureToken", true)
            val res = app.post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_API_KEY", requestBody = body.toString().toRequestBody("application/json".toMediaType())).text
            val j = JSONObject(res); val newId = j.optString("idToken", "")
            if (newId.isNotEmpty()) { idToken = newId; refreshToken = j.optString("refreshToken", ""); newId } else null
        } catch (e: Exception) { null }
    }

    private suspend fun ensureToken(): String? {
        idToken?.let { return it }
        refreshToken?.let { rt ->
            try {
                val res = app.post("https://securetoken.googleapis.com/v1/token?key=$FIREBASE_API_KEY", requestBody = "grant_type=refresh_token&refresh_token=$rt".toRequestBody("application/x-www-form-urlencoded".toMediaType())).text
                val j = JSONObject(res); val newId = j.optString("id_token", "")
                if (newId.isNotEmpty()) { idToken = newId; j.optString("refresh_token", "").let { if (it.isNotEmpty()) refreshToken = it }; return newId }
            } catch (e: Exception) { }
        }
        if (USER_EMAIL.isNotBlank() && USER_PASSWORD.isNotBlank()) login(USER_EMAIL, USER_PASSWORD)?.let { return it }
        if (genEmail.isNotBlank() && genPassword.isNotBlank()) login(genEmail, genPassword)?.let { return it }
        return try {
            val email = "aw${(100000..999999).random()}${System.currentTimeMillis() % 100000}@example.com"
            val pass = UUID.randomUUID().toString().replace("-", "").take(14) + "A1!"
            val body = JSONObject().put("email", email).put("password", pass).put("returnSecureToken", true)
            val res = app.post("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$FIREBASE_API_KEY", requestBody = body.toString().toRequestBody("application/json".toMediaType())).text
            val j = JSONObject(res); val newId = j.optString("idToken", "")
            if (newId.isNotEmpty()) { genEmail = email; genPassword = pass; idToken = newId; refreshToken = j.optString("refreshToken", ""); newId } else null
        } catch (e: Exception) { null }
    }

    private suspend fun fsGet(url: String): Pair<Int, String> {
        val token = ensureToken(); val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        val res = app.get(url, headers = headers)
        if (res.text.contains("\"error\"")) { idToken = null; val t2 = ensureToken(); val res2 = app.get(url, headers = if (t2 != null) mapOf("Authorization" to "Bearer $t2") else emptyMap()); return Pair(res2.code, res2.text) }
        return Pair(res.code, res.text)
    }

    private suspend fun firestoreGet(url: String): String = fsGet(url).second

    // ==================== MAIN PAGE (9 SECTIONS) ====================

    override val mainPage = mainPageOf(
        "recent" to "أحدث الحلقات",
        "most_watched_animations" to "الانميشن الاكثر مشاهدة",
        "prev_season" to "الموسم السابق",
        "current_season" to "الموسم الحالي",
        "next_season" to "الموسم القادم",
        "series_fav_count_desc" to "الأكثر شعبية",
        "best_mal_ranked" to "أفضل التقييمات",
        "movies" to "أفلام الأنمي",
        "ongoing" to "يُعرض الآن"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val recentEps = async { fetchRecentEpisodes() }
        val mostWatched = async { fetchAlgoliaList("most_watched_animations", "") }
        val prevSeason = async { fetchAlgoliaList("series", "", getSeasonFilter(-1)) }
        val currentSeason = async { fetchAlgoliaList("series", "", getSeasonFilter(0)) }
        val nextSeason = async { fetchAlgoliaList("series", "", getSeasonFilter(1)) }
        val popular = async { fetchAlgoliaList("series_fav_count_desc", "") }
        val topRated = async { fetchAlgoliaList("best_mal_ranked", "") }
        val movies = async { fetchAlgoliaList("series", "", "[\"type:فيلم\"]") }
        val ongoing = async { fetchAlgoliaList("series", "", "[\"details.state:مستمر\"]") }

        return@withContext newHomePageResponse(listOf(
            HomePageList("أحدث الحلقات", recentEps.await(), isHorizontalImages = true),
            HomePageList("الانميشن الاكثر مشاهدة", mostWatched.await()),
            HomePageList("الموسم السابق", prevSeason.await()),
            HomePageList("الموسم الحالي", currentSeason.await()),
            HomePageList("الموسم القادم", nextSeason.await()),
            HomePageList("الأكثر شعبية", popular.await()),
            HomePageList("أفضل التقييمات", topRated.await()),
            HomePageList("أفلام الأنمي", movies.await()),
            HomePageList("يُعرض الآن", ongoing.await())
        ), hasNext = false)
    }

    // ==================== ALGOLIA FETCHING ====================

    private suspend fun fetchAlgoliaList(indexName: String, query: String, facetFilters: String = "", excludeUnreleased: Boolean = false): List<SearchResponse> = withContext(Dispatchers.IO) {
        val attributes = enc("[\"objectID\",\"name\",\"tags\",\"poster_uri\",\"order\",\"path\",\"doc_ref\",\"type\",\"poster\",\"details\",\"cover_uri\",\"dubbed\",\"anime_id\",\"image\",\"poster_url\",\"thumb_uri\",\"cover\",\"story\",\"aniList_poster\"]")
        var params = "attributesToRetrieve=$attributes&hitsPerPage=25&page=0&query=" + URLEncoder.encode(query, "UTF-8")
        if (facetFilters.isNotEmpty()) params += "&facetFilters=" + URLEncoder.encode(facetFilters, "UTF-8")
        val body = JSONObject().put("params", params).toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val res = try { app.post(algoliaUrl(indexName), requestBody = body, headers = getAlgoliaHeaders()).text } catch (e: Exception) { return@withContext emptyList() }
        val hits = (try { JSONObject(res) } catch (e: Exception) { JSONObject() }).optJSONArray("hits") ?: JSONArray()
        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i); val title = obj.optString("name"); if (title.isNullOrEmpty()) continue
            if (excludeUnreleased) { val st = obj.optJSONObject("details")?.optString("state") ?: ""; if (st.contains("لم يتم بثه")) continue }
            val animeId = sanitizeId(idFrom(obj)); val url = "$mainUrl/watch/${enc(animeId)}?data=" + URLEncoder.encode(obj.toString(), "utf-8")
            list.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = posterFrom(obj) })
        }
        return@withContext list
    }

    private suspend fun fetchRecentEpisodes(): List<SearchResponse> = withContext(Dispatchers.IO) {
        val attributes = enc("[\"objectID\",\"name\",\"poster_uri\",\"thumb_uri\",\"episode_name\",\"doc_ref\",\"anime_id\",\"story\",\"details\",\"tags\",\"type\"]")
        val params = "attributesToRetrieve=$attributes&hitsPerPage=25&page=0&query="
        val body = JSONObject().put("params", params).toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        val res = try { app.post(algoliaUrl("recent"), requestBody = body, headers = getAlgoliaHeaders()).text } catch (e: Exception) { return@withContext emptyList() }
        val hits = (try { JSONObject(res) } catch (e: Exception) { JSONObject() }).optJSONArray("hits") ?: JSONArray()
        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i); val title = obj.optString("name"); val epName = obj.optString("episode_name"); if (title.isNullOrEmpty()) continue
            val displayTitle = if (epName.isNotEmpty()) "$title • $epName" else title
            val animeId = sanitizeId(obj.optString("anime_id").ifEmpty { obj.optString("doc_ref").substringAfter("anime_list/") })
            val url = "$mainUrl/watch/${enc(animeId)}?data=" + URLEncoder.encode(obj.toString(), "utf-8"); val poster = obj.optString("thumb_uri").ifEmpty { obj.optString("poster_uri") }
            list.add(newAnimeSearchResponse(displayTitle, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext list
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) { fetchAlgoliaList("series", query) }

    // ==================== LOAD ANIME ====================

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val animeJson = try { JSONObject(URLDecoder.decode(url.substringAfter("?data=", ""), "utf-8")) } catch (e: Exception) { JSONObject() }
        var animeId = sanitizeId(idFrom(animeJson)); if (animeId.isEmpty()) animeId = sanitizeId(URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "utf-8"))
        
        // 🚀 SMART METADATA FILL
        if (animeJson.optString("story").isEmpty() && animeId.isNotEmpty()) {
            try {
                val fullAttributes = enc("[\"objectID\",\"name\",\"tags\",\"poster_uri\",\"order\",\"path\",\"doc_ref\",\"type\",\"poster\",\"details\",\"cover_uri\",\"dubbed\",\"anime_id\",\"image\",\"poster_url\",\"thumb_uri\",\"cover\",\"story\",\"aniList_poster\"]")
                val filterQuery = enc("path:\"anime_list/$animeId\" OR objectID:\"$animeId\"")
                val params = "attributesToRetrieve=$fullAttributes&hitsPerPage=1&query=&filters=$filterQuery"
                val fullAnimeRes = app.post(
                    algoliaUrl("series"),
                    requestBody = JSONObject().put("params", params).toString().toRequestBody("application/json; charset=UTF-8".toMediaType()),
                    headers = getAlgoliaHeaders()
                ).text
                val fullHits = JSONObject(fullAnimeRes).optJSONArray("hits")
                if (fullHits != null && fullHits.length() > 0) {
                    val fullObj = fullHits.getJSONObject(0)
                    val keys = fullObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        animeJson.put(k, fullObj.opt(k))
                    }
                }
            } catch (_: Exception) {}
        }

        val details = animeJson.optJSONObject("details") ?: JSONObject()
        val isUnreleased = (details.optString("state") ?: "").contains("لم يتم بثه")
        var episodes: List<EpisodeInfo> = emptyList()
        if (!isUnreleased) {
            val cached = episodesCache[animeId]
            if (cached != null && System.currentTimeMillis() - cached.first < EPISODE_CACHE_TTL_MS) {
                episodes = cached.second
            } else {
                episodes = fetchEpisodes(animeId)
                if (episodes.isNotEmpty()) episodesCache[animeId] = System.currentTimeMillis() to episodes
            }
            if (episodes.isEmpty()) { val count = Regex("\\d+").find(details.optString("eps_num", ""))?.value?.toIntOrNull() ?: 0; if (count in 1..2000) episodes = (1..count).map { n -> EpisodeInfo(if (count <= 999) String.format("%03d", n) else n.toString(), null, n, null) } }
            if (episodes.isEmpty()) episodes = listOf(EpisodeInfo("000", "⚠ DEBUG: $debugInfo", 0, null))
        }
        val epList = episodes.map { info -> newEpisode(data = "$animeId|${info.id}") { this.name = info.name ?: "الحلقة ${info.number}"; this.episode = info.number } }
        val tagsArray = animeJson.optJSONArray("tags")
        val tags = if (tagsArray != null) (0 until tagsArray.length()).map { tagsArray.getString(it) } else emptyList()
        val plot = animeJson.optString("story").ifEmpty { animeJson.optString("description").ifEmpty { animeJson.optJSONObject("details")?.optString("story").orEmpty() } }

        return@withContext newAnimeLoadResponse(animeJson.optString("name", animeId), url, TvType.Anime) {
            this.posterUrl = posterFrom(animeJson)
            this.year = details.optString("year").toIntOrNull()
            this.plot = plot
            this.showStatus = if (details.optString("state") == "مكتمل") ShowStatus.Completed else ShowStatus.Ongoing
            this.tags = tags
            addEpisodes(DubStatus.Subbed, epList)
        }
    }

    // ==================== FETCH EPISODES FROM FIRESTORE ====================

    private suspend fun fetchEpisodes(animeId: String): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val a = enc(animeId); val list = ArrayList<EpisodeInfo>(); var raw = ""
        try { raw = firestoreGet(firestoreDocUrl("anime_list/$a/episodes_summery/summery")); parseEpisodesDoc(raw, list); if (list.isEmpty()) { raw = firestoreGet(firestoreDocUrl("anime_list/$a")); parseEpisodesDoc(raw, list) }; list.sortBy { it.number } } catch (e: Exception) { debugInfo = "fetchEpisodes exc: ${e.message}" }
        return@withContext list
    }

    private fun parseEpisodesDoc(raw: String, list: ArrayList<EpisodeInfo>) {
        val json = try { JSONObject(raw) } catch (e: Exception) { return }; val fields = json.optJSONObject("fields") ?: return
        var keys = fields.keys(); while (keys.hasNext()) { val v = fields.optJSONObject(keys.next()) ?: continue; if (v.has("arrayValue")) { addFromArray(v, list); if (list.isNotEmpty()) return } }
        keys = fields.keys(); while (keys.hasNext()) { val v = fields.optJSONObject(keys.next()) ?: continue; if (v.has("mapValue")) { addFromNumericMap(v, list); if (list.isNotEmpty()) return } }
        addFromNumericMap(fields, list); if (list.isNotEmpty()) return
        keys = fields.keys(); while (keys.hasNext()) { val v = fields.optJSONObject(keys.next()) ?: continue; val count = if (v.has("integerValue")) v.optString("integerValue").toIntOrNull() else null; if (count != null && count in 1..2000) { for (n in 1..count) list.add(EpisodeInfo(if (count <= 999) String.format("%03d", n) else n.toString(), null, n, null)); return } }
    }

    private fun addFromArray(container: JSONObject, list: ArrayList<EpisodeInfo>) {
        val values = container.optJSONObject("arrayValue")?.optJSONArray("values") ?: return
        for (i in 0 until values.length()) {
            val v = values.optJSONObject(i) ?: continue; val s = v.optString("stringValue", ""); if (s.isNotEmpty()) { list.add(EpisodeInfo(s, null, s.toIntOrNull() ?: (list.size + 1), null)); continue }
            val m = v.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
            val id = m.optJSONObject("id")?.optString("stringValue") ?: m.optJSONObject("episode_id")?.optString("stringValue") ?: m.optJSONObject("doc_id")?.optString("stringValue") ?: (i + 1).toString()
            val nm = m.optJSONObject("name")?.optString("stringValue") ?: m.optJSONObject("episode_name")?.optString("stringValue") ?: m.optJSONObject("title")?.optString("stringValue")
            val number = m.optJSONObject("number")?.optString("integerValue")?.toIntOrNull() ?: m.optJSONObject("episode")?.optString("integerValue")?.toIntOrNull() ?: id.toIntOrNull() ?: (i + 1)
            val image = m.optJSONObject("image")?.optString("stringValue") ?: m.optJSONObject("thumb_uri")?.optString("stringValue") ?: m.optJSONObject("poster_uri")?.optString("stringValue")
            list.add(EpisodeInfo(id, nm, number, image))
        }
    }

    private fun addFromNumericMap(container: JSONObject, list: ArrayList<EpisodeInfo>) {
        val map = container.optJSONObject("mapValue")?.optJSONObject("fields") ?: container; val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next(); if (!key.matches(Regex("^\\d+$"))) continue; val inner = map.optJSONObject(key) ?: continue; val m = inner.optJSONObject("mapValue")?.optJSONObject("fields")
            if (m != null) list.add(EpisodeInfo(key, m.optJSONObject("name")?.optString("stringValue") ?: m.optJSONObject("episode_name")?.optString("stringValue"), key.toIntOrNull() ?: (list.size + 1), m.optJSONObject("image")?.optString("stringValue") ?: m.optJSONObject("thumb_uri")?.optString("stringValue")))
            else list.add(EpisodeInfo(key, if (inner.has("stringValue")) inner.optString("stringValue") else null, key.toIntOrNull() ?: (list.size + 1), null))
        }
    }

    // ==================== FETCH SERVERS FROM FIRESTORE ====================

    private suspend fun fetchServersForEpisode(animeId: String, episodeId: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val a = enc(animeId); val candidates = LinkedHashSet<String>(); candidates.add(episodeId)
        episodeId.toIntOrNull()?.let { n -> candidates.add(String.format("%03d", n)); candidates.add(n.toString()) }
        for (e in candidates) { val r = fetchServersAt(a, e); if (r.isNotEmpty()) return@withContext r }
        return@withContext emptyList()
    }

    private suspend fun fetchServersAt(a: String, e: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val jobS2 = async {
            try {
                val r1 = fsGet(firestoreDocUrl("anime_list/$a/episodes/$e/servers2/all_servers")); val json = JSONObject(r1.second); val fields = json.optJSONObject("fields"); val serversField = fields?.optJSONObject("servers")
                if (serversField != null) { val arr = serversField.optJSONObject("arrayValue")?.optJSONArray("values"); if (arr != null) { val temp = ArrayList<ServerModel>(); for (i in 0 until arr.length()) { val map = arr.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue; val name = map.optJSONObject("name")?.optString("stringValue"); val link = map.optJSONObject("link")?.optString("stringValue"); val quality = map.optJSONObject("quality")?.optString("stringValue"); val originalLink = map.optJSONObject("original_link")?.optString("stringValue"); val openBrowser = map.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false; val visible = map.optJSONObject("visible")?.optBoolean("booleanValue") ?: true; val directLink = map.optJSONObject("direct_link")?.optBoolean("booleanValue") ?: false; if (!name.isNullOrEmpty() && !link.isNullOrEmpty() && visible) temp.add(ServerModel(name, link, quality, originalLink, openBrowser, directLink)) }; temp } else emptyList() } else emptyList()
            } catch (e2: Exception) { emptyList() }
        }
        val jobList = async {
            try {
                val r2 = fsGet(firestoreDocUrl("anime_list/$a/episodes/$e/servers?pageSize=50")); val json = JSONObject(r2.second); val docs = json.optJSONArray("documents") ?: JSONArray(); val temp = ArrayList<ServerModel>()
                for (i in 0 until docs.length()) { val fields = docs.getJSONObject(i).optJSONObject("fields") ?: continue; val name = fields.optJSONObject("name")?.optString("stringValue"); val link = fields.optJSONObject("link")?.optString("stringValue"); val visible = fields.optJSONObject("visible")?.optBoolean("booleanValue") ?: true; val quality = fields.optJSONObject("quality")?.optString("stringValue"); val originalLink = fields.optJSONObject("original_link")?.optString("stringValue"); val openBrowser = fields.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false; val directLink = fields.optJSONObject("direct_link")?.optBoolean("booleanValue") ?: false; if (!name.isNullOrEmpty() && !link.isNullOrEmpty() && visible) temp.add(ServerModel(name, link, quality, originalLink, openBrowser, directLink)) }
                temp
            } catch (e2: Exception) { emptyList() }
        }
        val resS2 = jobS2.await(); val resList = jobList.await()
        if (resS2.isNotEmpty()) return@withContext resS2.sortedByDescending { getQualityAsInt(it.quality) }
        if (resList.isNotEmpty()) return@withContext resList.sortedByDescending { getQualityAsInt(it.quality) }
        return@withContext emptyList()
    }

    // ==================== LOAD LINKS (PARALLEL EXTRACTION) ====================

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split('|'); if (parts.size < 2) return@withContext false
        val animeId = sanitizeId(parts[0]); val episodeId = parts[1].trim(); if (episodeId == "000") return@withContext false
        val servers = fetchServersForEpisode(animeId, episodeId); if (servers.isEmpty()) throw ErrorLoadingException("NO SERVERS FOUND :: $lastServerRaw")

        val allLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())

        coroutineScope {
            val jobs = servers.map { server ->
                launch(Dispatchers.IO) {
                    try { extractFromServer(server, subtitleCallback) { link -> allLinks.add(link) } } catch (_: Exception) {}
                }
            }
            withTimeoutOrNull(20000L) { jobs.joinAll() }
        }

        val deduplicated = allLinks.distinctBy { link ->
            if (link.url.contains(".m3u8") || link.url.contains(".mp4")) link.url.substringBefore("?").substringBefore("#") else link.url
        }.sortedByDescending { it.quality }

        deduplicated.forEach(callback)
        if (deduplicated.isEmpty()) throw ErrorLoadingException("No working streams extracted.")
        return@withContext true
    }

    // ==================== EXTRACT FROM SERVER ====================

    private suspend fun extractFromServer(server: ServerModel, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val rawServerName = server.name ?: "Server"
        val serverName = rawServerName.replace(Regex("""\b\d{3,4}p\b|\b4K\b|\b2160p\b|\bFHD\b|\bHD\b|\bSD\b""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s{2,}"""), " ").trim().ifEmpty { "Server" }
        val link = server.link ?: return; val host = try { URL(link).host.lowercase() } catch (e: Exception) { "" }
        val upperName = serverName.uppercase(Locale.getDefault()); val fixedLink = link.replace("filemoon.link", "filemoon.sx").replace("voe.sx", "voe.unblockit.cat")
        val finalName = serverName
        try {
            when {
                host.contains("pixeldrain") -> {
                    val id = fixedLink.substringAfterLast("/u/", fixedLink.substringAfterLast("/api/file/")).substringAfterLast("/file/")
                    if (id.isNotBlank() && !id.contains("/")) {
                        callback.invoke(newExtractorLink(source = name, name = "PD", url = "https://pixeldrain.com/api/file/$id") { referer = mainUrl; quality = getQualityFromName(server.quality) })
                        callback.invoke(newExtractorLink(source = name, name = "PD Fast", url = "https://cdn.pixeldrain.eu.cc/$id") { referer = mainUrl; quality = getQualityFromName(server.quality) })
                    }
                }
                host.contains("photos.app.goo.gl") || host.contains("photos.google.com") || upperName.startsWith("GF") -> {
                    try {
                        val response = app.get(fixedLink, headers = mapOf("User-Agent" to "Mozilla/5.0")); val html = response.text
                        val streamMatch = Regex(""""(https://video-downloads\.googleusercontent\.com/[^"]+)"""").find(html)
                        if (streamMatch != null) { callback.invoke(newExtractorLink(source = name, name = serverName, url = streamMatch.groupValues[1].replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/")) { referer = fixedLink; quality = getQualityFromName(server.quality) }); return }
                        val matches = Regex("""(https?://[^"'\s\\]+(?:googlevideo\.com|googleusercontent\.com)[^"'\s\\]+)""").findAll(html).map { it.groupValues[1].replace("\\u003d", "=").replace("\\u0026", "&").replace("\\/", "/").replace("\\", "") }.distinct().toList()
                        for (u in matches) { if (u.contains("lh3.") || u.contains(".jpg")) continue; callback.invoke(newExtractorLink(source = name, name = serverName, url = u) { referer = fixedLink; quality = getQualityFromName(server.quality) }); return }
                        callback.invoke(newExtractorLink(source = name, name = serverName, url = fixedLink) { referer = mainUrl; quality = getQualityFromName(server.quality) })
                    } catch (e: Exception) { callback.invoke(newExtractorLink(source = name, name = serverName, url = fixedLink) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
                }
                host.contains("krakenfiles") || upperName.startsWith("KF") -> {
                    var direct: String? = null
                    try { val id = Regex("/(?:view|embed-video)/([0-9a-zA-Z]+)").find(fixedLink)?.groupValues?.get(1); if (!id.isNullOrEmpty()) { val source = app.get("https://krakenfiles.com/embed-video/$id", headers = mapOf("Referer" to "https://krakenfiles.com/view/$id.html")).document.selectFirst("source")?.attr("src"); if (!source.isNullOrEmpty()) direct = if (source.startsWith("//")) "https:$source" else source } } catch (e: Exception) { }
                    if (direct != null) { callback.invoke(newExtractorLink(source = name, name = serverName, url = direct, type = ExtractorLinkType.VIDEO) { this.referer = "https://krakenfiles.com/"; this.quality = getQualityFromName(server.quality) }) }
                    else { var ok = false; try { ok = withTimeoutOrNull(15000L) { loadExtractor(fixedLink, mainUrl, subtitleCallback, callback) } ?: false } catch (e: Exception) {}; if (!ok) callback.invoke(newExtractorLink(source = name, name = serverName, url = fixedLink) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
                }
                host.contains("mega.nz") || host.contains("mega.co.nz") || upperName.startsWith("MG") -> {
                    val proxyUrl = MegaProxy.resolve(fixedLink)
                    if (proxyUrl != null) { callback.invoke(newExtractorLink(source = name, name = serverName, url = proxyUrl) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
                    else { callback.invoke(newExtractorLink(source = name, name = serverName, url = fixedLink) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
                }
                host.contains("streamtape") || host.contains("stape.") || host.contains("shavetape") || host.contains("watchadsontape") -> {
                    var ok = false; try { ok = withTimeoutOrNull(15000L) { loadExtractor(fixedLink, mainUrl, subtitleCallback, callback) } ?: false } catch (e: Exception) { }
                    if (ok) return
                    extractStreamTape(fixedLink)?.let { st -> callback.invoke(newExtractorLink(source = name, name = "$serverName ST", url = st) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
                }
                host.contains("mediafire") -> {
                    var ok = false; try { ok = withTimeoutOrNull(15000L) { loadExtractor(fixedLink, mainUrl, subtitleCallback, callback) } ?: false } catch (e: Exception) { }
                    if (ok) return
                    extractMediaFire(fixedLink)?.let { mf -> callback.invoke(newExtractorLink(source = name, name = "$serverName MF", url = mf) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
                }
                host.contains("vidtube.one") || upperName == "VT" -> {
                    val extracted = VidTubeExtractor.extract(fixedLink, finalName, getQualityFromName(server.quality), callback)
                    if (!extracted) callback.invoke(newExtractorLink(source = name, name = finalName, url = fixedLink) { referer = mainUrl; this.quality = getQualityFromName(server.quality) })
                }
                host.contains("megaplay") || host.contains("vidwish") || host.contains("vidtube.site") -> {
                    var ok = false; try { ok = withTimeoutOrNull(15000L) { loadExtractor(fixedLink, mainUrl, subtitleCallback, callback) } ?: false } catch (e: Exception) {}
                    if (!ok) { try { MegaPlay.extractMegaPlayUrl(fixedLink, mainUrl, "https://${host}", finalName, subtitleCallback, callback); ok = true } catch (_: Exception) {} }
                    if (!ok) callback.invoke(newExtractorLink(source = name, name = finalName, url = fixedLink) { referer = mainUrl; quality = getQualityFromName(server.quality) })
                }
                else -> {
                    var ok = false
                    if (ZenHeavyweightExtractors.tryExtract(host, fixedLink, mainUrl, finalName, getQualityFromName(server.quality), callback)) return
                    try { ok = withTimeoutOrNull(15000L) { loadExtractor(fixedLink, mainUrl, subtitleCallback, callback) } ?: false } catch (e: Exception) {}
                    if (!ok) ok = ZenUniversalSniffer.deepScan(fixedLink, finalName, getQualityFromName(server.quality), callback)
                    
                    // 🚑 EMERGENCY RESCUE
                    if (!ok) {
                        ZenProxyRescue.rescue(fixedLink, finalName, getQualityFromName(server.quality), mainUrl, callback)
                    }
                }
            }
        } catch (e: Exception) { callback.invoke(newExtractorLink(source = name, name = serverName, url = link) { referer = mainUrl; quality = getQualityFromName(server.quality) }) }
    }

    // ==================== STREAMTAPE & MEDIAFIRE HELPERS ====================

    private suspend fun extractStreamTape(url: String): String? {
        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:151.0) Gecko/20100101 Firefox/151.0", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language" to "en-US,en;q=0.5", "Sec-Fetch-Dest" to "document", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "none", "Sec-Fetch-User" to "?1")
            val html = app.get(url, headers = headers).text
            val patterns = listOf(Regex("""id=["']norobotlink["'][^>]*>([^<]+)<"""), Regex("""getElementById\(['"]norobotlink['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]"""), Regex("""(https?://[^"'\s]*?/get_video\?[^"'\s]+)"""))
            for (regex in patterns) { val match = regex.find(html); if (match != null) { var link = match.groupValues[1].trim(); if (link.startsWith("//")) link = "https:$link" else if (!link.startsWith("http")) link = "https://streamtape.com$link"; if (!link.contains("&stream=1")) link += "&stream=1"; return link } }
            null
        } catch (e: Exception) { null }
    }

    private suspend fun extractMediaFire(url: String): String? {
        return try {
            val quickKey = Regex("""/file(?:_premium)?/([a-zA-Z0-9]+)/""").find(url)?.groupValues?.get(1)
            val pageUrl = if (quickKey != null) "https://www.mediafire.com/file/$quickKey/" else url
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            val doc = app.get(pageUrl, headers = headers, referer = "https://www.mediafire.com/").document
            val dlLink = doc.selectFirst("a#downloadButton")?.attr("href") ?: doc.selectFirst("a[aria-labelledby=\"downloadButton\"]")?.attr("href") ?: doc.selectFirst("a.download_link")?.attr("href")
            if (!dlLink.isNullOrBlank() && dlLink.startsWith("http")) return dlLink
            val html = doc.outerHtml(); val regexLink = Regex("""href="(https://download[^"]+)"""").find(html)?.groupValues?.get(1) ?: Regex("""(https://[a-zA-Z0-9\-]+\.mediafire\.com/[^"'\s]+)""").find(html)?.groupValues?.get(1)
            if (regexLink != null && regexLink.startsWith("http")) return regexLink; null
        } catch (e: Exception) { null }
    }

    // ==================== QUALITY HELPER ====================

    private fun getQualityFromName(quality: String?): Int {
        return when {
            quality == null -> Qualities.Unknown.value
            quality.contains("4K") || quality.contains("2160") -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
