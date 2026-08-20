package com.animewitcher

import com.lagradost.cloudstream3.app
import java.net.URI

object ZenAdaptivePicker {
    data class Variant(val bandwidth: Long, val height: Int, val url: String)
    private const val SAFETY = 0.8; private const val PROBE_TIMEOUT_MS = 8000L; private const val PROBE_RANGE_END = "262143"

    internal fun parseVariants(masterText: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        val lines = masterText.lines(); var i = 0
        while (i < lines.size) {
            val line = lines[i]; val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
            if (line.startsWith("#EXT-X-STREAM-INF") && bandwidth != null) {
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                var j = i + 1; while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) { variants.add(Variant(bandwidth, height, lines[j].trim())); i = j }
            }
            i++
        }
        return variants
    }

    internal fun resolveUrl(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return path
        val origin = buildString { append(uri.scheme ?: "https"); append("://"); append(uri.host.orEmpty()); val port = uri.port; if (port > 0 && port != 80 && port != 443) append(":$port") }
        if (path.startsWith("/")) return origin + path
        return origin + uri.path.orEmpty().substringBeforeLast('/', "") + "/" + path
    }

    internal fun chooseVariant(variants: List<Variant>, measuredBytesPerSec: Double): Variant? {
        if (variants.isEmpty()) return null
        val capacity = measuredBytesPerSec * 8 * SAFETY; val sorted = variants.sortedBy { it.bandwidth }
        return sorted.lastOrNull { it.bandwidth <= capacity } ?: sorted.minByOrNull { it.bandwidth }
    }

    suspend fun selectBestVariant(masterUrl: String, headers: Map<String, String>): String? {
        return try {
            val masterText = app.get(masterUrl, timeout = PROBE_TIMEOUT_MS, headers = headers).text
            val variants = parseVariants(masterText); if (variants.isEmpty()) return null
            val sorted = variants.sortedBy { it.bandwidth }; val probeVariant = sorted[sorted.size / 2]
            val probePlaylistUrl = resolveUrl(masterUrl, probeVariant.url)
            val playlistText = app.get(probePlaylistUrl, timeout = PROBE_TIMEOUT_MS, headers = headers).text
            val firstSegment = playlistText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: return null
            val segmentUrl = resolveUrl(probePlaylistUrl, firstSegment); val start = System.currentTimeMillis()
            val probe = app.get(segmentUrl, timeout = PROBE_TIMEOUT_MS, headers = headers + ("Range" to "bytes=0-$PROBE_RANGE_END"))
            if (probe.code != 200 && probe.code != 206) return null
            val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            val bytes = probe.body.bytes().size.toDouble(); probe.body.close()
            val speed = bytes / (elapsedMs / 1000.0); val chosen = chooseVariant(variants, speed) ?: return null
            resolveUrl(masterUrl, chosen.url)
        } catch (e: Exception) { null }
    }
}
