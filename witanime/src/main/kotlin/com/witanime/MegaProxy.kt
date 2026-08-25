package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
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

    data class MegaFile(val dlUrl: String, val size: Long, val aesKey: ByteArray, val nonce: ByteArray)

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
                        socket.keepAlive = true
                        Thread {
                            try { handleClient(socket) } catch (_: Exception) {}
                            finally { try { socket.close() } catch (_: Exception) {} }
                        }.apply { isDaemon = true }.start()
                    } catch (e: Exception) { if (server.isClosed) break }
                }
            }.apply { isDaemon = true; name = "MegaProxy-Accept" }.start()
        } catch (e: Exception) {
            println("WitAnimeDebug: MegaProxy start fail: ${e.message}")
            Thread {
                try { Thread.sleep(5000); start() } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
        }
    }

    /** Check if server is alive, restart if dead */
    private fun ensureAlive() {
        if (serverSocket == null || serverSocket?.isClosed == true) {
            println("WitAnimeDebug: MegaProxy server dead → restarting")
            start()
        }
    }

    suspend fun resolve(url: String): String? {
        return try {
            ensureAlive()  // 🔄 restart if dead

            val m = Regex("""([A-Za-z0-9_-]{8})[!#]([A-Za-z0-9_-]{20,})""").find(url) ?: return null
            val fileId = m.groupValues[1]
            val key = try {
                Base64.decode(m.groupValues[2].replace("-", "+").replace("_", "/"),
                    Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (e: Exception) { return null }
            if (key.size != 32) return null

            val aesKey = ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
            val nonce = key.copyOfRange(16, 24)

            val body = """[{"a":"g","g":1,"ssl":1,"p":"$fileId"}]"""
                .toRequestBody("application/json".toMediaType())
            val resp = app.post(
                "https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}",
                headers = mapOf("User-Agent" to EXTRACTOR_UA),
                requestBody = body
            ).text
            val arr = JSONArray(resp)
            if (arr.length() == 0) return null
            val obj = arr.optJSONObject(0) ?: run { println("WitAnimeDebug: Mega api err ${arr.opt(0)}"); return null }
            val size = obj.optLong("s", -1)
            val dl = obj.optString("g")
            if (size <= 0 || dl.isBlank()) return null

            val server = serverSocket ?: return null
            val token = "${System.currentTimeMillis()}_${seq.incrementAndGet()}"
            files[token] = MegaFile(dl, size, aesKey, nonce)
            println("WitAnimeDebug: Mega proxied OK token=$token size=$size")
            "http://127.0.0.1:${server.localPort}/v/$token.mp4"
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega resolve fail: ${e.message}")
            null
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[1].startsWith("/v/")) return
            val token = parts[1].removePrefix("/v/").substringBefore(".")
            val file = files[token] ?: run {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
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
                append("Connection: keep-alive\r\n\r\n")
            }.toByteArray())
            output.flush()
            if (parts[0].equals("HEAD", true)) return

            val req = Request.Builder().url(file.dlUrl)
                .header("Range", "bytes=$start-$end")
                .header("User-Agent", EXTRACTOR_UA)
                .build()
            app.baseClient.newCall(req).execute().use { resp ->
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
