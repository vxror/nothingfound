package com.kawaii

import android.util.Log
import com.lagradost.cloudstream3.app
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * KawaiiSubs
 *
 * Full replacement subtitle engine:
 * - Fetches raw VTT with the correct site headers.
 * - Uses /api/translate-sub to produce Arabic from the English VTT.
 * - Converts VTT to styled ASS with the field-proven RTL wrapping.
 * - Serves the generated ASS from a local loopback HTTP server.
 * - The generated URL ends with .ass so players can infer the format.
 */
object KawaiiSubs {

    private const val TAG = "KawaiiSubs"
    private const val MAIN_URL = "https://kawaiianime.cc"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

    private val assCache = ConcurrentHashMap<String, String>()
    private var server: ServerSocket? = null
    private val serverLock = Any()

    // Exact RTL codepoints used by the known-good fix_rtl2.py logic.
    private const val RLE = '\u202B'
    private const val PDF = '\u202C'

    private val ASS_HEADER = """
        [Script Info]
        Title: Kawaii Styled Subs
        ScriptType: v4.00+
        PlayResX: 620
        PlayResY: 374
        ScaledBorderAndShadow: yes

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Bahij Nassim,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,95,102,0,0,1,1.2,0,2,15,15,15,1
        Style: NOTE,The Year of The Camel ExtraBold,22,&H00FFFFFF,&H0300FFFF,&H00140091,&H02000000,0,0,0,0,95,95,0,0,1,0.8,0,8,8,8,8,1
        Style: song,DG Jory,32,&H00FFFFFF,&H000000FF,&H00893519,&H00000000,-1,0,0,0,95,96,0,0,1,1,0,8,10,10,10,1
        Style: Sign,The Year of The Camel ExtraBold,15,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,8,15,15,8,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    // ───────────────────────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────────────────────

    /**
     * Styles the raw VTT without translation.
     * Used for the original English track.
     */
    fun rawStyledUrl(vttUrl: String): String? {
        val vtt = fetchVtt(vttUrl) ?: return null
        val ass = vttToAss(vtt)
        return serveAss(ass)
    }

    /**
     * Translates the raw VTT using /api/translate-sub, then styles it.
     * Returns null if translation does not produce a valid WEBVTT payload.
     */
    fun translatedStyledUrl(
        vttUrl: String,
        showId: String,
        epNum: String,
        titles: List<String>
    ): String? {
        val ass = fetchTranslatedAss(vttUrl, showId, epNum, titles) ?: return null
        return serveAss(ass)
    }

    // ───────────────────────────────────────────────────────────────
    // Network
    // ───────────────────────────────────────────────────────────────

    private fun vttRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "$MAIN_URL/")
            .header("DNT", "1")
            .header("Accept", "*/*")
            .header("sec-ch-ua", "\"Chromium\";v=\"148\", \"Quetta\";v=\"148\", \"Not/A)Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .build()
    }

    private fun translationRequest(url: String, showId: String, epNum: String): Request {
        return Request.Builder()
            .url(url)
            .header("accept", "*/*")
            .header("accept-language", "en-US,en;q=0.9")
            .header("dnt", "1")
            .header("priority", "u=1, i")
            .header("referer", "$MAIN_URL/watch/$showId?num=$epNum")
            .header("sec-ch-ua", "\"Chromium\";v=\"148\", \"Quetta\";v=\"148\", \"Not/A)Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .header("sec-gpc", "1")
            .header("user-agent", UA)
            .header("Origin", MAIN_URL)
            .build()
    }

    private fun fetchVtt(url: String): String? {
        return try {
            app.baseClient.newCall(vttRequest(url)).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.d(TAG, "fetchVtt failed HTTP ${res.code} for $url")
                    return null
                }

                val body = res.body?.string() ?: return null
                if (body.contains("WEBVTT") && body.contains("-->")) body else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchVtt exception: ${e.message}")
            null
        }
    }

    private fun fetchTranslatedAss(
        vttUrl: String,
        showId: String,
        epNum: String,
        titles: List<String>
    ): String? {
        val candidates = titleCandidates(titles)
        if (candidates.isEmpty()) {
            Log.d(TAG, "No title candidates for translation API")
            return null
        }

        val encodedVtt = try {
            URLEncoder.encode(vttUrl, "UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "URL encode failed: ${e.message}")
            return null
        }

        for (title in candidates) {
            try {
                val encodedTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
                val url = "$MAIN_URL/api/translate-sub?url=$encodedVtt&v=$epNum&title=$encodedTitle"

                app.baseClient.newCall(translationRequest(url, showId, epNum)).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.d(TAG, "translate HTTP ${res.code} title='$title'")
                        return@use
                    }

                    val body = res.body?.string() ?: return@use
                    if (body.contains("WEBVTT") && body.contains("-->")) {
                        Log.d(TAG, "translate success title='$title' len=${body.length}")
                        return vttToAss(body)
                    }

                    Log.d(TAG, "translate response not VTT title='$title' len=${body.length}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "translate exception title='$title': ${e.message}")
            }
        }

        return null
    }

    private fun titleCandidates(titles: List<String>): List<String> {
        val out = ArrayList<String>()

        for (raw in titles) {
            val t = raw.trim()
            if (t.isBlank()) continue

            out.add(t)

            val upper = t.uppercase()
            if (upper != t) out.add(upper)

            val collapsed = t.replace(Regex("\\s+"), " ")
            if (collapsed != t) out.add(collapsed)

            val collapsedUpper = collapsed.uppercase()
            if (collapsedUpper != collapsed) out.add(collapsedUpper)
        }

        return out.distinct().take(8)
    }

    // ───────────────────────────────────────────────────────────────
    // VTT → ASS
    // ───────────────────────────────────────────────────────────────

    private fun cleanBidi(s: String): String {
        val sb = StringBuilder(s.length)

        for (c in s) {
            val cp = c.code
            val drop = cp == 0x200e ||
                cp == 0x200f ||
                cp in 0x202a..0x202e ||
                cp in 0x2066..0x2069 ||
                cp == 0xfeff ||
                cp in 0x200b..0x200d

            if (!drop) sb.append(c)
        }

        return sb.toString()
    }

    private fun styleFor(text: String): String {
        val t = text.trim()

        return when {
            t.contains("♪") || t.contains("♫") || t.startsWith("#") -> "song"
            t.startsWith("(") || t.startsWith("[") -> "NOTE"
            else -> "Default"
        }
    }

    private fun assText(text: String): String {
        val cleaned = cleanBidi(text)
            .replace("{", "\\{")
            .replace("}", "}")

        return cleaned
            .split('\n')
            .filter { it.isNotEmpty() }
            .map { RLE + it + PDF }
            .joinToString("\\N")
    }

    private fun vttToAssTime(t: String): String {
        val m = Regex("""(\d{2}):(\d{2}):(\d{2})\.(\d{3})""").find(t) ?: return t
        val (h, min, sec, ms) = m.destructured
        val cs = (ms.toInt() / 10).toString().padStart(2, '0')
        return "$h:$min:$sec.$cs"
    }

    fun vttToAss(vtt: String): String {
        val sb = StringBuilder()
        sb.append(ASS_HEADER).append('\n')

        val lines = vtt.replace("\r\n", "\n").split('\n')
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    val start = vttToAssTime(times[0].trim())
                    val end = vttToAssTime(times[1].trim().substringBefore(' '))

                    val textLines = ArrayList<String>()
                    i++

                    while (i < lines.size && lines[i].isNotBlank()) {
                        textLines.add(lines[i].trim())
                        i++
                    }

                    val text = textLines.joinToString("\n")
                    if (text.isNotEmpty()) {
                        val style = styleFor(text)
                        sb.append("Dialogue: 0,$start,$end,$style,,0,0,0,,${assText(text)}\n")
                    }

                    continue
                }
            }

            i++
        }

        return sb.toString()
    }

    // ───────────────────────────────────────────────────────────────
    // Local ASS server
    // ───────────────────────────────────────────────────────────────

    private fun serveAss(ass: String): String? {
        if (ass.isBlank()) return null

        trimCacheIfNeeded()

        val id = UUID.randomUUID().toString()
        assCache[id] = ass

        val port = ensureServer() ?: return null
        return "http://127.0.0.1:$port/sub/$id.ass"
    }

    private fun trimCacheIfNeeded() {
        if (assCache.size <= 120) return

        try {
            assCache.keys.take(40).forEach { assCache.remove(it) }
        } catch (_: Exception) {}
    }

    private fun ensureServer(): Int? {
        synchronized(serverLock) {
            val existing = server
            if (existing != null && !existing.isClosed) {
                return existing.localPort
            }

            return try {
                val s = ServerSocket(0)
                server = s

                Thread {
                    while (!s.isClosed) {
                        try {
                            val socket = s.accept()
                            Thread {
                                handle(socket)
                            }.apply {
                                isDaemon = true
                                name = "KawaiiSubConn-${System.currentTimeMillis()}"
                            }.start()
                        } catch (_: Exception) {
                            if (s.isClosed) break
                        }
                    }
                }.apply {
                    isDaemon = true
                    name = "KawaiiSubServer"
                }.start()

                s.localPort
            } catch (e: Exception) {
                Log.e(TAG, "ensureServer failed: ${e.message}")
                null
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val path = parts[1]

            // Drain headers.
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
            }

            if (!path.startsWith("/sub/") || !path.contains(".ass")) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            val id = path
                .substringAfter("/sub/", "")
                .substringBefore("?")
                .substringBefore(".ass")

            val ass = assCache[id]
            if (id.isBlank() || ass == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            val bytes = ass.toByteArray(Charsets.UTF_8)

            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-subtitle-ass; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Connection: close\r\n\r\n"

            output.write(header.toByteArray())
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "handle exception: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
