package com.animerift

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AnimeRift : MainAPI() {

    override var mainUrl = "https://gateway.anime-rift.com"
    override var name = "AnimeRift"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val JWT_SECRET = "RIFT_JWT_SECRET_PLACEHOLDER"
    private val FIREBASE_API_KEY = "AIzaSyBiLkiGEm7ruugny3tDFHEZvqli8yv1k7I"
    private val FIREBASE_APP_ID = "RIFT_FIREBASE_APP_ID_PLACEHOLDER"
    private val ANDROID_PACKAGE = "com.riftapps.animerift"
    private val ANDROID_CERT = "AF40CE82A52AA4107F311D8B9727D01C8D02250B"

    private data class RLink(
        val serverName: String,
        val subTitle: String,
        val quality: String,
        val url: String,
        val headers: Map<String, String>
    )

    private data class RBundle(
        val links: List<RLink>,
        val subtitles: List<SubtitleFile>,
        val createdAt: Long
    )

    private data class RawResp(val code: Int, val text: String, val retryAfter: Long?)

    companion object {
        private const val TAG = "AnimeRift"
        private const val ID_REQ_BUDGET = 600
        private const val ID_TTL_MS = 36L * 60 * 60 * 1000
        private const val PREFETCH_TTL_MS = 5L * 60 * 1000

        private var gatewayBaseUrl: String? = null
        private var active: Idx? = null
        private var breakerUntil = 0L
        @Volatile private var lastReqAt = 0L
        @Volatile private var vpnNoticeShown = false
        @Volatile private var episodeOrder: List<String> = emptyList()

        private val priorityProviders = listOf("cr2", "rift-streamer", "streamtape")

        private val prefetchScope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineName("AnimeRift-prefetch")
        )
        private val prefetchCache = ConcurrentHashMap<String, RBundle>()
        private val prefetchJobs = ConcurrentHashMap<String, Job>()

        private data class Idx(
            var fid: String = "", var tok: String = "", var dev: String = "", var sess: String = "",
            var build: String = "", var born: Long = 0L, var used: Long = 0L,
            var reqs: Int = 0, var dead: Boolean = false, var info: String = ""
        )

        private fun poolFile(): File =
            File(System.getProperty("java.io.tmpdir") ?: ".", "anime_rift_pool_v2.txt")

        @Synchronized
        private fun loadPool(): MutableList<Idx> {
            val out = mutableListOf<Idx>()
            try {
                val f = poolFile()
                if (!f.exists()) return out
                for (line in f.readLines()) {
                    val p = line.split('\t')
                    if (p.size < 10) continue
                    out.add(Idx(
                        fid = p[0], tok = p[1], dev = p[2], sess = p[3], build = p[4],
                        born = p[5].toLongOrNull() ?: 0L, used = p[6].toLongOrNull() ?: 0L,
                        reqs = p[7].toIntOrNull() ?: 0, dead = p[8] == "1",
                        info = String(Base64.decode(p[9], Base64.NO_WRAP))
                    ))
                }
            } catch (_: Exception) {}
            return out
        }

        @Synchronized
        private fun savePool(pool: List<Idx>) {
            try {
                val now = System.currentTimeMillis()
                val live = pool.filter { it.dead || now - it.born < ID_TTL_MS * 2 }.takeLast(12)
                poolFile().writeText(live.joinToString("\n") {
                    listOf(
                        it.fid, it.tok, it.dev, it.sess, it.build,
                        it.born.toString(), it.used.toString(), it.reqs.toString(),
                        if (it.dead) "1" else "0",
                        Base64.encodeToString(it.info.toByteArray(), Base64.NO_WRAP)
                    ).joinToString("\t")
                })
            } catch (_: Exception) {}
        }

        private fun usable(i: Idx): Boolean {
            val now = System.currentTimeMillis()
            return !i.dead && i.sess.isNotEmpty() && i.reqs < ID_REQ_BUDGET && now - i.born < ID_TTL_MS
        }

        @Synchronized
        private fun pickIdentity(): Idx? = loadPool().filter { usable(it) }.minByOrNull { it.reqs }

        @Synchronized
        private fun retire(i: Idx) {
            i.dead = true
            savePool(loadPool().map { if (it.fid == i.fid) i else it })
            if (active?.fid == i.fid) active = null
            Log.w(TAG, "identity retired: ${i.fid.take(6)}…")
        }

        @Synchronized
        private fun touch(i: Idx) {
            i.used = System.currentTimeMillis(); i.reqs++
            savePool(loadPool().map { if (it.fid == i.fid) i else it })
        }

        private val jsonCache = object : LinkedHashMap<String, Pair<Long, JsonNode>>(200, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, JsonNode>>?) = size > 200
        }

        private fun ttlFor(scope: String): Long = when (scope) {
            "ANIME.LIBRARY.HOME_CONTENT" -> 5 * 60 * 1000L
            "ANIME.LIBRARY.SEARCH" -> 3 * 60 * 1000L
            "ANIME.LIBRARY.DETAILS" -> 10 * 60 * 1000L
            "ANIME.LIBRARY.EPISODES.ALL" -> 10 * 60 * 1000L
            else -> 0L
        }

        private fun cacheKey(method: String, url: String, body: Map<String, Any?>?): String =
            "$method|$url|${body?.entries?.sortedBy { it.key }?.joinToString("&") { "${it.key}=${it.value}" } ?: ""}"

        @Synchronized private fun cacheGet(k: String): JsonNode? {
            val e = jsonCache[k] ?: return null
            if (System.currentTimeMillis() > e.first) { jsonCache.remove(k); return null }
            return e.second
        }

        @Synchronized private fun cachePeek(k: String): JsonNode? = jsonCache[k]?.second

        @Synchronized private fun cachePut(k: String, n: JsonNode, ttl: Long) {
            jsonCache[k] = Pair(System.currentTimeMillis() + ttl, n)
        }

        @Synchronized
        private fun takePrefetch(key: String): RBundle? {
            val b = prefetchCache.remove(key) ?: return null
            if (b.links.isEmpty() || System.currentTimeMillis() - b.createdAt > PREFETCH_TTL_MS) return null
            return b
        }
    }

    private val mapper = ObjectMapper()

    private val httpClient: okhttp3.OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun jsonBody(body: Map<String, Any?>?): okhttp3.RequestBody =
        (if (body == null) "{}" else mapper.writeValueAsString(body))
            .toRequestBody("application/json; charset=UTF-8".toMediaType())

    private suspend fun rawCall(
        url: String, method: String, headers: Map<String, String>, body: Map<String, Any?>?
    ): RawResp = withContext(Dispatchers.IO) {
        val rb = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.addHeader(k, v) }
        when (method.uppercase()) {
            "POST" -> rb.post(jsonBody(body))
            "PUT" -> rb.put(jsonBody(body))
            else -> rb.get()
        }
        httpClient.newCall(rb.build()).execute().use { r ->
            RawResp(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toLongOrNull())
        }
    }

    private fun genFid(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        return (1..22).map { chars.random() }.joinToString("")
    }

    private fun generateFcmToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return "APA91b" + (1..80).map { chars.random() }.joinToString("")
    }

    private fun generateSyntheticDevice(): Pair<String, String> {
        val brands = listOf(
            Triple("google", "Google", "Pixel 8 Pro", "AP2A.240805"),
            Triple("google", "Google", "Pixel 7", "AP2A.240805"),
            Triple("samsung", "samsung", "Galaxy S24", "UP1A.231005"),
            Triple("samsung", "samsung", "Galaxy S23", "UP1A.231005"),
            Triple("xiaomi", "Xiaomi", "Xiaomi 14", "UKQ1.230917"),
            Triple("xiaomi", "Xiaomi", "Redmi Note 13", "UKQ1.230917"),
            Triple("realme", "realme", "realme GT 5", "UKQ1.230924"),
            Triple("oneplus", "OnePlus", "OnePlus 12", "UP1A.231005")
        )
        val s = brands.random()
        val buildId = "${s.fourth}.${Random().nextInt(899999) + 100000}.${(10..99).random()}"
        val tag = UUID.randomUUID().toString().substring(0, 8)
        val model = s.third
        val dev = model.lowercase().replace(" ", "_")
        val info = mapper.writeValueAsString(mapOf(
            "model" to model, "brand" to s.first, "manufacturer" to s.second, "device" to dev,
            "hardware" to "qcom", "product" to dev, "androidVersion" to "14", "sdkInt" to 34,
            "securityPatch" to "2025-01-01", "isPhysicalDevice" to true,
            "supportedAbis" to listOf("arm64-v8a"), "tags" to "release-keys", "type" to "user",
            "buildId" to buildId, "host" to "build-server-$tag",
            "fingerprint" to "${s.first}/$model/${s.first}:14/$buildId/V.1e9a_$tag:user/release-keys"
        ))
        return Pair(buildId, info)
    }

    private fun base64UrlEncode(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun generateIntegrityToken(scope: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 55 + Random().nextInt(20)
        val header = base64UrlEncode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = base64UrlEncode("""{"scope":"$scope","exp":$exp,"iat":$now}""".toByteArray())
        val toSign = "$header.$payload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(JWT_SECRET.toByteArray(), "HmacSHA256"))
        return "$toSign.${base64UrlEncode(mac.doFinal(toSign.toByteArray()))}"
    }

    private fun generateDeviceTimezone(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return "${format.format(Date())}.${Random().nextInt(999999).toString().padStart(6, '0')}"
    }

    private fun gzipCompress(data: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data.toByteArray()) }
        return bos.toByteArray()
    }

    private fun buildHeaders(i: Idx, scope: String): Map<String, String> = mapOf(
        "x-device-os-id" to i.build,
        "user-agent" to "Dart/3.10 (dart:io)",
        "x-device-release-version" to "3.13.5",
        "x-firebase-app-check" to "null",
        "authorization" to "Bearer null",
        "content-type" to "application/json; charset=UTF-8",
        "x-installation-source" to "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER",
        "integrity" to "Bearer ${generateIntegrityToken(scope)}",
        "accept" to "application/json",
        "x-firebase-id" to i.fid,
        "x-device-id" to i.dev,
        "x-device-timezone" to generateDeviceTimezone(),
        "x-device-language" to "ar",
        "x-platform" to "Mobile",
        "x-os" to "android"
    )

    private suspend fun pace(scope: String) {
        val gap = if (scope.startsWith("ANIME.LIBRARY.EPISODES.SOURCES") || scope.startsWith("USER.ADS")) {
            500L + Random().nextLong(1000L)
        } else {
            450L + Random().nextLong(950L)
        }
        val since = System.currentTimeMillis() - lastReqAt
        if (since in 0 until gap) delay(gap - since)
        lastReqAt = System.currentTimeMillis()
    }

    private fun registerFirebaseBlocking(): Pair<String, String>? {
        if (FIREBASE_APP_ID.contains("PLACEHOLDER")) return null
        return try {
            val url = "https://firebaseinstallations.googleapis.com/v1/projects/anime-rift-4142e/installations"
            val payload = """{"fid":"","appId":"$FIREBASE_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:19.1.0"}"""
            val req = okhttp3.Request.Builder().url(url)
                .post(gzipCompress(payload).toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addHeader("Content-Type", "application/json").addHeader("Accept", "application/json")
                .addHeader("Content-Encoding", "gzip").addHeader("Cache-Control", "no-cache")
                .addHeader("X-Android-Package", ANDROID_PACKAGE).addHeader("X-Android-Cert", ANDROID_CERT)
                .addHeader("x-goog-api-key", FIREBASE_API_KEY)
                .addHeader("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
                .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Pro Build/AP2A.240805.015)")
                .build()
            httpClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val j = mapper.readTree(r.body?.string().orEmpty())
                val fid = j.get("fid")?.asText() ?: return null
                val tok = j.get("authToken")?.get("token")?.asText() ?: return null
                Pair(fid, tok)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun mintIdentity(): Idx {
        val ft = withContext(Dispatchers.IO) { registerFirebaseBlocking() }
        val fid = ft?.first ?: genFid()
        val tok = ft?.second ?: "synthetic:${System.currentTimeMillis()}"

        if (gatewayBaseUrl.isNullOrEmpty()) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/536921039715/namespaces/firebase:fetch"
                val payload = mapOf(
                    "appVersion" to "3.13.5", "appInstanceIdToken" to tok, "appBuild" to "68",
                    "appInstanceId" to fid, "analyticsUserProperties" to emptyMap<String, String>(),
                    "appId" to FIREBASE_APP_ID, "platformVersion" to "34", "sdkVersion" to "23.0.1",
                    "packageName" to ANDROID_PACKAGE
                )
                val headers = mapOf(
                    "X-Goog-Api-Key" to FIREBASE_API_KEY, "X-Android-Package" to ANDROID_PACKAGE,
                    "X-Android-Cert" to ANDROID_CERT, "X-Goog-Firebase-Installations-Auth" to tok,
                    "Content-Type" to "application/json"
                )
                val r = rawCall(url, "POST", headers, payload)
                if (r.code in 200..299) {
                    gatewayBaseUrl = mapper.readTree(r.text).get("entries")
                        ?.get("anime_rift_android_gateway_base_url_v4")?.asText()?.replace("\"", "")
                }
            } catch (_: Exception) {}
            if (gatewayBaseUrl.isNullOrEmpty()) gatewayBaseUrl = mainUrl
        }

        val (build, info) = generateSyntheticDevice()
        val i = Idx(
            fid = fid, tok = tok, dev = "$fid:${generateFcmToken()}", sess = "",
            build = build, born = System.currentTimeMillis(), used = 0L,
            reqs = 0, dead = false, info = info
        )
        val payload = mapOf(
            "deviceId" to i.dev, "current_app_version" to "3.13.5", "device_os" to "android",
            "device_environment" to "production", "device_info" to info,
            "install_source" to "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER", "deviceOsId" to build,
            "firebaseInstallationId" to fid, "apn_token" to null, "install_mode" to 2
        )
        val r = rawCall("${gatewayBaseUrl ?: mainUrl}/auth/register/device",
            "POST", buildHeaders(i, "USER.AUTH.DEVICE.REGISTER"), payload)
        if (r.code == 429) {
            breakerUntil = System.currentTimeMillis() + 120_000L + Random().nextLong(60_000L)
            throw Exception("register rate limited — backing off")
        }
        if (r.code !in 200..299) throw Exception("register HTTP ${r.code}")
        i.sess = mapper.readTree(r.text).get("sessionKey")?.asText() ?: ""
        synchronized(this) { savePool(loadPool() + i) }
        Log.d(TAG, "device registered under fresh identity")
        return i
    }

    private suspend fun acquireIdentity(): Idx {
        active?.let { if (usable(it)) return it }
        return withContext(Dispatchers.IO) {
            pickIdentity()?.let { active = it; return@withContext it }
            val m = mintIdentity()
            active = m
            m
        }
    }

    private suspend fun ensureInitialized(forceRefresh: Boolean = false) {
        if (!forceRefresh && active?.let { usable(it) } == true) return
        if (forceRefresh) active = null
        acquireIdentity()
    }

    private suspend fun apiCall(
        url: String, scope: String, method: String = "GET", body: Map<String, Any?>? = null
    ): JsonNode {
        val key = cacheKey(method.uppercase(), url, body)
        val ttl = ttlFor(scope)
        if (ttl > 0) cacheGet(key)?.let { return it }

        if (System.currentTimeMillis() < breakerUntil) {
            cachePeek(key)?.let { return it }
            throw Exception("cooldown active (${(breakerUntil - System.currentTimeMillis()) / 1000}s remaining)")
        }

        ensureInitialized()
        var attempts = 0
        while (true) {
            val id = acquireIdentity()
            pace(scope)
            val resp = rawCall(url, method, buildHeaders(id, scope), body)
            touch(id)

            if (resp.code == 429 || resp.text.contains("Too Many Requests")) {
                attempts++
                val back = resp.retryAfter ?: minOf(900L, 30L * (1L shl (attempts - 1)))
                breakerUntil = System.currentTimeMillis() + back * 1000 + Random().nextLong(5000L)
                if (attempts >= 2) retire(id)
                if (attempts >= 3) throw Exception("rate limited — cooling ${back}s")
                continue
            }
            if (resp.code == 401 || resp.code == 403 ||
                resp.text.contains("ACCESS_DENIED") || resp.text.contains("DEVICE_SUSPENDED")) {
                attempts++
                retire(id)
                if (attempts >= 3) throw Exception("identities rejected repeatedly")
                continue
            }
            if (resp.code !in 200..299) throw Exception("HTTP ${resp.code} from $url")

            val parsed = mapper.readTree(resp.text)
            parsed.get("errorCode")?.asText()?.takeIf { it.isNotEmpty() && it != "null" }
                ?.let { throw Exception("errorCode=$it from $url") }
            if (ttl > 0) cachePut(key, parsed, ttl)
            return parsed
        }
    }

    private fun resolveRiftRedirect(url: String, headers: Map<String, String>): String? {
        return try {
            val hb = okhttp3.Headers.Builder()
            headers.filterKeys { it.lowercase() != "range" && it.lowercase() != "host" }
                .forEach { (k, v) -> hb.add(k, v) }
            val req = okhttp3.Request.Builder().url(url).headers(hb.build()).head().build()
            httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(req).execute().use { resp ->
                    if (resp.isRedirect) {
                        resp.header("Location")?.let { loc ->
                            try { java.net.URI(url).resolve(loc).toString() } catch (_: Exception) { loc }
                        }
                    } else null
                }
        } catch (_: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        if (!vpnNoticeShown) {
            vpnNoticeShown = true
            try {
                CommonActivity.showToast(
                    "⚠️ تنبيه: يُرجى استخدام VPN لتجنب حظر عنوان الـ IP الخاص بك",
                    android.widget.Toast.LENGTH_LONG
                )
            } catch (_: Exception) {}
        }
        ensureInitialized()
        val json = apiCall("${gatewayBaseUrl ?: mainUrl}/library/home_content?with_genres=true", "ANIME.LIBRARY.HOME_CONTENT")

        val homeLists = mutableListOf<HomePageList>()
        json.get("sections")?.forEach { section ->
            val title = section.get("sectionTitle")?.asText() ?: ""
            val list = mutableListOf<SearchResponse>()
            section.get("items")?.forEach { item ->
                val rating = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                list.add(newAnimeSearchResponse(
                    name = item.get("title")?.asText() ?: "",
                    url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
                ) {
                    this.posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                    this.year = item.get("release_year")?.asInt()
                    this.score = rating?.let { Score.from10(it) }
                })
            }
            if (list.isNotEmpty()) homeLists.add(HomePageList(title, list, isHorizontalImages = true))
        }
        return newHomePageResponse(homeLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureInitialized()
        val url = "${gatewayBaseUrl ?: mainUrl}/library/search?page=${(page - 1).coerceAtLeast(0)}&sort_by=release_year&sort_direction=1&text_direction=jp"
        return try {
            val json = apiCall(url, "ANIME.LIBRARY.SEARCH", method = "POST", body = mapOf("query" to query))
            val items = json.get("items") ?: return null
            val results = mutableListOf<SearchResponse>()
            items.forEach { item ->
                val rating = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                results.add(newAnimeSearchResponse(
                    name = item.get("title")?.asText() ?: "",
                    url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
                ) {
                    this.posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                    this.year = item.get("release_year")?.asInt()
                    this.score = rating?.let { Score.from10(it) }
                })
            }
            newSearchResponseList(results, json.get("hasNext")?.asBoolean() ?: false)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInitialized()
        val animeId = url.substringAfterLast("/")
        val json = apiCall("${gatewayBaseUrl ?: mainUrl}/library/details/$animeId", "ANIME.LIBRARY.DETAILS")
        val item = json.get("item") ?: throw ErrorLoadingException("Failed to load anime details")

        val allRelated = mutableListOf<AnimeSearchResponse>()
        json.get("others")?.get("recommendations")?.forEach { rec ->
            val rating = rec.get("myAnimeList_rating")?.asDouble()?.toFloat()
            allRelated.add(newAnimeSearchResponse(
                name = rec.get("title")?.asText() ?: "",
                url = "$mainUrl/api/v4/library/details/${rec.get("_id")?.asText()}"
            ) {
                this.posterUrl = rec.get("main_picture")?.asText()
                this.score = rating?.let { Score.from10(it) }
            } as AnimeSearchResponse)
        }

        val episodesList = mutableListOf<Episode>()
        val orderedData = mutableListOf<String>()
        try {
            val epJson = apiCall(
                "${gatewayBaseUrl ?: mainUrl}/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true",
                "ANIME.LIBRARY.EPISODES.ALL"
            )
            epJson.get("items")?.forEach { ep ->
                val epNumber = ep.get("episode_number")?.asInt() ?: 1
                val epId = ep.get("_id")?.asText() ?: ""
                val dataStr = "$animeId|$epId|$epNumber"
                orderedData.add(dataStr)
                episodesList.add(newEpisode(data = dataStr) {
                    this.name = "الحلقة $epNumber"; this.episode = epNumber; this.season = 1
                    this.posterUrl = ep.get("thumbnail")?.asText()
                })
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}

        episodeOrder = orderedData.toList()

        val rating = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
        return newAnimeLoadResponse(name = item.get("title")?.asText() ?: "", url = url, type = TvType.Anime) {
            this.posterUrl = item.get("main_picture")?.asText()
            this.plot = item.get("synopsis")?.asText()
            this.year = item.get("release_year")?.asInt()
            this.score = rating?.let { Score.from10(it) }
            this.tags = item.get("genreLabels")?.mapNotNull { it.get("label")?.asText() }
            this.showStatus = when (item.get("release_status")?.asText()) {
                "on_going" -> ShowStatus.Ongoing
                "finished" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = allRelated
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }

    private suspend fun resolveEpisode(
        animeId: String, episodeId: String, episodeNumber: Int,
        onLink: (RLink) -> Unit, onSub: (SubtitleFile) -> Unit
    ): RBundle {
        val links = mutableListOf<RLink>()
        val subs = mutableListOf<SubtitleFile>()
        val base = gatewayBaseUrl ?: mainUrl

        val sourcesJson = apiCall("$base/library/episode/sources",
            "ANIME.LIBRARY.EPISODES.SOURCES.ALL", method = "POST",
            body = mapOf("animeId" to animeId, "episodeId" to episodeId, "episode_number" to episodeNumber))
        val items = sourcesJson.get("items") ?: return RBundle(links, subs, System.currentTimeMillis())

        val configs = mutableMapOf<String, JsonNode>()
        sourcesJson.get("configs")?.forEach { c ->
            c.get("server_key")?.asText()?.let { k -> configs[k] = c }
        }
        Log.d(TAG, "sources: ${items.size()} items, ${configs.size} configs")

        val filtered = items.filter { src ->
            val st = src.get("sub_title")?.asText() ?: ""
            st.startsWith("ar_") || st.isEmpty()
        }.ifEmpty { items.toList() }

        // every server stays in the list — priority only changes the order they resolve in
        val sorted = filtered.sortedBy { src ->
            val provider = src.get("provider")?.asText() ?: ""
            val serverPriority = configs[provider]?.get("priority")?.asInt()
            val idx = priorityProviders.indexOf(provider)
            when {
                serverPriority != null && serverPriority > 0 -> serverPriority
                idx != -1 -> 100 + idx
                else -> 200
            }
        }

        for (src in sorted) {
            val hostId = src.get("_id")?.asText() ?: continue
            val serverName = src.get("server_name")?.asText() ?: "Server"
            val provider = src.get("provider")?.asText() ?: ""
            val subTitle = src.get("sub_title")?.asText() ?: ""

            val loadingDuration = configs[provider]?.get("loadingDuration")?.asInt() ?: 0
            if (loadingDuration in 1..15) delay(minOf(loadingDuration, 8) * 100L)

            val qualitiesNode = src.get("qualities")
            val qualities = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                qualitiesNode.map { it.asText() }.sortedByDescending { extractQuality(it) }
            } else listOf("720P")

            for (quality in qualities) {
                try {
                    val sessionId = apiCall("$base/library/episode/source/can_play",
                        "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY", method = "POST",
                        body = mapOf(
                            "episodeId" to episodeId, "hostId" to hostId,
                            "is_download" to false, "event_name" to "play_episode_unlocked"
                        )).get("sessionId")?.asText() ?: ""

                    delay(600L + Random().nextLong(1200L))
                    try {
                        apiCall("$base/ads_manager/claim", "USER.ADS_MANAGER.CLAIMS", method = "PUT",
                            body = mapOf(
                                "event_name" to "play_episode_unlocked", "hostId" to hostId,
                                "episodeId" to episodeId,
                                "transactionRef" to "${System.currentTimeMillis()}_${Random().nextInt(99999999)}",
                                "reward_result" to if (Random().nextInt(100) < 70) "filled" else "not_filled",
                                "streamingServerKey" to provider, "is_optional" to false, "is_reward" to true
                            ))
                    } catch (_: Exception) {}

                    val dl = apiCall("$base/library/episode/source/direct_link",
                        "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK", method = "POST",
                        body = mapOf(
                            "id" to hostId, "quality" to quality,
                            "with_internal_player" to "1", "sessionId" to sessionId
                        ))

                    dl.get("tracks")?.forEach { track ->
                        val trackUrl = track.get("file")?.asText() ?: track.get("url")?.asText()
                        val trackLang = track.get("label")?.asText()
                            ?: track.get("language")?.asText() ?: "Arabic"
                        if (!trackUrl.isNullOrEmpty()) {
                            val sf = SubtitleFile(trackLang, trackUrl)
                            subs.add(sf)
                            onSub(sf)
                        }
                    }

                    if (dl.get("url_response")?.asBoolean() == true) {
                        val videoUrl = dl.get("videoUrl")?.asText()
                        if (!videoUrl.isNullOrEmpty()) {
                            val customHeaders = mutableMapOf(
                                "User-Agent" to "libmpv", "Accept" to "*/*",
                                "Range" to "bytes=0-", "Connection" to "close", "Icy-MetaData" to "1"
                            )
                            customHeaders["Host"] = try {
                                java.net.URI(videoUrl).host
                            } catch (_: Exception) { "media-1.rift-content.com" }
                            dl.get("http_headers")?.fields()?.forEach { (k, v) -> customHeaders[k] = v.asText() }

                            val resolvedUrl = if (videoUrl.contains("media.rift-streamer.com/videos/watch")) {
                                resolveRiftRedirect(videoUrl, customHeaders) ?: videoUrl
                            } else videoUrl
                            val finalHeaders = if (resolvedUrl != videoUrl) {
                                mapOf("User-Agent" to "libmpv", "Accept" to "*/*")
                            } else customHeaders

                            val rl = RLink(serverName, subTitle, quality, resolvedUrl, finalHeaders)
                            links.add(rl)
                            onLink(rl)
                        }
                    } else if (dl.get("ticket_response")?.asBoolean() == true) {
                        val fileId = dl.get("fileId")?.asText() ?: ""
                        val ticket = dl.get("ticket")?.asText() ?: ""
                        if (fileId.isNotEmpty() && ticket.isNotEmpty()) {
                            delay((dl.get("wait_time")?.asLong() ?: 5L) * 1000 + 500)
                            val tapeApiUrl = "https://api.streamtape.com/file/dl?file=$fileId&ticket=$ticket"
                            var tapeRes = apiCall(tapeApiUrl, "STREAMTAPE.TICKET")
                            if (tapeRes.get("status")?.asInt() != 200) {
                                delay(2000)
                                tapeRes = apiCall(tapeApiUrl, "STREAMTAPE.TICKET")
                            }
                            val tapeDirectUrl = tapeRes.get("result")?.get("url")?.asText()
                            if (!tapeDirectUrl.isNullOrEmpty()) {
                                val rl = RLink(serverName, subTitle, quality, tapeDirectUrl,
                                    mapOf("User-Agent" to "libmpv", "Accept" to "*/*"))
                                links.add(rl)
                                onLink(rl)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
        }
        return RBundle(links, subs, System.currentTimeMillis())
    }

    private fun linkFrom(rl: RLink): ExtractorLink =
        newExtractorLink(source = name, name = "${rl.serverName} [${rl.subTitle}] - ${rl.quality}", url = rl.url) {
            this.quality = extractQuality(rl.quality)
            this.headers = rl.headers
        }

    private fun scheduleNextPrefetch(current: String) {
        if (System.currentTimeMillis() < breakerUntil) return
        val idx = episodeOrder.indexOf(current)
        if (idx < 0) return
        val next = episodeOrder.getOrNull(idx + 1) ?: return
        if (prefetchCache.containsKey(next) || prefetchJobs.containsKey(next)) return
        val p = next.split('|')
        if (p.size < 2) return

        val job = prefetchScope.launch {
            try {
                // staggered start so prefetch traffic doesn't glue onto your playback burst
                delay(8000L + Random().nextLong(12000L))
                if (prefetchCache.containsKey(next)) return@launch
                ensureInitialized()
                val links = mutableListOf<RLink>()
                val subs = mutableListOf<SubtitleFile>()
                resolveEpisode(p[0], p[1], p.getOrNull(2)?.toIntOrNull() ?: 1,
                    { links.add(it) }, { subs.add(it) })
                if (links.isNotEmpty()) {
                    if (prefetchCache.size >= 3) {
                        prefetchCache.keys.toList()
                            .take(prefetchCache.size - 2)
                            .forEach { prefetchCache.remove(it) }
                    }
                    prefetchCache[next] = RBundle(links, subs, System.currentTimeMillis())
                    Log.d(TAG, "prefetched ${links.size} links for next episode")
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
            prefetchJobs.remove(next)
        }
        prefetchJobs[next] = job
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split('|')
        if (parts.size < 2) return@withContext false
        val animeId = parts[0]
        val episodeId = parts[1]
        val episodeNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1

        try {
            var emitted = 0
            val cached = takePrefetch(data)
            if (cached != null) {
                Log.d(TAG, "loadLinks prefetch hit: ${cached.links.size} links, zero wait")
                cached.subtitles.forEach { subtitleCallback(it) }
                cached.links.forEach { rl ->
                    callback.invoke(linkFrom(rl))
                    emitted++
                }
            } else {
                ensureInitialized()
                resolveEpisode(animeId, episodeId, episodeNumber,
                    { rl ->
                        callback.invoke(linkFrom(rl))
                        emitted++
                    },
                    { subtitleCallback(it) })
            }
            scheduleNextPrefetch(data)
            return@withContext emitted > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            return@withContext false
        }
    }

    private fun extractQuality(resolution: String?): Int {
        if (resolution == null) return Qualities.Unknown.value
        val cleanRes = resolution.lowercase().trim()
        return when {
            cleanRes.contains("2160") || cleanRes.contains("4k") -> Qualities.P2160.value
            cleanRes.contains("1440") -> Qualities.P1440.value
            cleanRes.contains("1080") -> Qualities.P1080.value
            cleanRes.contains("720") -> Qualities.P720.value
            cleanRes.contains("480") -> Qualities.P480.value
            cleanRes.contains("360") -> Qualities.P360.value
            cleanRes.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
