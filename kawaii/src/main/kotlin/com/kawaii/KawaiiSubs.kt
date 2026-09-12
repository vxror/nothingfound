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
 * Bulletproof VTT → styled ASS converter + pre-cached local server.
 * Pre-fetches the VTT to eliminate ExoPlayer timeouts.
 * Binds to 0.0.0.0 to prevent IPv4/IPv6 loopback mismatches.
 */
object KawaiiSubs {

    private const val TAG = "KawaiiSubs"
    private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"
    private const val MAIN_URL = "https://kawaiianime.cc"

    // Memory cache for pre-converted ASS files
    private val assCache = ConcurrentHashMap<String, String>()
    private var server: ServerSocket? = null
    private val serverLock = Any()

    // ── bidi constants ──
    private const val RLE = '\u202B'
    private const val PDF = '\u202C'

    private fun cleanBidi(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val cp = c.code
            val drop = (cp == 0x200e || cp == 0x200f ||
                    (cp in 0x202a..0x202e) ||
                    (cp in 0x2066..0x2069) ||
                    cp == 0xfeff ||
                    (cp in 0x200b..0x200d))
            if (!drop) sb.append(c)
        }
        return sb.toString()
    }

    private val ASS_HEADER = """
        [Script Info]
        Title: Styled Subs
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
            .replace("{", "\\{").replace("}", "}")
        val segs = cleaned.split('\n')
            .filter { it.isNotEmpty() }
            .map { RLE + it + PDF }
        return segs.joinToString("\\N")
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

    private fun fetchVtt(url: String): String? {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "$MAIN_URL/")
                .header("Origin", MAIN_URL)
                .header("Accept", "*/*")
                .build()
            app.baseClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                return if (body.contains("WEBVTT")) body else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchVtt failed", e)
            return null
        }
    }

    private fun fetchAndConvert(vttUrl: String, showId: String, epNum: String): String? {
        // 1. Try translation API first (crucial for Arabic subs on this site)
        try {
            val encUrl = URLEncoder.encode(vttUrl, "UTF-8")
            val transUrl = "$MAIN_URL/api/translate-sub?url=$encUrl&v=$epNum&title=Anime$showId"
            val transReq = Request.Builder().url(transUrl)
                .header("User-Agent", UA)
                .header("Referer", "$MAIN_URL/")
                .header("Origin", MAIN_URL)
                .header("Accept", "*/*")
                .build()
            app.baseClient.newCall(transReq).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (body != null && body.contains("WEBVTT")) {
                        return vttToAss(body)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Translation API failed, falling back to direct VTT: ${e.message}")
        }

        // 2. Fallback to direct VTT URL
        val vtt = fetchVtt(vttUrl) ?: return null
        return vttToAss(vtt)
    }

    private fun ensureServer(): Int? {
        synchronized(serverLock) {
            if (server != null && !server!!.isClosed) return server!!.localPort
            try {
                // Bind to 0.0.0.0 (all interfaces) to prevent IPv6/IPv4 loopback mismatches
                val s = ServerSocket(0)
                server = s
                Thread {
                    while (!s.isClosed) {
                        try {
                            val socket = s.accept()
                            Thread { handle(socket) }.apply { isDaemon = true }.start()
                        } catch (_: Exception) {
                            if (s.isClosed) break
                        }
                    }
                }.apply { isDaemon = true; name = "KawaiiSubServer" }.start()
                return s.localPort
            } catch (e: Exception) {
                Log.e(TAG, "Server startup failed", e)
                return null
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

            // Consume headers
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
            }

            if (!path.startsWith("/sub/")) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            val id = path.substringAfter("/sub/").substringBefore("?")
            val ass = assCache[id]
            if (ass == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            val bytes = ass.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-subtitle-ass; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            output.write(response.toByteArray())
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Socket handle error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun styledSubtitleUrl(vttUrl: String, lang: String, showId: String, epNum: String): String? {
        // Pre-fetch and convert synchronously to avoid ExoPlayer timeout
        val ass = fetchAndConvert(vttUrl, showId, epNum) ?: return null
        val id = UUID.randomUUID().toString()
        assCache[id] = ass

        val port = ensureServer() ?: return null
        return "http://127.0.0.1:$port/sub/$id"
    }
}
