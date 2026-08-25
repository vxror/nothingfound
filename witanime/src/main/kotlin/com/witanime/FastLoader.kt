package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * ⚡ FastLoader — the performance engine
 * 
 * SAFE optimizations only:
 * - Episode data cache (instant revisits, no risk)
 * - Smart prioritization (reorders, doesn't skip)
 * - Reliability tracking (statistics only)
 * 
 * NO health checks that could miss slow servers.
 */
object FastLoader {

    // ═══ Episode Data Cache ═══
    private data class CachedEpisode(
        val servers: List<ServerInfo>,
        val timestamp: Long
    )

    data class ServerInfo(
        val id: String,
        val label: String,
        val url: String,
        val quality: String?,
        val priority: Int
    )

    private val episodeCache = ConcurrentHashMap<String, CachedEpisode>()
    private const val EPISODE_CACHE_TTL = 5 * 60 * 1000L  // 5 minutes

    // ═══ Server Reliability Tracking ═══
    private val serverSuccessCount = ConcurrentHashMap<String, Int>()
    private val serverFailCount = ConcurrentHashMap<String, Int>()

    fun recordSuccess(label: String) {
        serverSuccessCount[label] = (serverSuccessCount[label] ?: 0) + 1
    }

    fun recordFailure(label: String) {
        serverFailCount[label] = (serverFailCount[label] ?: 0) + 1
    }

    fun getReliability(label: String): Double {
        val success = serverSuccessCount[label] ?: 0
        val fail = serverFailCount[label] ?: 0
        if (success + fail == 0) return 0.5  // unknown = neutral
        return success.toDouble() / (success + fail)
    }

    /**
     * Sort servers by priority + reliability
     * Proven fast servers go first — but ALL servers are included
     */
    fun prioritizeServers(servers: List<ServerInfo>): List<ServerInfo> {
        return servers.sortedByDescending { server ->
            val reliability = getReliability(server.label)
            server.priority * 10 + (reliability * 100).toInt()
        }
    }

    // ═══ Episode Cache ═══

    fun getCachedServers(episodeUrl: String): List<ServerInfo>? {
        val cached = episodeCache[episodeUrl] ?: return null
        val age = System.currentTimeMillis() - cached.timestamp
        return if (age < EPISODE_CACHE_TTL) cached.servers else {
            episodeCache.remove(episodeUrl)
            null
        }
    }

    fun cacheServers(episodeUrl: String, servers: List<ServerInfo>) {
        // Prevent memory bloat — keep max 30 episodes
        if (episodeCache.size > 30) {
            val oldest = episodeCache.entries.sortedBy { it.value.timestamp }.firstOrNull()?.key
            oldest?.let { episodeCache.remove(it) }
        }
        episodeCache[episodeUrl] = CachedEpisode(servers, System.currentTimeMillis())
    }

    // ═══ Fast Episode Parsing ═══

    /**
     * Parse the episode page and extract server info — with caching
     * Returns null if page couldn't be parsed (falls back to old method)
     */
    suspend fun parseEpisode(
        episodeUrl: String,
        html: String,
        userAgent: String
    ): List<ServerInfo>? {
        // Check cache first — ⚡ instant on revisit
        getCachedServers(episodeUrl)?.let {
            println("WitAnimeDebug: FastLoader CACHE HIT (${it.size} servers)")
            return it
        }

        // Parse _zT/_zV
        fun b64Bytes(i: String?) = if (i.isNullOrBlank()) ByteArray(0) else try {
            Base64.decode(i, Base64.DEFAULT)
        } catch (_: Exception) { ByteArray(0) }

        val zT = Regex("""_zT\s*=\s*"([A-Za-z0-9+/=]{20,})"""").find(html)?.groupValues?.get(1)
        val zV = Regex("""_zV\s*=\s*"([A-Za-z0-9+/=]{20,})"""").find(html)?.groupValues?.get(1)
        val resArr = zT?.let { try { JSONArray(String(b64Bytes(it))) } catch (_: Exception) { null } }
        val cfgArr = zV?.let { try { JSONArray(String(b64Bytes(it))) } catch (_: Exception) { null } }

        if (resArr == null) return null

        // Find server anchors
        val servers = mutableListOf<ServerInfo>()
        Regex("""(<a[^>]+class=["'][^"']*server-link[^"']*["'][^>]*>.*?</a>)""", 
            RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            val tag = m.groupValues[1]
            val sid = Regex("""data-server-id\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
            val label = Regex("""<span[^>]+class=["'][^"']*ser[^"']*["'][^>]*>(.*?)</span>""", 
                RegexOption.DOT_MATCHES_ALL).find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
            if (sid != null) {
                val idx = sid.toIntOrNull() ?: -1
                if (idx in 0 until resArr.length()) {
                    // Decode the URL (same logic as decodeWatch)
                    val raw = resArr.optString(idx)
                    val cfg = cfgArr?.optJSONObject(idx)

                    val cleaned = raw.replace(Regex("[^A-Za-z0-9+/=]"), "").reversed()
                    val decoded = b64Bytes(cleaned)
                    if (decoded.isNotEmpty()) {
                        var off = 0
                        if (cfg != null) {
                            val kIdx = String(b64Bytes(cfg.optString("k", ""))).trim().toIntOrNull() ?: 0
                            val d = cfg.optJSONArray("d")
                            if (d != null && kIdx in 0 until d.length()) off = d.optInt(kIdx)
                        }
                        val sliced = if (off in 1 until decoded.size) decoded.copyOf(decoded.size - off) else decoded
                        val url = String(sliced, Charsets.UTF_8).replace(Regex("[\\x00\\u0000]"), "").trim()

                        if (url.startsWith("http")) {
                            val quality = when {
                                label?.contains("FHD") == true -> "FHD"
                                label?.contains("HD") == true -> "HD"
                                else -> null
                            }

                            servers.add(ServerInfo(
                                id = sid,
                                label = label ?: "server-$sid",
                                url = url,
                                quality = quality,
                                priority = getPriority(url)
                            ))
                        }
                    }
                }
            }
        }

        if (servers.isNotEmpty()) {
            cacheServers(episodeUrl, servers)
            println("WitAnimeDebug: FastLoader cached ${servers.size} servers")
        }
        return servers
    }

    private fun getPriority(url: String): Int {
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return when {
            host.contains("mp4upload") -> 100  // always fast, always works
            host.contains("videa.hu") || host.contains("videas.fr") -> 90  // proven fast
            host.contains("4shared") -> 80
            host.contains("mega.nz") -> 70
            host.contains("streamwish") || host.contains("wish") -> 60
            host.contains("dood") -> 50
            host.contains("ok.ru") -> 40
            host.contains("yonaplay") -> 30
            host.contains("dotplay") || host.contains("soraplay") -> 20
            else -> 10
        }
    }

    fun getStats(): String {
        return "cache=${episodeCache.size} episodes, tracking=${serverSuccessCount.size + serverFailCount.size} servers"
    }
}
