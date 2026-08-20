package com.animewitcher

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

object ZenProxyRescue {
    
    // 🛠️ READS FROM UI SETTINGS
    private fun getProxyHost(): String {
        return try {
            val ctx = APIHolder.context ?: return "issa-proxy.yazankal.workers.dev"
            // Cloudstream stores plugin settings in SharedPreferences
            val prefs = ctx.getSharedPreferences("plugin_prefs", Context.MODE_PRIVATE)
            prefs.getString("proxy_host", "issa-proxy.yazankal.workers.dev") ?: "issa-proxy.yazankal.workers.dev"
        } catch (e: Exception) {
            "issa-proxy.yazankal.workers.dev"
        }
    }

    private val PROXY_BASE get() = "https://${getProxyHost()}"

    // 🛡️ NEVER PROXY THESE (They work directly or are local)
    private val SKIP_HOSTS = listOf(
        "pixeldrain.com", "pixeldrain.eu.cc", "cdn.pixeldrain.eu.cc",
        "127.0.0.1", "localhost", "mega.nz", "mega.co.nz"
    )

    private fun shouldSkip(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return true
        return SKIP_HOSTS.any { host.contains(it) }
    }

    private fun proxyMp4(url: String, referer: String? = null): String {
        val enc = URLEncoder.encode(url, "UTF-8")
        val sb = StringBuilder("$PROXY_BASE/mp4-proxy?url=$enc")
        referer?.let { sb.append("&referer=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    private fun proxyM3u8(url: String, headers: Map<String, String> = emptyMap(), referer: String? = null): String {
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

    /**
     * 🚑 EMERGENCY RESCUE: Try to extract video using proxy as a last resort
     */
    suspend fun rescue(
        url: String,
        sourceName: String,
        quality: Int,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (shouldSkip(url)) return false

        return try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            val response = app.get(url, headers = headers, referer = referer, allowRedirects = true)
            val html = response.text

            val patterns = listOf(
                Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""", RegexOption.IGNORE_CASE),
                Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)""", RegexOption.IGNORE_CASE),
                Regex("""(?:file|source|src)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            )

            var found = false
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val videoUrl = match.groupValues.last().replace("\\/", "/").replace("\\u0026", "&")
                    if (videoUrl.length > 20 && videoUrl.startsWith("http") && !shouldSkip(videoUrl)) {
                        
                        val isM3u8 = videoUrl.contains(".m3u8", true)
                        val proxiedUrl = if (isM3u8) proxyM3u8(videoUrl, headers, referer) else proxyMp4(videoUrl, referer)
                        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(newExtractorLink(
                            source = sourceName,
                            name = "$sourceName (Proxy Rescue)",
                            url = proxiedUrl,
                            type = type
                        ) {
                            this.quality = quality
                            this.referer = ""
                        })
                        found = true
                        break
                    }
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
