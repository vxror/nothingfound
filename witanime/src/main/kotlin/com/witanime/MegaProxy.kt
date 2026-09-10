package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaProxy {
    private var serverSocket: ServerSocket? = null
    private val files = ConcurrentHashMap<String, MegaFile>()
    private val seq = AtomicInteger(0)

    // [v164] BUCKET POOL — every entry is a mega quota bucket, walked with
    // automatic failover. capture each sid from a logged-in browser session:
    // mega.nz → devtools → any g.api.mega.co.nz/cs request → copy sid=.
    // "" (empty) = the anonymous per-IP bucket, always tried last.
    // MORE BUCKETS ≠ PREMIUM: utype lives in mega's payment database. a
    // PREMIUM sid pasted here inherits Pro bandwidth instantly.
    // WARP NOTE: a WARP connection = a different IP = a fresh anonymous
    // bucket. toggling WARP off/on mints a new bucket on demand.
    private val MEGA_SIDS = listOf(
        "HNarUZNXSv6yQQE_zH5PNTBxeV83aDV3N1ZnnUeH5KE4IZv9W2B7nFSCwA",
        "4Ifv50LOUzGvZXEyl3zf31FyZ2lNWkx3ZC0wShSSNY8wTt6VUnF1MdQjqg",
        "LRH1dwBlNNJyn-CWvp6fTDE0b1JxWE54QWNZ0lIRY50Z0bKDZluMuQlYcQ",
        "kuizsHSgjjrB8ZMtNFqMW0NPWktHdXZiRXhnZ34TFlmKEqPVcCWxP56u9w",
        "ZxHhV3yrH8wZc2CNaXNJm2tmMS1aZkF6SFlz6kQFj6RFuRkmnqnvtu2NOg",
        "-puwJUjnYfeW4mgSm-JPFkpQajJtWjNmMkdzlAbh3xHxVrxzRwYA3gVQWQ",
        "gXvVNVDK_v6FTnHkFM3_nGViVjExZzdUN0NzGvg1JO8w8D7go12_SRBB1Q",
        "Ewbc-0x2IZUPE62DP0ch8GN4VzVRRmFPcWVFmPOi4ALVRNl8oj894omZzg",
        "AwqLRDNeOhAmhzWoM5Xl4ExXQ0w1aDdWcDZBvv8JNex0jKLNbA0Vu0eNGw",
        "jCqhVk8H_Wr-KuZhjEuemFo3bFdyQ3lYTjhZzbI4eZxjjIVLOTzTURaCbg",
        "NYeY8gINsTfPQaNvyRG-6lJaWWZhcG53bjRzSItns-74rch1UJnPcg2Q3Q"
    )

    // sid -> epoch-millis when a drained bucket may be retried
    private val bucketCooldowns = ConcurrentHashMap<String, Long>()

    data class MegaFile(
        val dlUrl: String,
        val size: Long,
        val aesKey: ByteArray,
        val nonce: ByteArray,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var head: ByteArray? = null
    )

    fun start() {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            println("WitAnimeDebug: MegaProxy listening on ${server.localPort}")
            Thread {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        Thread {
                            try { handleClient(socket) } catch (_: Exception) {}
                            finally { try { socket.close() } catch (_: Exception) {} }
                        }.apply { isDaemon = true }.start()
                    } catch (e: Exception) { if (server.isClosed) break }
                }
            }.apply { isDaemon = true; name = "MegaProxy-Accept" }.start()
        } catch (e: Exception) {
            println("WitAnimeDebug: MegaProxy start fail: ${e.message}")
        }
    }

    private fun ensureAlive() {
        if (serverSocket == null || serverSocket?.isClosed == true) {
            println("WitAnimeDebug: MegaProxy server dead → restarting")
            serverSocket = null
            start()
        }
        cleanup()
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val iterator = files.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // 30-minute TTL to free RAM
            if (now - entry.value.createdAt > 1_800_000) {
                iterator.remove()
            }
        }
    }

    suspend fun resolve(url: String): String? {
        return try {
            ensureAlive()

            val m = Regex("""([A-Za-z0-9_-]{8})[!#]([A-Za-z0-9_-]{20,})""").find(url) ?: return null
            val fileId = m.groupValues[1]
            val key = try {
                Base64.decode(m.groupValues[2].replace("-", "+").replace("_", "/"),
                    Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (e: Exception) { return null }
            if (key.size != 32) return null

            val aesKey = ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
            val nonce = key.copyOfRange(16, 24)

            // walk the pool: every sid, then the anonymous IP bucket
            val buckets = MEGA_SIDS.map { it to "account" } + listOf("" to "anonymous-IP")

            for ((sid, label) in buckets) {
                // skip buckets known to be drained until their reset time
                val cooldownUntil = if (sid.isNotBlank()) bucketCooldowns[sid] ?: 0L else 0L
                if (System.currentTimeMillis() < cooldownUntil) {
                    println("WitAnimeDebug: skipping $label bucket " +
                        "(~${(cooldownUntil - System.currentTimeMillis()) / 60_000} min cooldown left)")
                    continue
                }

                val sidParam = if (sid.isNotBlank()) "&sid=$sid" else ""
                val body = """[{"a":"g","g":1,"ssl":1,"p":"$fileId"}]"""
                    .toRequestBody("application/json".toMediaType())
                val resp = try {
                    app.post(
                        "https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}$sidParam",
                        headers = mapOf("User-Agent" to EXTRACTOR_UA),
                        requestBody = body
                    ).text
                } catch (e: Exception) { continue }

                val arr = try { JSONArray(resp) } catch (_: Exception) { continue }
                if (arr.length() == 0) continue

                val first = arr.opt(0)
                val errCode = when (first) {
                    is Int -> first
                    is Long -> first.toInt()
                    is String -> first.toIntOrNull()
                    else -> null
                }
                if (errCode != null) {
                    when (errCode) {
                        // quota family → log, cooldown, failover to next bucket
                        -17, -18, -24 -> {
                            val resetSecs = if (sid.isNotBlank()) logQuotaReset(sid) else 0L
                            println("WitAnimeDebug: Mega quota dead ($label bucket, " +
                                "resets ~${resetSecs / 60} min) — next bucket")
                            if (sid.isNotBlank()) {
                                bucketCooldowns[sid] = System.currentTimeMillis() +
                                    (resetSecs * 1000L).coerceIn(300_000L, 6 * 3600_000L)
                            }
                        }
                        -15, -16 -> println("WitAnimeDebug: Mega sid dead/blocked ($label) — remove from MEGA_SIDS")
                        -9 -> { println("WitAnimeDebug: Mega file not found / removed"); return null }
                        else -> println("WitAnimeDebug: Mega api error $errCode ($label)")
                    }
                    continue
                }

                val obj = arr.optJSONObject(0) ?: continue

                // mega sometimes answers 200 with "tl" = seconds until quota resets
                val timeLeft = obj.optLong("tl", 0L)
                if (timeLeft > 0) {
                    println("WitAnimeDebug: Mega free limit ($label) — resets in " +
                        "${timeLeft / 60} min, next bucket")
                    if (sid.isNotBlank()) {
                        bucketCooldowns[sid] = System.currentTimeMillis() + timeLeft * 1000L
                    }
                    continue
                }

                val size = obj.optLong("s", -1)
                val dl = obj.optString("g")
                if (size <= 0 || dl.isBlank()) continue

                val server = serverSocket ?: return null
                val token = "${System.currentTimeMillis()}_${seq.incrementAndGet()}"
                files[token] = MegaFile(dl, size, aesKey, nonce)
                println("WitAnimeDebug: Mega resolved token=$token size=${size / 1048576}MB ($label bucket)")

                prewarm(token, dl)

                return "http://127.0.0.1:${server.localPort}/v/$token.mp4"
            }

            println("WitAnimeDebug: Mega ALL BUCKETS DRAINED (${buckets.size} tried) — " +
                "toggle WARP off/on for a fresh IP bucket, or use the CF Bypass server")
            null
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega resolve fail: ${e.message}")
            null
        }
    }

    /** returns seconds until the account's transfer window resets (0 = unknown) */
    private suspend fun logQuotaReset(sid: String): Long {
        try {
            val body = """[{"a":"uq","xfer":1,"pro":1}]"""
                .toRequestBody("application/json".toMediaType())
            val res = app.post(
                "https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}&sid=$sid",
                headers = mapOf("User-Agent" to EXTRACTOR_UA),
                requestBody = body
            ).text
            val obj = (try { JSONArray(res) } catch (_: Exception) { null })?.optJSONObject(0) ?: return 0L
            val bt = obj.optLong("bt", 0L)
            val tar = obj.optLong("tar", 0L)
            val tah = obj.optJSONArray("tah")
            val used = tah?.let { a -> (0 until a.length()).sumOf { i -> a.optLong(i, 0L) } } ?: 0L
            if (used < bt && tar > 0) return 0L

            var add = true
            var timeLeft = 3600L - (bt % 3600L)
            if (tah != null) {
                for (i in 0 until tah.length()) {
                    if (tah.optLong(i, 0L) > 0L) add = false
                    else if (add) timeLeft += 3600L
                }
            }
            println("WitAnimeDebug: bucket resets in ~${timeLeft / 60} min " +
                "(used=$used bt=$bt tar=$tar)")
            return timeLeft
        } catch (_: Exception) { return 0L }
    }

    /** prewarm first 1MB — instant player start, surfaces 509 early */
    private fun prewarm(token: String, dl: String) {
        Thread {
            try {
                val req = Request.Builder().url(dl)
                    .header("Range", "bytes=0-1048575")
                    .header("User-Agent", EXTRACTOR_UA)
                    .build()
                app.baseClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        // [v164 FIX] labeled return — a bare `return` here is
                        // prohibited (inside use{} nested in the non-inline
                        // Thread lambda). return@use exits just this block.
                        val body = resp.body?.byteStream() ?: return@use
                        val bos = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        var total = 0
                        while (body.read(buf).also { n = it } != -1 && total < 1_048_576) {
                            bos.write(buf, 0, n); total += n
                        }
                        files[token]?.head = bos.toByteArray()
                        println("WitAnimeDebug: Mega prewarmed ${bos.size()}B")
                    } else if (resp.code == 509) {
                        println("WitAnimeDebug: Mega CDN 509 — bandwidth limit at CDN")
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "MegaPrewarm" }.start()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[1].startsWith("/v/")) return
            val token = parts[1].removePrefix("/v/").substringBefore(".")

            val file = files[token] ?: run {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }

            var start = 0L; var end = file.size - 1; var isRange = false
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("range: bytes=")) {
                    isRange = true
                    val r = line.substringAfter("=").split("-")
                    start = r.getOrNull(0)?.toLongOrNull() ?: 0L
                    end = r.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: (file.size - 1)
                }
            }

            val length = end - start + 1
            output.write(buildString {
                append("HTTP/1.1 ${if (isRange) "206 Partial Content" else "200 OK"}\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (isRange) append("Content-Range: bytes $start-$end/${file.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray())
            output.flush()
            if (parts[0].equals("HEAD", true)) return

            // serve tiny probes instantly from the prewarmed head
            val head = file.head
            if (head != null && head.isNotEmpty() && start < head.size && end < head.size) {
                try {
                    val iv = ByteBuffer.allocate(16)
                    iv.put(file.nonce)
                    iv.putLong(start / 16)
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(file.aesKey, "AES"), IvParameterSpec(iv.array()))
                    val skip = (start % 16).toInt()
                    if (skip > 0) cipher.update(ByteArray(skip))
                    val out = cipher.doFinal(head, start.toInt(), (end - start + 1).toInt())
                    if (out != null && out.isNotEmpty()) {
                        output.write(out)
                        output.flush()
                        return
                    }
                } catch (_: Exception) { }
            }

            val req = Request.Builder().url(file.dlUrl)
                .header("Range", "bytes=$start-$end")
                .header("User-Agent", EXTRACTOR_UA)
                .build()
            app.baseClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 509) {
                        println("WitAnimeDebug: Mega CDN 509 — bandwidth limit (bucket empty)")
                    } else {
                        println("WitAnimeDebug: MegaProxy upstream failed: ${resp.code}")
                    }
                    return
                }
                val body = resp.body?.byteStream() ?: return

                val iv = ByteBuffer.allocate(16)
                iv.put(file.nonce)
                iv.putLong(start / 16)
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(file.aesKey, "AES"), IvParameterSpec(iv.array()))
                val skip = (start % 16).toInt()
                if (skip > 0) cipher.update(ByteArray(skip))
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (body.read(buf).also { n = it } != -1) {
                    val out = cipher.update(buf, 0, n) ?: continue
                    try { output.write(out); output.flush() } catch (_: Exception) { break }
                }
                try { cipher.doFinal()?.let { output.write(it) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
