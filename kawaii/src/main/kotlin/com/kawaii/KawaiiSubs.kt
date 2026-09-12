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
 * VTT → ASS converter + local server. Fetches the site's Arabic VTT,
 * restyles it with the operator's Arabic typography (Bahij Nassim dialogue,
 * DG Jory songs, sign styles), serves it as .ass on 127.0.0.1.
 *
 * Font note: styles reference fonts by family name — install the actual
 * .ttf/.otf files into CloudStream's font dir
 * (/data/data/com.lagradost.cloudstream3/files/fonts/) once, and the
 * renderer resolves them. Without them, styling (colors/outline/size)
 * still applies via system fallback; the typeface falls back too.
 */
object KawaiiSubs {

    private const val TAG = "KawaiiAnime"
    private val cache = ConcurrentHashMap<String, String>()   // vtt url -> ass text

    private var server: ServerSocket? = null

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

    /** classify a dialogue line into a style */
    private fun styleFor(text: String): String {
        val t = text.trim()
        return when {
            t.contains("♪") || t.contains("♫") || t.startsWith("#") -> "song"
            t.startsWith("(") || t.startsWith("[") -> "NOTE"
            else -> "Default"
        }
    }

    /** escape ASS text specials (braces and line breaks) */
    private fun assEscape(s: String): String =
        s.replace("{", "\\{").replace("}", "\\}")
            .replace("\n", "\\N")

    /** VTT timestamp (00:00:02.070) → ASS timestamp (0:00:02.07) */
    private fun vttToAssTime(t: String): String {
        val m = Regex("""(\d{2}):(\d{2}):(\d{2})\.(\d{3})""").find(t) ?: return t
        val (h, min, sec, ms) = m.destructured
        val cs = (ms.toInt() / 10).toString().padStart(2, '0')
        return "$h:$min:$sec.$cs"
    }

    /** convert full VTT body to ASS events */
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
                        sb.append("Dialogue: 0,$start,$end,$style,,0,0,0,,${assEscape(text)}\n")
                    }
                    continue
                }
            }
            i++
        }
        return sb.toString()
    }

    /** fetch the VTT (with the site headers that make it work) and convert */
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
                if (!res.isSuccessful) { Log.d(TAG, "sub fetch fail ${res.code} for $vttUrl"); return null }
                val body = res.body?.string() ?: return null
                if (!body.contains("WEBVTT")) return null
                val ass = vttToAss(body)
                cache[vttUrl] = ass
                Log.d(TAG, "sub converted: $vttUrl -> ${ass.length} chars")
                ass
            }
        } catch (e: Exception) { Log.e(TAG, "sub convert error: ${e.message}"); null }
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
        } catch (e: Exception) { Log.e(TAG, "sub server fail: ${e.message}"); null }
    }

    private fun handle(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val q = parts[1]
        if (!q.startsWith("/sub")) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val params = q.substringAfter('?').split('&')
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
        val bytes = ass.toByteArray()
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }

    /**
     * the one call the provider makes: hand it the site's VTT URL + lang label,
     * get back a local .ass URL with the styles applied.
     */
    fun styledSubtitleUrl(vttUrl: String, lang: String): String? {
        val port = ensureServer() ?: return null
        return try {
            "http://127.0.0.1:$port/sub?u=" + URLEncoder.encode(vttUrl, "UTF-8") +
                "&lang=" + URLEncoder.encode(lang, "UTF-8")
        } catch (_: Exception) { null }
    }
}
