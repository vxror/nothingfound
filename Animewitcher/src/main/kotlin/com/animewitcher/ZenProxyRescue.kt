package com.animewitcher

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

object ZenProxyRescue {
    private const val PROXY_HOST = "issa-proxy.yazankal.workers.dev"
    private const val PROXY_BASE = "https://$PROXY_HOST"

    // 🛡️ NEVER PROXY THESE (They work directly or are local)
    private val SKIP_HOSTS = listOf(
        "pixeldrain.com", "pixeldrain.eu.cc", "cdn.pixeldrain.eu.cc",
        "127.0.0.1", "localhost", "mega.nz", "mega.co.nz"
    )

    fun shouldSkip(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return true
        return SKIP_HOSTS.any { host.contains(it) }
    }

    // 🔄 PROXY MP4
    fun mp4(url: String, referer: String? = null, origin: String? = null): String {
        val enc = URLEncoder.encode(url, "UTF-8")
        val sb = StringBuilder("$PROXY_BASE/mp4-proxy?url=$enc")
        referer?.let { sb.append("&referer=").append(URLEncoder.encode(it, "UTF-8")) }
        origin?.let { sb.append("&origin=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    // 🔄 PROXY M3U8 (Worker automatically rewrites TS segments)
    fun m3u8(url: String, headers: Map<String, String> = emptyMap(), referer: String? = null): String {
        val enc = URLEncoder.encode(url, "UTF-8")
        val sb = StringBuilder("$PROXY_BASE/m3u8-proxy?url=$enc")
        if (headers.isNotEmpty()) {
            val json = JSONObject()
            headers.forEach { (k, v) -> json.put(k, v) }
            sb.append("&headers=").append(URLEncoder.encode(json.toString(), "UTF-8"))
        }
        referer?.let { sb.append("&referer=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }
}
