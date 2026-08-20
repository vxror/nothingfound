package com.animewitcher

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaProxy {
    private var serverSocket: ServerSocket? = null
    private val files = ConcurrentHashMap<String, MegaFile>()
    private var seq = 0

    // 🆕 AUTO-CLEANUP: Track token timestamps to prevent memory leaks
    private val tokenTimestamps = ConcurrentHashMap<String, Long>()
    private const val TOKEN_TTL_MS = 30 * 60 * 1000L // 30 minutes

    // 🆕 CONNECTION LIMITING: Prevent resource exhaustion
    private val activeConnections = AtomicInteger(0)
    private const val MAX_ACTIVE_CONNECTIONS = 10

    data class MegaFile(val dlUrl: String, val size: Long, val aesKey: ByteArray, val nonce: ByteArray)

    init {
        // 🆕 AUTO-CLEANUP THREAD
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(60 * 1000)
                    val now = System.currentTimeMillis()
                    val expired = tokenTimestamps.entries.filter { now - it.value > TOKEN_TTL_MS }.map { it.key }
                    expired.forEach { files.remove(it); tokenTimestamps.remove(it) }
                } catch (_: InterruptedException) { break } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "MegaProxy-Cleanup"; start() }
    }

    fun start() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        try {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            Thread {
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val socket = serverSocket!!.accept()
                        // 🆕 CONNECTION LIMITING
                        if (activeConnections.get() >= MAX_ACTIVE_CONNECTIONS) {
                            try {
                                socket.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
                                socket.close()
                            } catch (_: Exception) {}
                            continue
                        }
                        activeConnections.incrementAndGet()
                        Thread {
                            try { handleClient(socket) } finally { activeConnections.decrementAndGet() }
                        }.apply { isDaemon = true }.start()
                    } catch (e: Exception) {
                        if (serverSocket?.isClosed == true) break
                    }
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) { }
    }

    suspend fun resolve(url: String): String? {
        try {
            val parsed = parseEmbed(url) ?: return null
            val (fileId, keyBytes) = parsed
            val aesKey = ByteArray(16)
            for (i in 0 until 16) aesKey[i] = (keyBytes[i].toInt() xor keyBytes[i + 16].toInt()).toByte()
            val nonce = ByteArray(8)
            for (i in 0 until 8) nonce[i] = keyBytes[i + 16]
            val api = megaApi(fileId) ?: return null
            val size = api.optLong("s")
            val dlUrl = api.optString("g")
            if (size <= 0 || dlUrl.isEmpty()) return null
            val port = serverSocket?.localPort ?: return null
            val token = "${System.currentTimeMillis()}_${seq++}"
            files[token] = MegaFile(dlUrl, size, aesKey, nonce)
            tokenTimestamps[token] = System.currentTimeMillis() // 🆕 Track for cleanup
            return "http://127.0.0.1:$port/v/$token.mp4"
        } catch (e: Exception) { return null }
    }

    private suspend fun megaApi(fileId: String): JSONObject? {
        try {
            val url = "https://g.api.mega.co.nz/cs?id=${seq++}"
            val body = """[{"a":"g","g":1,"ssl":1,"p":"$fileId"}]"""
            val req = app.post(url, requestBody = okhttp3.RequestBody.create(null, body)).text
            val j = JSONArray(req)
            if (j.length() > 0 && j.get(0) is JSONObject) return j.getJSONObject(0)
            return null
        } catch (e: Exception) { return null }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[1].startsWith("/v/")) {
                output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()); return
            }
            val token = parts[1].removePrefix("/v/").substringBefore(".")
            val file = files[token] ?: run { output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()); return }
            var start = 0L; var end = file.size - 1; var isRange = false
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("range: bytes=")) {
                    isRange = true; val rangeStr = line.substringAfter("="); val rangeParts = rangeStr.split("-")
                    start = rangeParts[0].toLongOrNull() ?: 0
                    if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) end = rangeParts[1].toLongOrNull() ?: (file.size - 1)
                }
            }
            val length = end - start + 1
            val status = if (isRange) "206 Partial Content" else "200 OK"
            val headers = buildString {
                append("HTTP/1.1 $status\r\n"); append("Content-Type: video/mp4\r\n"); append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (isRange) append("Content-Range: bytes $start-$end/${file.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n"); append("\r\n")
            }
            output.write(headers.toByteArray()); output.flush()
            if (parts[0].uppercase() == "HEAD") return
            val request = Request.Builder().url(file.dlUrl).header("Range", "bytes=$start-$end")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").build()
            app.baseClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val inputStream = response.body?.byteStream() ?: return
                val blockIndex = start / 16
                val iv = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                iv.put(file.nonce); iv.putLong(blockIndex)
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(file.aesKey, "AES"), IvParameterSpec(iv.array()))
                val skip = (start % 16).toInt()
                if (skip > 0) { val dummy = ByteArray(skip); cipher.update(dummy, 0, skip, dummy, 0) }
                val buffer = ByteArray(8192); var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val decrypted = cipher.update(buffer, 0, bytesRead)
                    if (decrypted != null) { try { output.write(decrypted) } catch (e: Exception) { break } }
                }
                val finalBlock = cipher.doFinal()
                if (finalBlock != null) { try { output.write(finalBlock) } catch (e: Exception) {} }
                output.flush()
            }
        } catch (e: Exception) { } finally { try { socket.close() } catch (e: Exception) {} }
    }

    private fun parseEmbed(url: String): Pair<String, ByteArray>? {
        val regex = Regex("""mega\.nz/(?:embed|file)/([^!#?/]+)[!#]([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        var match = regex.find(url)
        if (match == null) {
            val legacy = Regex("""mega\.nz/#!([^!#?/]+)!([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(url)
            if (legacy == null) return null; match = legacy
        }
        val id = match.groupValues[1]
        var keyB64 = match.groupValues[2].replace("-", "+").replace("_", "/").replace(",", "")
        while (keyB64.length % 4 != 0) keyB64 += "="
        val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
        if (keyBytes.size != 32) return null
        return Pair(id, keyBytes)
    }
}
