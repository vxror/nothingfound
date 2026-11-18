package com.witanime

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class SessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()
    private fun baseDomain(host: String): String {
        val parts = host.lowercase().trim('.').split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val base = baseDomain(url.host)
        for (c in cookies) {
            val key = "$base|${c.name}"
            if (c.value.isEmpty()) store.remove(key) else store[key] = c
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val base = baseDomain(url.host)
        val now = System.currentTimeMillis()
        return store.entries.filter { (k, c) -> (k.startsWith("$base|") || c.matches(url)) && c.expiresAt > now }.map { it.value }
    }
    fun cookieValue(host: String, name: String): String? =
        store.entries.firstOrNull { it.key.startsWith("${baseDomain(host)}|") && it.value.name == name }?.value?.value
}

internal val sessionCookieJar = SessionCookieJar()

private val clientHints = Interceptor { chain ->
    val req = chain.request()
    val ua = req.header("User-Agent") ?: EXTRACTOR_UA
    val ver = Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1)
    if (ver != null && req.header("sec-ch-ua") == null) {
        val mobile = ua.contains("Mobile")
        chain.proceed(req.newBuilder()
            .header("sec-ch-ua", "\"Google Chrome\";v=\"$ver\", \"Chromium\";v=\"$ver\", \"Not_A Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", if (mobile) "?1" else "?0")
            .header("sec-ch-ua-platform", if (mobile) "\"Android\"" else "\"Windows\"")
            .build())
    } else chain.proceed(req)
}

/** DEDICATED client built from scratch — NOT app.baseClient.newBuilder().
 *  The app client carries its own interceptors/cookies/cache that injected a
 *  conflicting Laravel session → /sources POST 419. This mirrors the proven
 *  reference client exactly (own jar + hints + timeouts). */
internal val engineClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .cookieJar(sessionCookieJar)
        .addInterceptor(clientHints)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
}

internal val sessionClient: OkHttpClient get() = engineClient

internal val noRedirectEngineClient: OkHttpClient by lazy {
    engineClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
}
internal val noRedirectSessionClient: OkHttpClient get() = noRedirectEngineClient

internal fun sessionGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String? = try {
    val b = Request.Builder().url(url).header("User-Agent", EXTRACTOR_UA)
    if (referer != null) b.header("Referer", referer)
    headers.forEach { (k, v) -> b.header(k, v) }
    engineClient.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
} catch (_: Exception) { null }

internal fun sessionGetFull(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): Pair<String, String>? = try {
    val b = Request.Builder().url(url).header("User-Agent", EXTRACTOR_UA)
    if (referer != null) b.header("Referer", referer)
    headers.forEach { (k, v) -> b.header(k, v) }
    engineClient.newCall(b.build()).execute().use { r ->
        if (r.isSuccessful) (r.request.url.toString() to (r.body?.string() ?: "")) else null
    }
} catch (_: Exception) { null }

internal fun sessionPost(url: String, json: String, referer: String? = null, headers: Map<String, String> = emptyMap()): String? = try {
    val b = Request.Builder().url(url).header("User-Agent", EXTRACTOR_UA)
        .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
    if (referer != null) b.header("Referer", referer)
    headers.forEach { (k, v) -> b.header(k, v) }
    engineClient.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
} catch (_: Exception) { null }

internal fun isPlayable(url: String, referer: String?): Boolean = try {
    val b = Request.Builder().url(url).header("User-Agent", EXTRACTOR_UA).header("Range", "bytes=0-0")
    if (referer != null) b.header("Referer", referer)
    engineClient.newCall(b.build()).execute().use { it.isSuccessful }
} catch (_: Exception) { false }

internal fun injectWebViewCookies(url: String) {
    try {
        val raw = android.webkit.CookieManager.getInstance().getCookie(url) ?: return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val host = httpUrl.host
        raw.split(";").forEach { part ->
            val nv = part.trim().split("=", limit = 2)
            if (nv.size == 2 && nv[1].isNotBlank()) {
                val c = Cookie.Builder().domain(host).path("/").name(nv[0].trim()).value(nv[1].trim())
                    .expiresAt(System.currentTimeMillis() + 30 * 60_000L).build()
                sessionCookieJar.saveFromResponse(httpUrl, listOf(c))
            }
        }
    } catch (_: Exception) {}
}
