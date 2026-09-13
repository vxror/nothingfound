package com.kawaii

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object KawaiiSubs {
    private const val TAG = "KawaiiSubs"
    private const val MAIN_URL = "https://kawaiianime.cc"
    private const val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

    // Fixed port block so URLs survive app restarts and link-cache replays.
    private const val BASE_PORT = 41739
    private const val PORT_SPAN = 5

    // key (base64 payload) -> ASS bytes
    private val warmCache = ConcurrentHashMap<String, ByteArray>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private var server: ServerSocket? = null
    private val serverLock = Any()

    private val subScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val WAIT_MS = 10_000L

    private const val RLE = '‫'
    private const val PDF = '‬'

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

    // ── server ────────────────────────────────────────────────────

    fun ensureServer(): Int {
        synchronized(serverLock) {
            server?.takeIf { !it.isClosed }?.let { return it.localPort }
            for (p in BASE_PORT until BASE_PORT + PORT_SPAN) {
                try {
                    val s = ServerSocket(p, 50, InetAddress.getByName("127.0.0.1"))
                    server = s

                    Thread {
                        while (!s.isClosed) {
                            try {
                                val socket = s.accept()
                                Thread { handle(socket) }.apply { isDaemon = true }.start()
                            } catch (_: Exception) {}
                        }
                    }.apply { isDaemon = true; name = "KawaiiSubServer" }.start()

                    return p
                } catch (_: Exception) {
                    // port taken, try next
                }
            }
            Log.e(TAG, "ensureServer: all ports $BASE_PORT..${BASE_PORT + PORT_SPAN - 1} failed")
            return -1
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

            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
            }

            if (!path.startsWith("/sub/")) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            val key = path.substringAfter("/sub/", "").substringBefore("?").substringBefore(".ass")
            if (key.isBlank()) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            var data = warmCache[key]

            // If a pre-warm coroutine is in flight, wait for it.
            if (data == null && pending.contains(key)) {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < WAIT_MS) {
                    data = warmCache[key]
                    if (data != null || !pending.contains(key)) break
                    Thread.sleep(50)
                }
            }

            // Cold key (stale replay / process restarted): fetch synchronously here.
            // [FIX 134] runBlocking — we are on a dedicated server thread whose whole
            // purpose is to block until the response is ready. This is the ONLY legal
            // bridge from a raw thread into the suspend fetch layer.
            if (data == null && pending.add(key)) {
                try {
                    val fields = decodeKey(key)
                    if (fields != null) {
                        val ass = runBlocking {
                            fetchAndConvert(fields.vttUrl, fields.showId, fields.epNum, fields.titles, fields.translate)
                        }
                        if (ass != null) {
                            data = ass.toByteArray(Charsets.UTF_8)
                            warmCache[key] = data
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "handle on-demand fetch: ${e.message}")
                } finally {
                    pending.remove(key)
                }
            }

            if (data == null) {
                // 503 is retryable for ExoPlayer/mpv; 404 kills the track forever.
                output.write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }

            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-subtitle-ass; charset=utf-8\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "handle: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── public entry ──────────────────────────────────────────────

    fun prepareSubtitle(
        rawUrl: String,
        lang: String,
        showId: String,
        epNum: String,
        titles: List<String>,
        tryTranslate: Boolean
    ): String? {
        val port = ensureServer()
        if (port == -1) return null

        val key = encodeKey(rawUrl, showId, epNum, titles.firstOrNull().orEmpty(), tryTranslate)

        if (!warmCache.containsKey(key) && pending.add(key)) {
            subScope.launch {
                try {
                    val ass = fetchAndConvert(rawUrl, showId, epNum, titles, tryTranslate)
                    if (ass != null) {
                        warmCache[key] = ass.toByteArray(Charsets.UTF_8)
                        Log.d(TAG, "subtitle ready [${if (tryTranslate) "ar" else "raw"}] key=${key.take(12)}")
                    } else {
                        Log.d(TAG, "subtitle fetch failed [${if (tryTranslate) "ar" else "raw"}] $rawUrl")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "prepareSubtitle: ${e.message}")
                } finally {
                    pending.remove(key)
                }
            }
        }

        return "http://127.0.0.1:$port/sub/$key.ass"
    }

    /** Remote last-resort URL (works without custom headers, proven by probe). */
    fun remoteTranslateUrl(vttUrl: String, epNum: String, title: String): String {
        val encUrl = URLEncoder.encode(vttUrl, "UTF-8")
        val encTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        return "$MAIN_URL/api/translate-sub?url=$encUrl&v=$epNum&title=$encTitle"
    }

    // ── key codec ─────────────────────────────────────────────────
    // [FIX] android.util.Base64 — java.util.Base64 needs API 26, minSdk is 21

    private class KeyFields(
        val vttUrl: String,
        val showId: String,
        val epNum: String,
        val titles: List<String>,
        val translate: Boolean
    )

    private fun encodeKey(vttUrl: String, showId: String, epNum: String, title: String, translate: Boolean): String {
        val payload = listOf(if (translate) "ar" else "raw", showId, epNum, title, vttUrl).joinToString("|")
        return android.util.Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
    }

    private fun decodeKey(key: String): KeyFields? {
        return try {
            val payload = String(android.util.Base64.decode(key, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            val f = payload.split("|", limit = 5)
            if (f.size < 5) return null
            KeyFields(
                vttUrl = f[4],
                showId = f[1],
                epNum = f[2],
                titles = listOf(f[3]).filter { it.isNotBlank() },
                translate = f[0] == "ar"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── network (2 attempts each — device DNS is flaky per logs) ──

    private fun titleCandidates(titles: List<String>): List<String> {
        val out = ArrayList<String>()
        for (raw in titles) {
            val t = raw.trim()
            if (t.isBlank()) continue
            out.add(t)
            out.add(t.uppercase())
        }
        if (out.isEmpty()) out.add("")
        return out.distinct().take(6)
    }

    private suspend fun fetchAndConvert(
        rawUrl: String,
        showId: String,
        epNum: String,
        titles: List<String>,
        tryTranslate: Boolean
    ): String? {
        if (tryTranslate) {
            for (title in titleCandidates(titles)) {
                val vtt = fetchTranslation(rawUrl, showId, epNum, title)
                if (vtt != null) return vttToAss(vtt)
            }
            return null
        }
        return fetchRawVtt(rawUrl)?.let { vttToAss(it) }
    }

    private suspend fun fetchTranslation(vttUrl: String, showId: String, epNum: String, title: String): String? {
        val encUrl = URLEncoder.encode(vttUrl, "UTF-8")
        val encTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = "$MAIN_URL/api/translate-sub?url=$encUrl&v=$epNum&title=$encTitle"

        repeat(2) { attempt ->
            try {
                val res = app.get(
                    url,
                    headers = mapOf(
                        "accept" to "*/*",
                        "accept-language" to "en-US,en;q=0.9",
                        "dnt" to "1",
                        "priority" to "u=1, i",
                        "referer" to "$MAIN_URL/watch/$showId?num=$epNum",
                        "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Quetta\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
                        "sec-ch-ua-mobile" to "?1",
                        "sec-ch-ua-platform" to "\"Android\"",
                        "sec-fetch-dest" to "empty",
                        "sec-fetch-mode" to "cors",
                        "sec-fetch-site" to "same-origin",
                        "sec-gpc" to "1",
                        "user-agent" to UA
                    )
                )
                if (res.isSuccessful && res.text.contains("WEBVTT")) return res.text
            } catch (e: Exception) {
                Log.e(TAG, "fetchTranslation attempt=$attempt: ${e.message}")
            }
            if (attempt == 0) delay(400)
        }
        return null
    }

    private suspend fun fetchRawVtt(vttUrl: String): String? {
        repeat(2) { attempt ->
            try {
                val res = app.get(
                    vttUrl,
                    headers = mapOf(
                        "sec-ch-ua-platform" to "\"Android\"",
                        "Referer" to "$MAIN_URL/",
                        "User-Agent" to UA,
                        "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Quetta\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
                        "DNT" to "1",
                        "sec-ch-ua-mobile" to "?1",
                        "Accept" to "*/*"
                    )
                )
                if (res.isSuccessful && res.text.contains("WEBVTT")) return res.text
            } catch (e: Exception) {
                Log.e(TAG, "fetchRawVtt attempt=$attempt: ${e.message}")
            }
            if (attempt == 0) delay(400)
        }
        return null
    }

    // ── VTT → ASS ─────────────────────────────────────────────────

    private fun cleanBidi(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val cp = c.code
            val drop = cp == 0x200e || cp == 0x200f || (cp in 0x202a..0x202e) ||
                (cp in 0x2066..0x2069) || cp == 0xfeff || (cp in 0x200b..0x200d)
            if (!drop) sb.append(c)
        }
        return sb.toString()
    }

    /**
     * [HTML FIX] the site's subs carry HTML markup — well-formed pairs like
     * <b>word</b>, AND malformed garbage like "<b/>text<b>" (see screenshots).
     * entities are unescaped first, well-formed tags become ASS overrides,
     * everything else tag-shaped is stripped by the catch-all — <b/> included.
     */
    private fun inlineToAss(s: String): String {
        var t = s
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        t = t
            .replace(Regex("<\\s*b\\s*>", RegexOption.IGNORE_CASE), "{\\b1}")
            .replace(Regex("<\\s*/\\s*b\\s*>", RegexOption.IGNORE_CASE), "{\\b0}")
            .replace(Regex("<\\s*i\\s*>", RegexOption.IGNORE_CASE), "{\\i1}")
            .replace(Regex("<\\s*/\\s*i\\s*>", RegexOption.IGNORE_CASE), "{\\i0}")
            .replace(Regex("<\\s*u\\s*>", RegexOption.IGNORE_CASE), "{\\u1}")
            .replace(Regex("<\\s*/\\s*u\\s*>", RegexOption.IGNORE_CASE), "{\\u0}")
        // catch-all: <b/>, </ b >, <font ...>, <span>, anything tag-shaped → gone
        t = t.replace(Regex("<[^>]*>"), "")
        return t.replace(Regex(" {2,}"), " ").trimEnd()
    }

    private fun styleFor(text: String): String {
        val t = text.replace(Regex("<[^>]*>"), "").trim()
        return when {
            t.contains("♪") || t.contains("♫") || t.startsWith("#") -> "song"
            t.startsWith("(") || t.startsWith("[") -> "NOTE"
            else -> "Default"
        }
    }

    private fun assText(text: String): String {
        val cleaned = cleanBidi(text)
            .replace("{", "\\{")
            .replace("}", "\\}")
        val converted = inlineToAss(cleaned)
        return converted.split('\n')
            .filter { it.isNotBlank() }
            .map { RLE + it + PDF }
            .joinToString("\\N")
    }

    // Accepts HH:MM:SS.mmm AND MM:SS.mmm (Black Torch ep 2 uses the latter).
    private fun vttToAssTime(t: String): String {
        val m = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?""").find(t.trim()) ?: return t
        val h = m.groupValues[1].ifEmpty { "0" }.toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].toInt()
        val frac = m.groupValues[4].padEnd(3, '0').ifEmpty { "000" }.toInt()
        val cs = (frac / 10).toString().padStart(2, '0')
        return "$h:${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.$cs"
    }

    fun vttToAss(vtt: String): String {
        val sb = StringBuilder().append(ASS_HEADER).append('\n')
        val lines = vtt.replace("\r\n", "\n").replace('﻿', ' ').split('\n')
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
                    if (text.replace(Regex("<[^>]*>"), "").isNotBlank()) {
                        sb.append("Dialogue: 0,$start,$end,${styleFor(text)},,0,0,0,,${assText(text)}\n")
                    }
                    continue
                }
            }
            i++
        }
        return sb.toString()
    }
}
