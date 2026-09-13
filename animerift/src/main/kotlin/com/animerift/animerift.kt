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
    override var name = "AnimeRift"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    // Signature NOT verified on the API layer (proven: HTTP 200 with garbage token).
    // Any string works for integrity. Registration is the only stricter endpoint.
    private val JWT_SECRET = "RIFT_JWT_SECRET_PLACEHOLDER"
    private val FIREBASE_API_KEY = "AIzaSyBiLkiGEm7ruugny3tDFHEZvqli8yv1k7I"

    // Try the second OAuth client hash first — first one was rejected by FIS.
    // If Firebase still 400s, ensureInitialized falls back to a synthetic FID
    // and the gateway accepts it (proven: HTTP 201 with synthetic fid).
    private val FIREBASE_APP_ID = "1:536921039715:android:ri3okhhk7t0gbh6k70n4v7dh8da8nd8b"
    private val FIREBASE_PROJECT_ID = "anime-rift-4142e"
    private val FIREBASE_SENDER_ID = "536921039715"

    private val ANDROID_PACKAGE = "com.riftapps.animerift"
    // cert registered with Firebase — matches the original signer, NOT the kill apk signer
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

        // ban-resistance state
        private var consecutiveFailures = 0
        private var lastRequestAt = 0L
        private val initLock = Any()

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

        // full identity wipe — a banned fid/fcm/device never gets reused
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

    private suspend fun ensureInitialized(forceRefresh: Boolean = false, rotateIdentity: Boolean = false) {
        synchronized(initLock) {
            if (rotateIdentity) wipeIdentity()

            if (!forceRefresh) {
                if (!fid.isNullOrEmpty() && !gatewayBaseUrl.isNullOrEmpty() &&
                    !firebaseToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()) return

                loadFromDisk()?.let { disk ->
                    fid = disk["fid"]
                    firebaseToken = disk["firebaseToken"]
                    gatewayBaseUrl = disk["gatewayBaseUrl"]
                    deviceId = disk["deviceId"]
                    sessionKey = disk["sessionKey"]
                    syntheticBuildId = disk["syntheticBuildId"]
                    syntheticDeviceInfoJson = disk["syntheticDeviceInfoJson"]
                    if (!fid.isNullOrEmpty() && !gatewayBaseUrl.isNullOrEmpty() &&
                        !firebaseToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()) return
                }
            } else if (!rotateIdentity) {
                // forceRefresh without rotation: try cached identity first
                loadFromDisk()?.let { disk ->
                    if (!disk["fid"].isNullOrEmpty() && !disk["firebaseToken"].isNullOrEmpty()) {
                        fid = disk["fid"]
                        firebaseToken = disk["firebaseToken"]
                        gatewayBaseUrl = disk["gatewayBaseUrl"]
                        deviceId = disk["deviceId"]
                        sessionKey = disk["sessionKey"]
                        syntheticBuildId = disk["syntheticBuildId"]
                        syntheticDeviceInfoJson = disk["syntheticDeviceInfoJson"]
                        return
                    }
                }
            }
        }

        withContext(Dispatchers.IO) {
            // FID: try Firebase, fall back to synthetic. Gateway accepts synthetic (proven).
            if (!firebaseFailed) {
                try {
                    registerFirebaseInstallation()
                    log("firebase fid ok: $fid")
                } catch (e: Exception) {
                    logErr("firebase install failed — using synthetic fid", e)
                    firebaseFailed = true
                    fid = generateSyntheticFid()
                    firebaseToken = "synthetic:${System.currentTimeMillis()}"
                }
            } else {
                fid = generateSyntheticFid()
                firebaseToken = "synthetic:${System.currentTimeMillis()}"
            }

            // RemoteConfig: optional — default to mainUrl on failure
            try {
                fetchRemoteConfig()
            } catch (e: Exception) {
                logErr("remoteconfig failed — using mainUrl", e)
                gatewayBaseUrl = mainUrl
            }

            try {
                registerDevice()
            } catch (e: Exception) {
                logErr("registerDevice failed", e)
                // fall back to disk if we have anything usable
                loadFromDisk()?.let { disk ->
                    if (!disk["fid"].isNullOrEmpty() && !disk["firebaseToken"].isNullOrEmpty()) {
                        fid = disk["fid"]
                        firebaseToken = disk["firebaseToken"]
                        gatewayBaseUrl = disk["gatewayBaseUrl"] ?: mainUrl
                        deviceId = disk["deviceId"]
                        sessionKey = disk["sessionKey"]
                        syntheticBuildId = disk["syntheticBuildId"]
                        syntheticDeviceInfoJson = disk["syntheticDeviceInfoJson"]
                        return@withContext
                    }
                }
                throw e
            }

            synchronized(initLock) {
                saveToDisk(
                    mapOf(
                        "fid" to (fid ?: ""),
                        "firebaseToken" to (firebaseToken ?: ""),
                        "gatewayBaseUrl" to (gatewayBaseUrl ?: mainUrl),
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

        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
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
                    return
                }
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "registerFirebaseInstallation attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) Thread.sleep((attempt + 1) * 1500L)
            }
        }
        throw lastError ?: Exception("Firebase installation failed after retries")
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
        if (!response.isSuccessful) {
            Log.e(TAG, "RemoteConfig HTTP ${response.code}")
            gatewayBaseUrl = mainUrl
            return
        }
        val json = response.parsed<JsonNode>()
        gatewayBaseUrl = json.get("entries")
            ?.get("anime_rift_android_gateway_base_url_v4")
            ?.asText()
            ?.replace("\"", "")
            ?: mainUrl
    }

    private suspend fun registerDevice() {
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val url = "$baseUrl/auth/register/device"
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

        // pacing — 150ms minimum gap between requests. rate-limit/ban insurance
        val since = System.currentTimeMillis() - lastRequestAt
        if (since in 0 until 150) delay(150 - since)
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
            val parsed = response.parsed<JsonNode>()
            consecutiveFailures = 0
            return parsed
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val isAuthFail = msg.contains("401") || msg.contains("403") || msg.contains("ACCESS_DENIED")
            if (isAuthFail) {
                consecutiveFailures++
                if (!isRetry) {
                    when {
                        // two strikes → rotate to a completely new device identity
                        consecutiveFailures >= 2 -> {
                            log("auth failed x$consecutiveFailures → rotating identity")
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
                throw e
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
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val json = apiCall("$baseUrl/library/home_content?with_genres=true", "ANIME.LIBRARY.HOME_CONTENT")

        val homeLists = mutableListOf<HomePageList>()
        json.get("sections")?.forEach { section ->
            val title = section.get("sectionTitle")?.asText() ?: ""
            val list = mutableListOf<SearchResponse>()
            section.get("items")?.forEach { item ->
                val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                list.add(
                    newAnimeSearchResponse(
                        name = item.get("title")?.asText() ?: "",
                        url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
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
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val apiPage = (page - 1).coerceAtLeast(0)
        val url = "$baseUrl/library/search?page=$apiPage&sort_by=release_year&sort_direction=1&text_direction=jp"

        return try {
            val json = apiCall(url, "ANIME.LIBRARY.SEARCH", method = "POST", body = mapOf("query" to query))
            val items = json.get("items") ?: return null

            val results = mutableListOf<SearchResponse>()
            items.forEach { item ->
                val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                results.add(
                    newAnimeSearchResponse(
                        name = item.get("title")?.asText() ?: "",
                        url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
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
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val animeId = url.substringAfterLast("/")
        val json = apiCall("$baseUrl/library/details/$animeId", "ANIME.LIBRARY.DETAILS")
        val item = json.get("item") ?: throw ErrorLoadingException("Failed to load anime details")

        val allRelated = mutableListOf<AnimeSearchResponse>()
        json.get("others")?.get("recommendations")?.forEach { rec ->
            val recRating = rec.get("myAnimeList_rating")?.asDouble()?.toFloat()
            allRelated.add(
                newAnimeSearchResponse(
                    name = rec.get("title")?.asText() ?: "",
                    url = "$mainUrl/api/v4/library/details/${rec.get("_id")?.asText()}"
                ) {
                    posterUrl = rec.get("main_picture")?.asText()
                    score = recRating?.let { Score.from10(it) }
                } as AnimeSearchResponse
            )
        }

        val episodesList = mutableListOf<Episode>()
        try {
            val epJson = apiCall(
                "$baseUrl/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true",
                "ANIME.LIBRARY.EPISODES.ALL"
            )
            epJson.get("items")?.forEach { ep ->
                val epNumber = ep.get("episode_number")?.asInt() ?: 1
                episodesList.add(
                    newEpisode(data = "$animeId|${ep.get("_id")?.asText() ?: ""}|$epNumber") {
                        this.name = "الحلقة $epNumber"
                        this.episode = epNumber
                        this.season = 1
                        this.posterUrl = ep.get("thumbnail")?.asText()
                    }
                )
            }
        } catch (e: Exception) { logErr("episodes", e) }

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

            val baseUrl = gatewayBaseUrl ?: mainUrl
            val sourcesJson = apiCall(
                "$baseUrl/library/episode/sources",
                "ANIME.LIBRARY.EPISODES.SOURCES.ALL",
                method = "POST",
                body = mapOf("animeId" to animeId, "episodeId" to episodeId, "episode_number" to episodeNumber)
            )
            val items = sourcesJson.get("items") ?: return@withContext false

            synchronized(providerConfigs) {
                providerConfigs.clear()
                sourcesJson.get("configs")?.forEach { c ->
                    c.get("server_key")?.asText()?.let { key -> providerConfigs[key] = c }
                }
            }
            log("sources: ${items.size()} items, ${providerConfigs.size} configs")

            // server's own priority first, local preference as tie-breaker
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

            val sortedItems = filteredItems.sortedBy { src ->
                providerRank(src.get("provider")?.asText() ?: "")
            }.take(6)

            val semaphore = Semaphore(3)
            val emittedCount = java.util.concurrent.atomic.AtomicInteger(0)

            suspend fun processSource(src: JsonNode) {
                val hostId = src.get("_id")?.asText() ?: return
                val serverName = src.get("server_name")?.asText() ?: "Server"
                val provider = src.get("provider")?.asText() ?: ""
                val subTitle = src.get("sub_title")?.asText() ?: ""

                // honor the server's own loadingDuration for this provider
                val loadingDuration = providerConfigs[provider]?.get("loadingDuration")?.asInt() ?: 0
                if (loadingDuration in 1..15) delay(loadingDuration * 100L)

                // each host lists its OWN valid qualities — iterate them exactly.
                // requesting a quality the host doesn't offer → QUALITY_NOT_AVAILABLE
                val qualitiesNode = src.get("qualities")
                val qualitiesList = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                    qualitiesNode.map { it.asText() }.sortedByDescending { extractQuality(it) }
                } else listOf("720P")

                for (quality in qualitiesList) {
                    try {
                        val canPlayJson = apiCall(
                            "$baseUrl/library/episode/source/can_play",
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

                        // ads claim — best effort, soft gate (server 202s any result)
                        try {
                            apiCall(
                                "$baseUrl/ads_manager/claim",
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
                            "$baseUrl/library/episode/source/direct_link",
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
                                subtitleCallback(SubtitleFile(trackLang, trackUrl))
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
                                    "Icy-MetaData" to "1"
                                )
                                customHeaders["Host"] = try {
                                    java.net.URI(videoUrl).host
                                } catch (_: Exception) { "media-1.rift-content.com" }

                                directLinkJson.get("http_headers")?.fields()?.forEach { (k, v) ->
                                    customHeaders[k] = v.asText()
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverName [$subTitle] - $quality",
                                        url = videoUrl,
                                    ) {
                                        this.quality = extractQuality(quality)
                                        this.headers = customHeaders
                                    }
                                )
                                emittedCount.incrementAndGet()
                            }
                        } else if (directLinkJson.get("ticket_response")?.asBoolean() == true) {
                            // streamtape ticket flow
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
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "$serverName [$subTitle] - $quality",
                                            url = tapeDirectUrl,
                                        ) {
                                            this.quality = extractQuality(quality)
                                            this.headers = mapOf("User-Agent" to "libmpv", "Accept" to "*/*")
                                        }
                                    )
                                    emittedCount.incrementAndGet()
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

            log("loadLinks done: ${emittedCount.get()} links emitted")
            return@withContext emittedCount.get() > 0
        } catch (e: Exception) {
            logErr("loadLinks", e)
            return@withContext false
        }
    }

    private fun isDownloadRequest(): Boolean {
        // CloudStream doesn't expose download intent directly in loadLinks;
        // the app's own flow uses is_download=false for streaming. keep false —
        // direct_link serves the same content either way.
        return false
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
