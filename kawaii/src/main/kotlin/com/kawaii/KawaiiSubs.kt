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

object KawaiiSubs {

    private const val TAG = "KawaiiAnime"
    private val cache = ConcurrentHashMap<String, String>()
    private var server: ServerSocket? = null

    private const val RLE = '\u202B'
    private const val PDF = '\u202C'

    // ── Embedded fonts ────────────────────────────────────────────

    @Volatile private var fontsBlock: String = ""

    fun setEmbeddedFonts(fonts: Map<String, ByteArray>) {
        if (fonts.isEmpty() || fontsBlock.isNotEmpty()) return
        val sb = StringBuilder("\n[Fonts]\n")
        for ((name, data) in fonts) {
            sb.append("fontname: ").append(name).append('\n')
            sb.append(uuencode(data))
        }
        fontsBlock = sb.toString()
        Log.d(TAG, "embedded ${fonts.size} fonts (${fontsBlock.length} chars)")
    }

    private fun uuencode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 / 3) + 64)
        var i = 0
        while (i < data.size) {
            val chunk = minOf(45, data.size - i)
            sb.append(if (chunk == 0) '`' else (chunk + 32).toChar())
            var j = 0
            while (j < chunk) {
                val b0 = data[i + j].toInt() and 0xFF
                val b1 = if (j + 1 < chunk) data[i + j + 1].toInt() and 0xFF else 0
                val b2 = if (j + 2 < chunk) data[i + j + 2].toInt() and 0xFF else 0
                sb.append(enc6((b0 shr 2) and 0x3F))
                sb.append(enc6(((b0 shl 4) or (b1 shr 4)) and 0x3F))
                sb.append(enc6(((b1 shl 2) or (b2 shr 6)) and 0x3F))
                sb.append(enc6(b2 and 0x3F))
                j += 3
            }
            sb.append('\n')
            i += chunk
        }
        sb.append("`\n")
        return sb.toString()
    }

    private fun enc6(v: Int): Char = if (v == 0) '`' else (v + 32).toChar()

    // ── ASS header ────────────────────────────────────────────────

    private val ASS_HEAD = """
        [Script Info]
        Title: Styled Subs
        ScriptType: v4.00+
        WrapStyle: 0
        ScaledBorderAndShadow: yes
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Bahij Nassim,108,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,95,102,0,0,1,3.6,0,2,45,45,45,1
        Style: NOTE,The Year of The Camel ExtraBold,66,&H00FFFFFF,&H0300FFFF,&H00140091,&H02000000,0,0,0,0,95,95,0,0,1,2.4,0,8,24,24,24,1
        Style: song,DG Jory,96,&H00FFFFFF,&H000000FF,&H00893519,&H00000000,-1,0,0,0,95,96,0,0,1,3.0,0,8,30,30,30,1
        Style: Sign,The Year of The Camel ExtraBold,45,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,8,45,45,24,1
    """.trimIndent()

    private val ASS_TAIL = """
        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent()

    private fun header(): String = buildString {
        append(ASS_HEAD)
        if (fontsBlock.isNotEmpty()) append(fontsBlock)
        append('\n').append(ASS_TAIL).append('\n')
    }

    // ── Conversion ────────────────────────────────────────────────

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
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
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
        val sb = StringBuilder(header())
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
                        sb.append("Dialogue: 0,$start,$end,${styleFor(text)},,0,0,0,,${assText(text)}\n")
                    }
                    continue
                }
            }
            i++
        }
        return sb.toString()
    }

    // ── Fetch + cache ─────────────────────────────────────────────

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

    // ── Server ────────────────────────────────────────────────────

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
        } catch (e: Exception) {
            Log.e(TAG, "sub server (non-fatal): ${e.message}")
            null
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val q = parts[1]
        if (!q.startsWith("/styled.ass")) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            try { socket.shutdownOutput() } catch (_: Exception) {}
            return
        }
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
        }
        val params = q.substringAfter('?', "").split('&')
        var vttUrl: String? = null
        for (p in params) {
            if (p.startsWith("u=")) vttUrl = java.net.URLDecoder.decode(p.substring(2), "UTF-8")
        }
        if (vttUrl == null) {
            output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
            try { socket.shutdownOutput() } catch (_: Exception) {}
            return
        }
        val ass = fetchAndConvert(vttUrl)
        if (ass == null) {
            output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
            try { socket.shutdownOutput() } catch (_: Exception) {}
            return
        }
        val bytes = ass.toByteArray(Charsets.UTF_8)
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/x-ssa; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
        try { socket.shutdownOutput() } catch (_: Exception) {}
    }

    fun styledSubtitleUrl(vttUrl: String, lang: String): String? {
        val port = ensureServer() ?: return null
        return try {
            "http://127.0.0.1:$port/styled.ass?u=" + URLEncoder.encode(vttUrl, "UTF-8") +
                "&lang=" + URLEncoder.encode(lang, "UTF-8")
        } catch (_: Exception) { null }
    }
}
