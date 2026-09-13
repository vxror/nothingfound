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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    private data class CacheEntry(val node: JsonNode, val expiresAt: Long)

    companion object {
        private const val TAG = "AnimeRift"
        private const val CACHE_VERSION = 7
        private const val CRED_VERSION = 5
        private const val MAX_ROTATION_ATTEMPTS = 3
        private const val PREFETCH_TTL_MS = 5 * 60 * 1000L
        private const val CALL_TIMEOUT_MS = 20_000L

        private var fid: String? = null
        private var firebaseToken: String? = null
        private var gatewayBaseUrl: String? = null
        private var sessionKey: String? = null
        private var deviceId: String? = null
        private var syntheticBuildId: String? = null
        private var syntheticDeviceInfoJson: String? = null
        private var firebaseFailed = false
        private var cacheVersionSeen = -1
        private var credVersionSeen = -1

        private val providerConfigs = HashMap<String, JsonNode>()

        private var consecutiveFailures = 0
        private var rotationAttempts = 0
        private var lastRequestAt = 0L
        private val initMutex = Mutex()
        private val rotationMutex = Mutex()
        private val throttleMutex = Mutex()

        @Volatile private var cooldownUntil = 0L
        @Volatile private var rateLimitHits = 0
        @Volatile private var last429At = 0L
        @Volatile private var lastRotationAt = 0L

        private val jsonCache = object : LinkedHashMap<String, CacheEntry>(300, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > 300
        }

        private val prefetchCache = ConcurrentHashMap<String, RBundle>()
        private val prefetchInFlight = ConcurrentHashMap<String, Job>()
        private val prefetchScope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineName("AnimeRift-prefetch")
        )
        @Volatile private var episodeOrder: List<String> = emptyList()

        private val TTL_BY_SCOPE = mapOf(
            "ANIME.LIBRARY.HOME_CONTENT" to 5 * 60 * 1000L,
            "ANIME.LIBRARY.SEARCH" to 3 * 60 * 1000L,
            "ANIME.LIBRARY.DETAILS" to 10 * 60 * 1000L,
            "ANIME.LIBRARY.EPISODES.ALL" to 10 * 60 * 1000L,
            "ANIME.LIBRARY.EPISODES.SOURCES.ALL" to 90 * 1000L,
            "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY" to 0L,
            "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK" to 0L,
            "USER.ADS_MANAGER.CLAIMS" to 0L,
            "USER.AUTH.DEVICE.REGISTER" to 0L
        )

        private fun ttlFor(scope: String): Long = TTL_BY_SCOPE[scope] ?: 0L

        private fun cacheKey(method: String, url: String, body: Map<String, Any?>?): String {
            val bodyPart = body?.entries?.sortedBy { it.key }
                ?.joinToString("&") { "${it.key}=${it.value}" } ?: ""
            return "$method|$url|$bodyPart"
        }

        @Synchronized private fun cacheGet(key: String): JsonNode? {
            val e = jsonCache[key] ?: return null
            if (System.currentTimeMillis() > e.expiresAt) {
                jsonCache.remove(key); return null
            }
            return e.node
        }

        @Synchronized private fun cachePeek(key: String): JsonNode? = jsonCache[key]?.node

        @Synchronized private fun cachePut(key: String, node: JsonNode, ttl: Long) {
            jsonCache[key] = CacheEntry(node, System.currentTimeMillis() + ttl)
        }

        @Synchronized private fun cacheClear() = jsonCache.clear()

        private fun isCoolingDown(): Boolean = System.currentTimeMillis() < cooldownUntil

        private fun enterCooldown(seconds: Long, reason: String) {
            val until = System.currentTimeMillis() + seconds * 1000L
            if (until > cooldownUntil) {
                cooldownUntil = until
                Log.w(TAG, "cooldown ${seconds}s — $reason")
            }
        }

        private fun getCacheFile(): File {
            val dir = runCatching {
                CloudStreamApp.context?.cacheDir
            }.getOrNull() ?: runCatching {
                CommonActivity.activity?.cacheDir
            }.getOrNull() ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            return File(dir, "anime_rift_session_store.json")
        }

        private fun saveToDisk(data: Map<String, String>) {
            try { getCacheFile().writeText(ObjectMapper().writeValueAsString(data)) } catch (_: Exception) {}
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
            fid = null; firebaseToken = null; sessionKey = null; deviceId = null
            syntheticBuildId = null; syntheticDeviceInfoJson = null
            Log.d(TAG, "identity wiped")
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
            Triple("google", "Google", "Pixel 8 Pro"), Triple("google", "Google", "Pixel 8"),
            Triple("samsung", "samsung", "Galaxy S24 Ultra"), Triple("samsung", "samsung", "Galaxy S23"),
            Triple("xiaomi", "Xiaomi", "Xiaomi 14"), Triple("oneplus", "OnePlus", "OnePlus 12")
        )
        val selected = brands.random()
        val buildPrefix = when (selected.first) {
            "google" -> "AP2A.240805"; "samsung" -> "UP1A.231005"
            "xiaomi" -> "UKQ1.230917"; else -> "UP1A.231005"
        }
        val randomNum = "${Random().nextInt(899999) + 100000}.${(10..99).random()}"
        val buildId = "$buildPrefix.$randomNum"
        val uniqueTag = UUID.randomUUID().toString().substring(0, 8)
        val model = selected.third
        val brandLower = selected.first

        val deviceInfo = mapOf(
            "model" to model, "brand" to selected.first, "manufacturer" to selected.second,
            "device" to model.lowercase().replace(" ", "_"), "hardware" to "qcom",
            "product" to model.lowercase().replace(" ", "_"), "androidVersion" to "14",
            "sdkInt" to 34, "securityPatch" to "2025-01-01", "isPhysicalDevice" to true,
            "supportedAbis" to listOf("arm64-v8a"), "tags" to "release-keys", "type" to "user",
            "buildId" to buildId, "host" to "build-server-$uniqueTag",
            "fingerprint" to "$brandLower/$model/$brandLower:14/$buildId/V.1e9a_$uniqueTag:user/release-keys"
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
            headers.filterKeys { it.lowercase() != "range" && it.lowercase() != "host" }
                .forEach { (k, v) -> hb.add(k, v) }

            val client = app.baseClient.newBuilder()
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS).build()

            val req = okhttp3.Request.Builder().url(url).headers(hb.build()).head().build()

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

    private fun isCacheWorthy(scope: String, node: JsonNode): Boolean {
        return when (scope) {
            "ANIME.LIBRARY.HOME_CONTENT" -> (node.get("sections")?.size() ?: 0) > 0
            "ANIME.LIBRARY.DETAILS" -> node.get("item")?.isObject == true
            "ANIME.LIBRARY.EPISODES.ALL" -> (node.get("items")?.size() ?: 0) > 0
            "ANIME.LIBRARY.SEARCH" -> (node.get("items")?.size() ?: 0) > 0
            "ANIME.LIBRARY.EPISODES.SOURCES.ALL" -> (node.get("items")?.size() ?: 0) > 0
            else -> true
        }
    }

    private fun buildHeaders(integrityToken: String): Map<String, String> = mapOf(
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

    private suspend fun ensureInitialized(forceRefresh: Boolean = false, rotateIdentity: Boolean = false) {
        if (isCoolingDown() && !forceRefresh) {
            if (!fid.isNullOrEmpty() && !deviceId.isNullOrEmpty() && !firebaseToken.isNullOrEmpty()) return
            val remain = (cooldownUntil - System.currentTimeMillis()) / 1000
            throw Exception("cooldown active (${remain}s remaining)")
        }

        if (rotateIdentity) {
            rotationMutex.withLock {
                val now = System.currentTimeMillis()
                if (now - lastRotationAt < 30_000L && !fid.isNullOrEmpty()) return
                wipeIdentity()
                lastRotationAt = now
                firebaseFailed = false
                doTheActualInit()
            }
            return
        }

        if (!forceRefresh) {
            if (!fid.isNullOrEmpty() && !gatewayBaseUrl.isNullOrEmpty() && !firebaseToken.isNullOrEmpty()) return
            loadFromDisk()?.let { disk ->
                fid = disk["fid"]; firebaseToken = disk["firebaseToken"]
                gatewayBaseUrl = disk["gatewayBaseUrl"]?.takeIf { it.isNotEmpty() } ?: defaultApiBase
                deviceId = disk["deviceId"]; sessionKey = disk["sessionKey"]
                syntheticBuildId = disk["syntheticBuildId"]; syntheticDeviceInfoJson = disk["syntheticDeviceInfoJson"]
                if (!fid.isNullOrEmpty() && !firebaseToken.isNullOrEmpty()) return
            }
        }
        initMutex.withLock { doTheActualInit() }
    }

    private suspend fun doTheActualInit() {
        withContext(Dispatchers.IO) {
            if (cacheVersionSeen != CACHE_VERSION) { cacheClear(); cacheVersionSeen = CACHE_VERSION }
            if (credVersionSeen != CRED_VERSION) { wipeIdentity(); firebaseFailed = false; credVersionSeen = CRED_VERSION }

            if (!firebaseFailed) {
                try { registerFirebaseInstallation() } catch (e: Exception) {
                    logErr("firebase failed", e); firebaseFailed = true
                    fid = generateSyntheticFid(); firebaseToken = "synthetic:${System.currentTimeMillis()}"
                }
            } else { fid = generateSyntheticFid(); firebaseToken = "synthetic:${System.currentTimeMillis()}" }

            if (!firebaseFailed) {
                try { fetchRemoteConfig() } catch (e: Exception) { gatewayBaseUrl = defaultApiBase }
            } else { gatewayBaseUrl = defaultApiBase }
            if (gatewayBaseUrl.isNullOrEmpty()) gatewayBaseUrl = defaultApiBase

            registerDeviceRaw()
            saveToDisk(mapOf(
                "fid" to (fid ?: ""), "firebaseToken" to (firebaseToken ?: ""),
                "gatewayBaseUrl" to (gatewayBaseUrl ?: defaultApiBase), "deviceId" to (deviceId ?: ""),
                "sessionKey" to (sessionKey ?: ""), "syntheticBuildId" to (syntheticBuildId ?: "BP2A.250605.015"),
                "syntheticDeviceInfoJson" to (syntheticDeviceInfoJson ?: "{}")
            ))
            consecutiveFailures = 0; rotationAttempts = 0
        }
    }

    private fun registerFirebaseInstallation() {
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/installations"
        val payloadStr = """{"fid":"","appId":"$FIREBASE_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:19.1.0"}"""
        val request = okhttp3.Request.Builder().url(url)
            .post(gzipCompress(payloadStr).toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addHeader("Content-Type", "application/json").addHeader("Accept", "application/json")
            .addHeader("Content-Encoding", "gzip").addHeader("Cache-Control", "no-cache")
            .addHeader("X-Android-Package", ANDROID_PACKAGE).addHeader("X-Android-Cert", ANDROID_CERT)
            .addHeader("x-goog-api-key", FIREBASE_API_KEY)
            .addHeader("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
            .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Pro Build/AP2A.240805.015)")
            .build()

        app.baseClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception("Firebase HTTP ${response.code}")
            val json = mapper.readTree(body)
            fid = json.get("fid")?.asText() ?: throw Exception("Firebase missing fid")
            firebaseToken = json.get("authToken")?.get("token")?.asText() ?: throw Exception("Firebase missing token")
        }
    }

    private suspend fun fetchRemoteConfig() {
        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$FIREBASE_SENDER_ID/namespaces/firebase:fetch"
        val payload = mapOf("appVersion" to "3.13.5", "appInstanceIdToken" to (firebaseToken ?: ""),
            "appBuild" to "68", "appInstanceId" to (fid ?: ""), "appId" to FIREBASE_APP_ID,
            "platformVersion" to "34", "sdkVersion" to "23.0.1", "packageName" to ANDROID_PACKAGE)
        val headers = mapOf("X-Goog-Api-Key" to FIREBASE_API_KEY, "X-Android-Package" to ANDROID_PACKAGE,
            "X-Android-Cert" to ANDROID_CERT, "X-Goog-Firebase-Installations-Auth" to (firebaseToken ?: ""),
            "Content-Type" to "application/json")

        val response = app.post(url, headers = headers, json = payload)
        if (!response.isSuccessful || response.text.trimStart().startsWith("<")) { gatewayBaseUrl = defaultApiBase; return }
        gatewayBaseUrl = mapper.readTree(response.text).get("entries")?.get("anime_rift_android_gateway_base_url_v4")?.asText()?.replace("\"", "")?.takeIf { it.isNotEmpty() } ?: defaultApiBase
    }

    private suspend fun registerDeviceRaw() {
        val url = "$apiBase/auth/register/device"
        deviceId = "${fid ?: ""}:${generateFcmToken()}"
        val (buildId, deviceInfoJson) = generateSyntheticDevice()
        syntheticBuildId = buildId; syntheticDeviceInfoJson = deviceInfoJson

        val payload = mapOf("deviceId" to deviceId, "current_app_version" to "3.13.5", "device_os" to "android",
            "device_environment" to "production", "device_info" to deviceInfoJson,
            "install_source" to "IS_INSTALLED_FROM_OTHER_SOURCE", "deviceOsId" to buildId,
            "firebaseInstallationId" to fid, "apn_token" to null, "install_mode" to 2)

        val integrityToken = generateIntegrityToken("USER.AUTH.DEVICE.REGISTER")
        val headers = buildHeaders(integrityToken)

        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            val r = app.post(url, headers = headers, json = payload)
            Pair(r.code, r.text)
        } ?: throw Exception("register: timeout")

        val text = result.second
        if (result.first == 429 || text.contains("Too Many Requests")) {
            rateLimitHits++; last429At = System.currentTimeMillis()
            enterCooldown(60L, "register 429")
            throw Exception("register: HTTP 429")
        }

        val json = mapper.readTree(text)
        sessionKey = json.get("sessionKey")?.asText() ?: throw Exception("register: no sessionKey")
    }

    private suspend fun apiCall(url: String, scope: String, method: String = "GET", body: Map<String, Any?>? = null, isRetry: Boolean = false): JsonNode {
        val key = cacheKey(method.uppercase(), url, body)
        if (isCoolingDown()) { cachePeek(key)?.let { return it }; throw Exception("cooldown active") }
        ensureInitialized()

        val ttl = ttlFor(scope)
        if (ttl > 0) cacheGet(key)?.let { return it }

        throttleMutex.withLock {
            val gap = 250L + Random().nextInt(300)
            val since = System.currentTimeMillis() - lastRequestAt
            if (since in 0 until gap) delay(gap - since)
            lastRequestAt = System.currentTimeMillis()
        }

        val integrityToken = generateIntegrityToken(scope)
        val headers = buildHeaders(integrityToken)

        try {
            val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                val r = when (method.uppercase()) {
                    "POST" -> app.post(url, headers = headers, json = body)
                    "PUT" -> app.put(url, headers = headers, json = body)
                    else -> app.get(url, headers = headers)
                }
                Pair(r.code, r.text)
            } ?: throw Exception("timeout from $url")

            val code = result.first; val text = result.second
            if (code == 429 || text.contains("Too Many Requests")) {
                rateLimitHits++; last429At = System.currentTimeMillis()
                enterCooldown(60L, "apiCall 429")
                throw Exception("HTTP 429 from $url")
            }

            val parsed = mapper.readTree(text)
            val errorCode = parsed.get("errorCode")?.asText()
            if (!errorCode.isNullOrEmpty() && errorCode != "null") throw Exception("Server errorCode=$errorCode")
            if (code !in 200..299) throw Exception("HTTP $code from $url")

            if (ttl > 0 && isCacheWorthy(scope, parsed)) cachePut(key, parsed, ttl)
            consecutiveFailures = 0; rotationAttempts = 0
            return parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("429") || msg.contains("cooldown")) throw e
            val isAuthFail = msg.contains("401") || msg.contains("403") || msg.contains("ACCESS_DENIED")
            if (isAuthFail && !isRetry) {
                consecutiveFailures++; rotationAttempts++
                if (rotationAttempts > MAX_ROTATION_ATTEMPTS) { enterCooldown(300, "rotation limit"); throw e }
                if (consecutiveFailures >= 2) { cacheClear(); ensureInitialized(forceRefresh = true, rotateIdentity = true) }
                else { ensureInitialized(forceRefresh = true) }
                delay(1500)
                return apiCall(url, scope, method, body, isRetry = true)
            }
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        ensureInitialized()
        val json = apiCall("$apiBase/library/home_content?with_genres=true", "ANIME.LIBRARY.HOME_CONTENT")
        val homeLists = mutableListOf<HomePageList>()
        json.get("sections")?.forEach { section ->
            val title = section.get("sectionTitle")?.asText() ?: ""
            val list = mutableListOf<SearchResponse>()
            section.get("items")?.forEach { item ->
                list.add(newAnimeSearchResponse(name = item.get("title")?.asText() ?: "", url = "$defaultApiBase/library/details/${item.get("_id")?.asText()}") {
                    posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                    year = item.get("release_year")?.asInt()
                    score = item.get("myAnimeList_rating")?.asDouble()?.toFloat()?.let { Score.from10(it) }
                })
            }
            if (list.isNotEmpty()) homeLists.add(HomePageList(title, list, isHorizontalImages = true))
        }
        return newHomePageResponse(homeLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureInitialized()
        val url = "$apiBase/library/search?page=${(page - 1).coerceAtLeast(0)}&sort_by=release_year&sort_direction=1&text_direction=jp"
        return try {
            val json = apiCall(url, "ANIME.LIBRARY.SEARCH", method = "POST", body = mapOf("query" to query))
            val results = json.get("items")?.map { item ->
                newAnimeSearchResponse(name = item.get("title")?.asText() ?: "", url = "$defaultApiBase/library/details/${item.get("_id")?.asText()}") {
                    posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                    year = item.get("release_year")?.asInt()
                    score = item.get("myAnimeList_rating")?.asDouble()?.toFloat()?.let { Score.from10(it) }
                }
            } ?: emptyList()
            newSearchResponseList(results, json.get("hasNext")?.asBoolean() ?: false)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInitialized()
        val animeId = url.substringAfterLast("/")
        val json = apiCall("$apiBase/library/details/$animeId", "ANIME.LIBRARY.DETAILS")
        val item = json.get("item") ?: throw ErrorLoadingException("Failed to load anime details")

        val episodesList = mutableListOf<Episode>()
        val orderedData = mutableListOf<String>()
        try {
            val epJson = apiCall("$apiBase/library/episodes/$animeId?sort_by_latest=1", "ANIME.LIBRARY.EPISODES.ALL")
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
        } catch (e: CancellationException) { throw e } catch (e: Exception) { logErr("episodes", e) }

        episodeOrder = orderedData.toList()
        prefetchCache.keys.iterator().apply { while (hasNext()) if (!next().startsWith("$animeId|")) remove() }

        return newAnimeLoadResponse(name = item.get("title")?.asText() ?: "", url = url, type = TvType.Anime) {
            posterUrl = item.get("main_picture")?.asText()
            plot = item.get("synopsis")?.asText()
            year = item.get("release_year")?.asInt()
            score = item.get("myAnimeList_rating")?.asDouble()?.toFloat()?.let { Score.from10(it) }
            tags = item.get("genreLabels")?.mapNotNull { it.get("label")?.asText() }
            showStatus = when (item.get("release_status")?.asText()) {
                "on_going" -> ShowStatus.Ongoing; "finished" -> ShowStatus.Completed; else -> null
            }
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }

    private fun warmPrefetch(targets: List<String>) {
        if (isCoolingDown() || rateLimitHits > 0) return
        for (data in targets) {
            if (prefetchCache.containsKey(data) || prefetchInFlight.containsKey(data)) continue
            val job = prefetchScope.launch {
                try {
                    val p = data.split('|'); if (p.size < 2) return@launch
                    val links = mutableListOf<RLink>(); val subs = mutableListOf<SubtitleFile>()
                    resolveBundle(p[0], p[1], p.getOrNull(2)?.toIntOrNull() ?: 1, { links.add(it) }, { subs.add(it) })
                    if (links.isNotEmpty()) prefetchCache[data] = RBundle(links, subs, System.currentTimeMillis())
                } catch (e: CancellationException) { throw e } catch (e: Exception) { logErr("prefetch $data", e) }
                finally { prefetchInFlight.remove(data) }
            }
            prefetchInFlight[data] = job
        }
    }

    private suspend fun resolveBundle(animeId: String, episodeId: String, episodeNumber: Int, onLink: (RLink) -> Unit, onSub: (SubtitleFile) -> Unit) {
        val sourcesJson = apiCall("$apiBase/library/episode/sources", "ANIME.LIBRARY.EPISODES.SOURCES.ALL", method = "POST", body = mapOf("animeId" to animeId, "episodeId" to episodeId, "episode_number" to episodeNumber))
        val items = sourcesJson.get("items") ?: return

        synchronized(providerConfigs) {
            providerConfigs.clear()
            sourcesJson.get("configs")?.forEach { c -> c.get("server_key")?.asText()?.let { key -> providerConfigs[key] = c } }
        }

        val filteredItems = items.filter { src ->
            val subTitle = src.get("sub_title")?.asText() ?: ""
            subTitle.startsWith("ar_") || subTitle.isEmpty()
        }.ifEmpty { items.toList() }

        val semaphore = Semaphore(3)

        suspend fun processSource(src: JsonNode) {
            if (isCoolingDown()) return
            val hostId = src.get("_id")?.asText() ?: return
            val serverName = src.get("server_name")?.asText() ?: "Server"
            val provider = src.get("provider")?.asText() ?: ""
            val subTitle = src.get("sub_title")?.asText() ?: ""

            val qualitiesNode = src.get("qualities")
            val quality = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                qualitiesNode.map { it.asText() }.sortedByDescending { extractQuality(it) }.first()
            } else "720P"

            try {
                val canPlayJson = apiCall("$apiBase/library/episode/source/can_play", "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY", method = "POST", body = mapOf("episodeId" to episodeId, "hostId" to hostId, "is_download" to false, "event_name" to "play_episode_unlocked"))
                val sessionId = canPlayJson.get("sessionId")?.asText() ?: ""

                prefetchScope.launch {
                    try { apiCall("$apiBase/ads_manager/claim", "USER.ADS_MANAGER.CLAIMS", method = "PUT", body = mapOf("event_name" to "play_episode_unlocked", "hostId" to hostId, "episodeId" to episodeId, "transactionRef" to "${System.currentTimeMillis()}_${Random().nextInt(99999999)}", "reward_result" to "not_filled", "streamingServerKey" to provider, "is_optional" to false, "is_reward" to true)) } catch (_: Exception) {}
                }

                val directLinkJson = apiCall("$apiBase/library/episode/source/direct_link", "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK", method = "POST", body = mapOf("id" to hostId, "quality" to quality, "with_internal_player" to "1", "sessionId" to sessionId))

                directLinkJson.get("tracks")?.forEach { track ->
                    val trackUrl = track.get("file")?.asText() ?: track.get("url")?.asText()
                    val trackLang = track.get("label")?.asText() ?: track.get("language")?.asText() ?: "Arabic"
                    if (!trackUrl.isNullOrEmpty()) onSub(SubtitleFile(trackLang, trackUrl))
                }

                if (directLinkJson.get("url_response")?.asBoolean() == true) {
                    val videoUrl = directLinkJson.get("videoUrl")?.asText()
                    if (!videoUrl.isNullOrEmpty()) {
                        val customHeaders = mutableMapOf("User-Agent" to "libmpv", "Accept" to "*/*", "Range" to "bytes=0-", "Connection" to "close")
                        customHeaders["Host"] = try { java.net.URI(videoUrl).host } catch (_: Exception) { "media.rift-streamer.com" }
                        directLinkJson.get("http_headers")?.fields()?.forEach { (k, v) -> customHeaders[k] = v.asText() }

                        val resolvedUrl = if (videoUrl.contains("media.rift-streamer.com/videos/watch")) resolveRiftRedirect(videoUrl, customHeaders) ?: videoUrl else videoUrl
                        val finalHeaders = if (resolvedUrl != videoUrl) mapOf("User-Agent" to "libmpv", "Accept" to "*/*") else customHeaders

                        onLink(RLink(serverName, subTitle, quality, resolvedUrl, finalHeaders))
                    }
                } else if (directLinkJson.get("ticket_response")?.asBoolean() == true) {
                    val fileId = directLinkJson.get("fileId")?.asText() ?: ""
                    val ticket = directLinkJson.get("ticket")?.asText() ?: ""
                    if (fileId.isNotEmpty() && ticket.isNotEmpty()) {
                        delay((directLinkJson.get("wait_time")?.asLong() ?: 5L) * 1000 + 500)
                        val tapeApiUrl = "https://api.streamtape.com/file/dl?file=$fileId&ticket=$ticket"
                        var tapeRes = app.get(tapeApiUrl).parsed<JsonNode>()
                        if (tapeRes.get("status")?.asInt() != 200) { delay(2000); tapeRes = app.get(tapeApiUrl).parsed<JsonNode>() }
                        val tapeDirectUrl = tapeRes.get("result")?.get("url")?.asText()
                        if (!tapeDirectUrl.isNullOrEmpty()) onLink(RLink(serverName, subTitle, quality, tapeDirectUrl, mapOf("User-Agent" to "libmpv", "Accept" to "*/*")))
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { logErr("server $serverName/$quality", e) }
        }

        supervisorScope {
            filteredItems.map { src -> async(Dispatchers.IO) { semaphore.withPermit { processSource(src) } } }.awaitAll()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val parts = data.split('|'); if (parts.size < 2) return@withContext false
        val animeId = parts[0]; val episodeId = parts[1]; val episodeNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val emitted = AtomicInteger(0)

        val onLink: (RLink) -> Unit = { rl ->
            callback.invoke(newExtractorLink(source = name, name = "${rl.serverName} [${rl.subTitle}] - ${rl.quality}", url = rl.url) {
                this.quality = extractQuality(rl.quality); this.headers = rl.headers
            })
            emitted.incrementAndGet()
        }
        val onSub: (SubtitleFile) -> Unit = { subtitleCallback(it) }

        try {
            val cached = prefetchCache.remove(data)
            val valid = cached != null && (System.currentTimeMillis() - cached.createdAt) < PREFETCH_TTL_MS

            if (valid && cached!!.links.isNotEmpty()) {
                cached.subtitles.forEach(onSub); cached.links.forEach(onLink)
            } else {
                resolveBundle(animeId, episodeId, episodeNumber, onLink, onSub)
            }

            episodeOrder.indexOf(data).takeIf { it >= 0 }?.let { idx ->
                episodeOrder.getOrNull(idx + 1)?.let { warmPrefetch(listOf(it)) }
            }
            return@withContext emitted.get() > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logErr("loadLinks", e)
            return@withContext emitted.get() > 0
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
