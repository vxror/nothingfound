package com.animerift

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AnimeRift : MainAPI() {

    override var mainUrl = "https://gateway.anime-rift.com"
    private val defaultApiBase = "https://gateway.anime-rift.com/api/v4"

    override var name = "AnimeRift"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val JWT_SECRET = "RIFT_JWT_SECRET_PLACEHOLDER"
    private val FIREBASE_API_KEY = "AIzaSyBiLkiGEm7ruugny3tDFHEZvqli8yv1k7I"
    private val FIREBASE_APP_ID = "1:536921039715:android:ri3okhhk7t0gbh6k70n4v7dh8da8nd8b"
    private val FIREBASE_PROJECT_ID = "anime-rift-4142e"
    private val FIREBASE_SENDER_ID = "536921039715"
    private val ANDROID_PACKAGE = "com.riftapps.animerift"
    private val ANDROID_CERT = "AF40CE82A52AA4107F311D8B9727D01C8D02250B"

    companion object {
        private const val TAG = "AnimeRift"

        private var fid: String? = null
        private var firebaseToken: String? = null
        private var gatewayBaseUrl: String? = null
        private var sessionKey: String? = null
        private var deviceId: String? = null
        private var syntheticBuildId: String? = null
        private var syntheticDeviceInfoJson: String? = null
        private var vpnNoticeCount = 0
        private var firebaseFailed = false

        private val providerConfigs = HashMap<String, JsonNode>()

        private var consecutiveFailures = 0
        private var lastRequestAt = 0L
        private val initLock = Any()

        private val jsonCache = AnimeRiftCache(maxEntries = 300)
        private val prefetcher = AnimeRiftPrefetcher(TAG, ttlMs = 5 * 60 * 1000L, windowSize = 2)

        private fun getCacheFile(): File {
            val tmp = System.getProperty("java.io.tmpdir") ?: "."
            return File(tmp, "anime_rift_session_store.json")
        }

        private fun saveToDisk(data: Map<String, String>) {
            try {
                getCacheFile().writeText(ObjectMapper().writeValueAsString(data))
            } catch (_: Exception) {}
        }

        private fun loadFromDisk(): Map<String, String>? {
            return try {
                val file = getCacheFile()
                if (file.exists() && file.length() > 0) {
                    val node = ObjectMapper().readTree(file.readText())
                    mapOf(
                        "fid" to (node.get("fid")?.asText() ?: ""),
                        "firebaseToken" to (node.get("firebaseToken")?.asText() ?: ""),
                        "gatewayBaseUrl" to (node.get("gatewayBaseUrl")?.asText() ?: ""),
                        "deviceId" to (node.get("deviceId")?.asText() ?: ""),
                        "sessionKey" to (node.get("sessionKey")?.asText() ?: ""),
                        "syntheticBuildId" to (node.get("syntheticBuildId")?.asText() ?: ""),
                        "syntheticDeviceInfoJson" to (node.get("syntheticDeviceInfoJson")?.asText() ?: "")
                    )
                } else null
            } catch (_: Exception) { null }
        }

        private fun wipeIdentity() {
            try { getCacheFile().delete() } catch (_: Exception) {}
            fid = null
            firebaseToken = null
            sessionKey = null
            deviceId = null
            syntheticBuildId = null
            syntheticDeviceInfoJson = null
            Log.d(TAG, "identity wiped — next call mints a fresh device")
        }
    }

    private val mapper = ObjectMapper()
    private val apiBase: String get() = gatewayBaseUrl ?: defaultApiBase

    private fun log(msg: String) { Log.d(TAG, msg) }
    private fun logErr(s: String, e: Throwable) { Log.e(TAG, "$s: ${e.message}") }

    private fun generateSyntheticFid(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        return (1..22).map { chars.random() }.joinToString("")
    }

    private fun generateSyntheticDevice(): Pair<String, String> {
        val brands = listOf(
            Triple("google", "Google", "Pixel 8 Pro"),
            Triple("google", "Google", "Pixel 7"),
            Triple("samsung", "samsung", "Galaxy S23"),
            Triple("xiaomi", "Xiaomi", "Xiaomi 13")
        )
        val selected = brands.random()
        val randomNum = "${Random().nextInt(899999) + 100000}.015"
        val buildId = "AP2A.240805.$randomNum"
        val uniqueTag = UUID.randomUUID().toString().substring(0, 8)

        val deviceInfo = mapOf(
            "model" to selected.third,
            "brand" to selected.first,
            "manufacturer" to selected.second,
            "device" to selected.first,
            "hardware" to "qcom",
            "product" to selected.first,
            "androidVersion" to "14",
            "sdkInt" to 34,
            "securityPatch" to "2025-01-01",
            "isPhysicalDevice" to true,
            "supportedAbis" to listOf("arm64-v8a"),
            "tags" to "release-keys",
            "type" to "user",
            "buildId" to buildId,
            "host" to "build-server-$uniqueTag",
            "fingerprint" to "${selected.first}/${selected.third}/${selected.first}:14/$buildId/V.1e9a_$uniqueTag:user/release-keys"
        )
        return buildId to mapper.writeValueAsString(deviceInfo)
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun generateIntegrityToken(scope: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 60
        val header = base64UrlEncode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = base64UrlEncode("""{"scope":"$scope","exp":$exp,"iat":$now}""".toByteArray())
        val toSign = "$header.$payload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(JWT_SECRET.toByteArray(), "HmacSHA256"))
        return "$toSign.${base64UrlEncode(mac.doFinal(toSign.toByteArray()))}"
    }

    private fun generateFcmToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return "APA91b" + (1..80).map { chars.random() }.joinToString("")
    }

    private fun generateDeviceTimezone(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val nano = Random().nextInt(999999).toString().padStart(6, '0')
        return "${format.format(Date())}.$nano"
    }

    private fun gzipCompress(data: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data.toByteArray()) }
        return bos.toByteArray()
    }

    private fun resolveRiftRedirect(url: String, headers: Map<String, String>): String? {
        return try {
            val hb = okhttp3.Headers.Builder()
            headers.filterKeys {
                val k = it.lowercase()
                k != "range" && k != "host"
            }.forEach { (k, v) -> hb.add(k, v) }

            val client = app.baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val req = okhttp3.Request.Builder()
                .url(url)
                .headers(hb.build())
                .head()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isRedirect) {
                    resp.header("Location")?.let { loc ->
                        try { java.net.URI(url).resolve(loc).toString() } catch (_: Exception) { loc }
                    }
                } else null
            }
        } catch (e: Exception) {
            logErr("resolveRiftRedirect", e)
            null
        }
    }

    private suspend fun ensureInitialized(forceRefresh: Boolean = false, rotateIdentity: Boolean = false) {
        synchronized(initLock) {
            if (rotateIdentity) {
                wipeIdentity()
                firebaseFailed = false
            }

            if (!forceRefresh) {
                if (!fid.isNullOrEmpty() && !gatewayBaseUrl.isNullOrEmpty() &&
                    !firebaseToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()) return

                loadFromDisk()?.let { disk ->
                    fid = disk["fid"]
                    firebaseToken = disk["firebaseToken"]
                    gatewayBaseUrl = disk["gatewayBaseUrl"].takeIf { !it.isNullOrEmpty() } ?: defaultApiBase
                    deviceId = disk["deviceId"]
                    sessionKey = disk["sessionKey"]
                    syntheticBuildId = disk["syntheticBuildId"]
                    syntheticDeviceInfoJson = disk["syntheticDeviceInfoJson"]
                    if (!fid.isNullOrEmpty() && !firebaseToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()) return
                }
            }
        }

        withContext(Dispatchers.IO) {
            AnimeRiftHttp.prewarm(
                "gateway.anime-rift.com",
                "media.rift-streamer.com",
                "media-1.rift-content.com",
                "firebaseinstallations.googleapis.com"
            )

            if (!firebaseFailed) {
                try {
                    registerFirebaseInstallation()
                    log("firebase fid ok: $fid")
                } catch (e: Exception) {
                    logErr("firebase failed — using synthetic fid", e)
                    firebaseFailed = true
                    fid = generateSyntheticFid()
                    firebaseToken = "synthetic:${System.currentTimeMillis()}"
                }
            } else {
                fid = generateSyntheticFid()
                firebaseToken = "synthetic:${System.currentTimeMillis()}"
            }

            if (firebaseFailed) {
                gatewayBaseUrl = defaultApiBase
            } else {
                try {
                    fetchRemoteConfig()
                } catch (e: Exception) {
                    logErr("remoteconfig failed — default api base", e)
                    gatewayBaseUrl = defaultApiBase
                }
                if (gatewayBaseUrl.isNullOrEmpty()) gatewayBaseUrl = defaultApiBase
            }

            try {
                registerDevice()
            } catch (e: Exception) {
                logErr("registerDevice failed — continuing anyway", e)
            }

            synchronized(initLock) {
                saveToDisk(
                    mapOf(
                        "fid" to (fid ?: ""),
                        "firebaseToken" to (firebaseToken ?: ""),
                        "gatewayBaseUrl" to (gatewayBaseUrl ?: defaultApiBase),
                        "deviceId" to (deviceId ?: ""),
                        "sessionKey" to (sessionKey ?: ""),
                        "syntheticBuildId" to (syntheticBuildId ?: "BP2A.250605.015"),
                        "syntheticDeviceInfoJson" to (syntheticDeviceInfoJson ?: "{}")
                    )
                )
                consecutiveFailures = 0
            }
        }
    }

    private fun registerFirebaseInstallation() {
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/installations"
        val payloadStr = """{"fid":"","appId":"$FIREBASE_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:19.1.0"}"""

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(gzipCompress(payloadStr).toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("X-Android-Package", ANDROID_PACKAGE)
            .addHeader("X-Android-Cert", ANDROID_CERT)
            .addHeader("x-goog-api-key", FIREBASE_API_KEY)
            .addHeader("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
            .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Pro Build/AP2A.240805.015)")
            .build()

        app.baseClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = runCatching {
                    mapper.readTree(body).get("error")?.get("message")?.asText()
                }.getOrNull()
                throw Exception("Firebase HTTP ${response.code}: ${errMsg ?: body.take(200)}")
            }

            val json = mapper.readTree(body)
            val fidNode = json.get("fid")
            val tokenNode = json.get("authToken")?.get("token")

            if (fidNode == null || fidNode.isNull || tokenNode == null || tokenNode.isNull) {
                throw Exception("Firebase response missing fid/authToken: ${body.take(300)}")
            }

            fid = fidNode.asText()
            firebaseToken = tokenNode.asText()
        }
    }

    private suspend fun fetchRemoteConfig() {
        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$FIREBASE_SENDER_ID/namespaces/firebase:fetch"
        val payload = mapOf(
            "appVersion" to "3.13.5",
            "appInstanceIdToken" to (firebaseToken ?: ""),
            "appBuild" to "68",
            "appInstanceId" to (fid ?: ""),
            "analyticsUserProperties" to emptyMap<String, String>(),
            "appId" to FIREBASE_APP_ID,
            "platformVersion" to "34",
            "sdkVersion" to "23.0.1",
            "packageName" to ANDROID_PACKAGE
        )
        val headers = mapOf(
            "X-Goog-Api-Key" to FIREBASE_API_KEY,
            "X-Android-Package" to ANDROID_PACKAGE,
            "X-Android-Cert" to ANDROID_CERT,
            "X-Goog-Firebase-Installations-Auth" to (firebaseToken ?: ""),
            "Content-Type" to "application/json"
        )

        val response = app.post(url, headers = headers, json = payload)
        val text = response.text
        if (!response.isSuccessful) {
            Log.e(TAG, "RemoteConfig HTTP ${response.code}")
            gatewayBaseUrl = defaultApiBase
            return
        }
        if (text.trimStart().startsWith("<")) {
            gatewayBaseUrl = defaultApiBase
            return
        }
        val json = mapper.readTree(text)
        gatewayBaseUrl = json.get("entries")
            ?.get("anime_rift_android_gateway_base_url_v4")
            ?.asText()
            ?.replace("\"", "")
            ?.takeIf { it.isNotEmpty() }
            ?: defaultApiBase
    }

    private suspend fun registerDevice() {
        val url = "$apiBase/auth/register/device"
        deviceId = "${fid ?: ""}:${generateFcmToken()}"
        val (buildId, deviceInfoJson) = generateSyntheticDevice()
        syntheticBuildId = buildId
        syntheticDeviceInfoJson = deviceInfoJson

        val payload = mapOf(
            "deviceId" to deviceId,
            "current_app_version" to "3.13.5",
            "device_os" to "android",
            "device_environment" to "production",
            "device_info" to deviceInfoJson,
            "install_source" to "IS_INSTALLED_FROM_OTHER_SOURCE",
            "deviceOsId" to buildId,
            "firebaseInstallationId" to fid,
            "apn_token" to null,
            "install_mode" to 2
        )

        val json = apiCall(url, "USER.AUTH.DEVICE.REGISTER", "POST", payload)
        sessionKey = json.get("sessionKey")?.asText()
        log("device registered under fresh identity")
    }

    private suspend fun apiCall(
        url: String, scope: String, method: String = "GET",
        body: Map<String, Any?>? = null, isRetry: Boolean = false
    ): JsonNode {
        ensureInitialized()

        val ttl = AnimeRiftCache.ttlFor(scope)
        val key = AnimeRiftCache.cacheKey(method.uppercase(), url, body)

        if (ttl > 0) {
            jsonCache.get(key)?.let {
                log("cache hit ${scope} ...${url.takeLast(48)}")
                return it
            }
        }

        val since = System.currentTimeMillis() - lastRequestAt
        if (since in 0 until 120) delay(120 - since)
        lastRequestAt = System.currentTimeMillis()

        val integrityToken = generateIntegrityToken(scope)

        val headers = mapOf(
            "x-device-os-id" to (syntheticBuildId ?: "AP2A.240805.015"),
            "user-agent" to "Dart/3.10 (dart:io)",
            "x-device-release-version" to "3.13.5",
            "x-firebase-app-check" to "null",
            "authorization" to "Bearer null",
            "content-type" to "application/json; charset=UTF-8",
            "x-installation-source" to "IS_INSTALLED_FROM_OTHER_SOURCE",
            "integrity" to "Bearer $integrityToken",
            "accept" to "application/json",
            "x-firebase-id" to (fid ?: ""),
            "x-device-id" to (deviceId ?: ""),
            "x-device-timezone" to generateDeviceTimezone(),
            "x-device-language" to "ar",
            "x-platform" to "Mobile",
            "x-os" to "android"
        )

        try {
            val response = when (method.uppercase()) {
                "POST" -> app.post(url, headers = headers, json = body)
                "PUT" -> app.put(url, headers = headers, json = body)
                else -> app.get(url, headers = headers)
            }

            val text = response.text
            if (text.isBlank()) throw Exception("Empty response from $url (HTTP ${response.code})")
            if (text.trimStart().startsWith("<")) {
                throw Exception("Non-JSON response from $url (HTTP ${response.code}): ${text.take(140)}")
            }

            val parsed = try {
                mapper.readTree(text)
            } catch (e: Exception) {
                throw Exception("Bad JSON from $url (HTTP ${response.code}): ${text.take(140)}")
            }

            if (ttl > 0) jsonCache.put(key, parsed, ttl)

            consecutiveFailures = 0
            return parsed
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val isAuthFail = msg.contains("401") || msg.contains("403") || msg.contains("ACCESS_DENIED")
            if (isAuthFail) {
                consecutiveFailures++
                if (!isRetry) {
                    when {
                        consecutiveFailures >= 2 -> {
                            log("auth failed x$consecutiveFailures → rotating identity")
                            jsonCache.clear()
                            ensureInitialized(forceRefresh = true, rotateIdentity = true)
                        }
                        else -> {
                            log("auth failed → re-init same identity")
                            ensureInitialized(forceRefresh = true)
                        }
                    }
                    delay(1000)
                    return apiCall(url, scope, method, body, isRetry = true)
                }
            }
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

        if (vpnNoticeCount < 3) {
            vpnNoticeCount++
            try {
                com.lagradost.cloudstream3.CommonActivity.showToast(
                    "⚠️ تنبيه: يُرجى استخدام VPN لتجنب حظر عنوان الـ IP الخاص بك",
                    android.widget.Toast.LENGTH_LONG
                )
            } catch (_: Exception) {}
        }

        ensureInitialized()
        val json = apiCall("$apiBase/library/home_content?with_genres=true", "ANIME.LIBRARY.HOME_CONTENT")

        val homeLists = mutableListOf<HomePageList>()
        json.get("sections")?.forEach { section ->
            val title = section.get("sectionTitle")?.asText() ?: ""
            val list = mutableListOf<SearchResponse>()
            section.get("items")?.forEach { item ->
                val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                list.add(
                    newAnimeSearchResponse(
                        name = item.get("title")?.asText() ?: "",
                        url = "$defaultApiBase/library/details/${item.get("_id")?.asText()}"
                    ) {
                        posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                        year = item.get("release_year")?.asInt()
                        score = ratingFloat?.let { Score.from10(it) }
                    }
                )
            }
            if (list.isNotEmpty()) homeLists.add(HomePageList(title, list, isHorizontalImages = true))
        }
        return newHomePageResponse(homeLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureInitialized()
        val apiPage = (page - 1).coerceAtLeast(0)
        val url = "$apiBase/library/search?page=$apiPage&sort_by=release_year&sort_direction=1&text_direction=jp"

        return try {
            val json = apiCall(url, "ANIME.LIBRARY.SEARCH", method = "POST", body = mapOf("query" to query))
            val items = json.get("items") ?: return null

            val results = mutableListOf<SearchResponse>()
            items.forEach { item ->
                val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                results.add(
                    newAnimeSearchResponse(
                        name = item.get("title")?.asText() ?: "",
                        url = "$defaultApiBase/library/details/${item.get("_id")?.asText()}"
                    ) {
                        posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                        year = item.get("release_year")?.asInt()
                        score = ratingFloat?.let { Score.from10(it) }
                    }
                )
            }
            newSearchResponseList(results, json.get("hasNext")?.asBoolean() ?: false)
        } catch (e: Exception) {
            logErr("search", e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInitialized()
        val animeId = url.substringAfterLast("/")
        val json = apiCall("$apiBase/library/details/$animeId", "ANIME.LIBRARY.DETAILS")
        val item = json.get("item") ?: throw ErrorLoadingException("Failed to load anime details")

        val allRelated = mutableListOf<AnimeSearchResponse>()
        json.get("others")?.get("recommendations")?.forEach { rec ->
            val recRating = rec.get("myAnimeList_rating")?.asDouble()?.toFloat()
            allRelated.add(
                newAnimeSearchResponse(
                    name = rec.get("title")?.asText() ?: "",
                    url = "$defaultApiBase/library/details/${rec.get("_id")?.asText()}"
                ) {
                    posterUrl = rec.get("main_picture")?.asText()
                    score = recRating?.let { Score.from10(it) }
                } as AnimeSearchResponse
            )
        }

        val episodesList = mutableListOf<Episode>()
        val orderedData = mutableListOf<String>()
        try {
            val epJson = apiCall(
                "$apiBase/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true",
                "ANIME.LIBRARY.EPISODES.ALL"
            )
            epJson.get("items")?.forEach { ep ->
                val epNumber = ep.get("episode_number")?.asInt() ?: 1
                val epId = ep.get("_id")?.asText() ?: ""
                val dataStr = "$animeId|$epId|$epNumber"
                orderedData.add(dataStr)
                episodesList.add(
                    newEpisode(data = dataStr) {
                        this.name = "الحلقة $epNumber"
                        this.episode = epNumber
                        this.season = 1
                        this.posterUrl = ep.get("thumbnail")?.asText()
                    }
                )
            }
        } catch (e: Exception) { logErr("episodes", e) }

        prefetcher.setEpisodeOrder(animeId, orderedData)
        prefetcher.triggerAll { data ->
            val p = data.split('|')
            if (p.size < 2) null else resolveBundle(p[0], p[1], p.getOrNull(2)?.toIntOrNull() ?: 1)
        }

        val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()

        return newAnimeLoadResponse(
            name = item.get("title")?.asText() ?: "",
            url = url,
            type = TvType.Anime
        ) {
            posterUrl = item.get("main_picture")?.asText()
            plot = item.get("synopsis")?.asText()
            year = item.get("release_year")?.asInt()
            score = ratingFloat?.let { Score.from10(it) }
            tags = item.get("genreLabels")?.mapNotNull { it.get("label")?.asText() }
            showStatus = when (item.get("release_status")?.asText()) {
                "on_going" -> ShowStatus.Ongoing
                "finished" -> ShowStatus.Completed
                else -> null
            }
            recommendations = allRelated
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }

    private suspend fun resolveBundle(
        animeId: String,
        episodeId: String,
        episodeNumber: Int
    ): AnimeRiftBundle {
        val links = mutableListOf<AnimeRiftLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val sourcesJson = apiCall(
            "$apiBase/library/episode/sources",
            "ANIME.LIBRARY.EPISODES.SOURCES.ALL",
            method = "POST",
            body = mapOf("animeId" to animeId, "episodeId" to episodeId, "episode_number" to episodeNumber)
        )
        val items = sourcesJson.get("items") ?: return AnimeRiftBundle(emptyList(), emptyList(), 0L)

        synchronized(providerConfigs) {
            providerConfigs.clear()
            sourcesJson.get("configs")?.forEach { c ->
                c.get("server_key")?.asText()?.let { key -> providerConfigs[key] = c }
            }
        }
        log("sources: ${items.size()} items, ${providerConfigs.size} configs")

        fun providerRank(provider: String): Int {
            val cfg = providerConfigs[provider]
            val serverPriority = cfg?.get("priority")?.asInt()
            val localIdx = listOf("rift-streamer", "cr2", "streamtape").indexOf(provider)
            return when {
                serverPriority != null && serverPriority > 0 -> serverPriority
                localIdx != -1 -> localIdx + 10
                else -> 99
            }
        }

        val filteredItems = items.filter { src ->
            val subTitle = src.get("sub_title")?.asText() ?: ""
            subTitle.startsWith("ar_") || subTitle.isEmpty()
        }.ifEmpty { items.toList() }

        val byProvider = filteredItems.groupBy { it.get("provider")?.asText() ?: "" }
        val topProviders = byProvider.keys
            .sortedBy { providerRank(it) }
            .take(8)
        val sortedItems = topProviders.flatMap { byProvider[it] ?: emptyList() }

        log("providers: ${topProviders.joinToString(",")} → ${sortedItems.size} items")

        val semaphore = Semaphore(4)
        val subLock = Any()
        val linkLock = Any()

        suspend fun processSource(src: JsonNode) {
            val hostId = src.get("_id")?.asText() ?: return
            val serverName = src.get("server_name")?.asText() ?: "Server"
            val provider = src.get("provider")?.asText() ?: ""
            val subTitle = src.get("sub_title")?.asText() ?: ""

            val loadingDuration = providerConfigs[provider]?.get("loadingDuration")?.asInt() ?: 0
            if (loadingDuration in 1..15) delay(loadingDuration * 100L)

            val qualitiesNode = src.get("qualities")
            val qualitiesList = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                qualitiesNode.map { it.asText() }.sortedByDescending { extractQuality(it) }
            } else listOf("720P")

            for (quality in qualitiesList) {
                try {
                    val canPlayJson = apiCall(
                        "$apiBase/library/episode/source/can_play",
                        "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY",
                        method = "POST",
                        body = mapOf(
                            "episodeId" to episodeId,
                            "hostId" to hostId,
                            "is_download" to isDownloadRequest(),
                            "event_name" to "play_episode_unlocked"
                        )
                    )
                    val sessionId = canPlayJson.get("sessionId")?.asText() ?: ""

                    try {
                        apiCall(
                            "$apiBase/ads_manager/claim",
                            "USER.ADS_MANAGER.CLAIMS",
                            method = "PUT",
                            body = mapOf(
                                "event_name" to "play_episode_unlocked",
                                "hostId" to hostId,
                                "episodeId" to episodeId,
                                "transactionRef" to "${System.currentTimeMillis()}_${Random().nextInt(99999999)}",
                                "reward_result" to "not_filled",
                                "streamingServerKey" to provider,
                                "is_optional" to false,
                                "is_reward" to true
                            )
                        )
                    } catch (_: Exception) {}

                    val directLinkJson = apiCall(
                        "$apiBase/library/episode/source/direct_link",
                        "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK",
                        method = "POST",
                        body = mapOf(
                            "id" to hostId,
                            "quality" to quality,
                            "with_internal_player" to "1",
                            "sessionId" to sessionId
                        )
                    )

                    directLinkJson.get("tracks")?.forEach { track ->
                        val trackUrl = track.get("file")?.asText() ?: track.get("url")?.asText()
                        val trackLang = track.get("label")?.asText()
                            ?: track.get("language")?.asText() ?: "Arabic"
                        if (!trackUrl.isNullOrEmpty()) {
                            synchronized(subLock) { subtitles.add(SubtitleFile(trackLang, trackUrl)) }
                        }
                    }

                    if (directLinkJson.get("url_response")?.asBoolean() == true) {
                        val videoUrl = directLinkJson.get("videoUrl")?.asText()
                        if (!videoUrl.isNullOrEmpty()) {
                            val customHeaders = mutableMapOf(
                                "User-Agent" to "libmpv",
                                "Accept" to "*/*",
                                "Range" to "bytes=0-",
                                "Connection" to "close",
                                "Icy-MetaData" to "1",
                                "X-request-source" to "anime-rift",
                                "X-streamer-version" to "1.0.0",
                                "X-request-action" to "watch"
                            )
                            customHeaders["Host"] = try {
                                java.net.URI(videoUrl).host
                            } catch (_: Exception) { "media.rift-streamer.com" }

                            directLinkJson.get("http_headers")?.fields()?.forEach { (k, v) ->
                                customHeaders[k] = v.asText()
                            }

                            val resolvedUrl: String = if (videoUrl.contains("media.rift-streamer.com/videos/watch")) {
                                resolveRiftRedirect(videoUrl, customHeaders) ?: videoUrl
                            } else videoUrl

                            val finalHeaders: Map<String, String> =
                                if (resolvedUrl != videoUrl) {
                                    mapOf("User-Agent" to "libmpv", "Accept" to "*/*")
                                } else {
                                    customHeaders
                                }

                            synchronized(linkLock) {
                                links.add(
                                    AnimeRiftLink(
                                        serverName = serverName,
                                        subTitle = subTitle,
                                        quality = quality,
                                        url = resolvedUrl,
                                        headers = finalHeaders
                                    )
                                )
                            }
                        }
                    } else if (directLinkJson.get("ticket_response")?.asBoolean() == true) {
                        val fileId = directLinkJson.get("fileId")?.asText() ?: ""
                        val ticket = directLinkJson.get("ticket")?.asText() ?: ""
                        val waitTimeSeconds = directLinkJson.get("wait_time")?.asLong() ?: 5L

                        if (fileId.isNotEmpty() && ticket.isNotEmpty()) {
                            delay(waitTimeSeconds * 1000 + 500)

                            val tapeApiUrl = "https://api.streamtape.com/file/dl?file=$fileId&ticket=$ticket"
                            var tapeRes = app.get(tapeApiUrl).parsed<JsonNode>()
                            if (tapeRes.get("status")?.asInt() != 200) {
                                delay(2000)
                                tapeRes = app.get(tapeApiUrl).parsed<JsonNode>()
                            }
                            val tapeDirectUrl = tapeRes.get("result")?.get("url")?.asText()
                            if (!tapeDirectUrl.isNullOrEmpty()) {
                                synchronized(linkLock) {
                                    links.add(
                                        AnimeRiftLink(
                                            serverName = serverName,
                                            subTitle = subTitle,
                                            quality = quality,
                                            url = tapeDirectUrl,
                                            headers = mapOf("User-Agent" to "libmpv", "Accept" to "*/*")
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logErr("server $serverName/$quality", e)
                }
            }
        }

        supervisorScope {
            sortedItems.map { src ->
                async(Dispatchers.IO) { semaphore.withPermit { processSource(src) } }
            }.awaitAll()
        }

        return AnimeRiftBundle(links.toList(), subtitles.toList(), 0L)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            val animeId = parts[0]
            val episodeId = parts[1]
            val episodeNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1

            val cached = prefetcher.cached(data)
            val bundle = cached ?: run {
                val b = resolveBundle(animeId, episodeId, episodeNumber)
                prefetcher.store(data, b)
                b
            }

            log("loadLinks ${if (cached != null) "prefetch" else "cold"}: ${bundle.links.size} links")

            bundle.subtitles.forEach { subtitleCallback(it) }
            bundle.links.forEach { rl ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "${rl.serverName} [${rl.subTitle}] - ${rl.quality}",
                        url = rl.url,
                    ) {
                        this.quality = extractQuality(rl.quality)
                        this.headers = rl.headers
                    }
                )
            }

            prefetcher.triggerAround(data) { d ->
                val p = d.split('|')
                if (p.size < 2) null else resolveBundle(p[0], p[1], p.getOrNull(2)?.toIntOrNull() ?: 1)
            }

            return@withContext bundle.links.isNotEmpty()
        } catch (e: Exception) {
            logErr("loadLinks", e)
            return@withContext false
        }
    }

    private fun isDownloadRequest(): Boolean = false

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
