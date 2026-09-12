package com.kawaii

import android.util.Log
import com.lagradost.cloudstream3.app
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * VTT → styled ASS converter + local server.
 *
 * [v6] fixes:
 *  - URL ends with /styled.ass → the subtitle loader detects ASS format
 *    and applies the style block (v5 served extension-less URLs, which
 *    made the player fall back to default rendering)
 *  - RTL embedding: every dialogue segment is wrapped in RLE...PDF
 *    (U+202B/U+202C), the classic Arabic fansub trick — trailing
 *    punctuation (., !, ?) stays at the END of the line where it belongs
 */
object KawaiiSubs {

    private const val TAG = "KawaiiAnime"
    private val cache = ConcurrentHashMap<String, String>()

    private var server: ServerSocket? = null

    // bidi control chars — the RTL fix
    private const val RLE = '‫'   // U+202B: start RTL embedding
    private const val PDF = '‬'   // U+202C: close embedding
    // strip any pre-existing bidi marks from input so we never double-wrap
    private val BI_DI_CLEAN = Regex("[‪‫‬‭‮‎‏‏\u2066-\u2069\uFEFF\u200b-\u200d]")

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

    /** escape ASS specials, strip stale bidi marks, then RTL-wrap EVERY segment */
    private fun assText(text: String): String {
        val cleaned = BI_DI_CLEAN.replace(text, "")
            .replace("{", "\\{").replace("}", "}")
        return cleaned.split('\n')
            .filter { it.isNotBlank() || cleaned.contains('\n') }
            .joinToString("\\N") { RLE + it + PDF }
    }

    /** VTT timestamp (00:00:02.070) → ASS timestamp (0:00:02.07) */
    private fun vttToAssTime(t: String): String {
        val m = Regex("""(\d{2}):(\d{2}):(\d{2})\.(\d{3})""").find(t) ?: return t
        val (h, min, sec, ms) = m.destructured
        val cs = (ms.toInt() / 10).toString().padStart(2, '0')
        return "$h:$min:$sec.$cs"
    }

    /** convert full VTT body to styled ASS with RTL embedding */
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

    private fun fetchAndConvert(vttUrl: String): String? {
        cache[vttUrl]?.let { return it }
        return try {
            val req = Request.Builder().url(vttUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://kawaiianime.cc/")
                .header("Origin", "https://kawaiianime.cc")
                .header("Accept", "*/*")
                .build()
            app.baseClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) { Log.d(TAG, "sub fetch fail ${res.code}"); return null }
                val body = res.body?.string() ?: return null
                if (!body.contains("WEBVTT")) return null
                val ass = vttToAss(body)
                cache[vttUrl] = ass
                Log.d(TAG, "sub converted: ${ass.length} chars")
                ass
            }
        } catch (e: Exception) { Log.e(TAG, "sub convert: ${e.message}"); null }
    }

    private fun ensureServer(): Int? {
        if (server != null && server?.isClosed == false) return server?.localPort
        return try {
            val s = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
            server = s
            Thread {
                while (!s.isClosed) {
                    try {
                        val socket = s.accept()
                        Thread {
                            try { handle(socket) } catch (_: Exception) {}
                            finally { try { socket.close() } catch (_: Exception) {} }
                        }.apply { isDaemon = true }.start()
                    } catch (_: Exception) { if (s.isClosed) break }
                }
            }.apply { isDaemon = true; name = "KawaiiSubs" }.start()
            s.localPort
        } catch (e: Exception) { Log.e(TAG, "sub server: ${e.message}"); null }
    }

    private fun handle(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val q = parts[1]
        // [v6] route is /styled.ass — the .ass suffix is what makes the
        // player's subtitle loader treat this as ASS and apply the styles
        if (!q.startsWith("/styled.ass")) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        // drain headers
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
        }
        val params = q.substringAfter('?', "").split('&')
        var vttUrl: String? = null
        var lang = "Arabic"
        for (p in params) {
            when {
                p.startsWith("u=") -> vttUrl = java.net.URLDecoder.decode(p.substring(2), "UTF-8")
                p.startsWith("lang=") -> lang = java.net.URLDecoder.decode(p.substring(5), "UTF-8")
            }
        }
        if (vttUrl == null) {
            output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val ass = fetchAndConvert(vttUrl)
        if (ass == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val bytes = ass.toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-subtitle-ass; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }

    /**
     * the provider's entry point. URL ends in /styled.ass so the player
     * detects ASS format and renders the style block.
     */
    fun styledSubtitleUrl(vttUrl: String, lang: String): String? {
        val port = ensureServer() ?: return null
        return try {
            "http://127.0.0.1:$port/styled.ass?u=" + URLEncoder.encode(vttUrl, "UTF-8") +
                "&lang=" + URLEncoder.encode(lang, "UTF-8")
        } catch (_: Exception) { null }
    }
}
