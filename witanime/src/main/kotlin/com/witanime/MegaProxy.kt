package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaProxy {
    private var serverSocket: ServerSocket? = null
    private val files = ConcurrentHashMap<String, MegaFile>()
    private val seq = AtomicInteger(0)
    
    // [!] Aggressive timeouts to ensure instant fallback to OkRu/StreamWish if Mega blocks us
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // [!] Premium-grade rotating User-Agents to bypass AI bot-fingerprinting
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    )

    data class MegaFile(
        val dlUrl: String, 
        val size: Long, 
        val aesKey: ByteArray, 
        val nonce: ByteArray,
        val createdAt: Long = System.currentTimeMillis()
    )

    fun start() {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            Thread {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        Thread {
                            try { handleClient(socket) } catch (_: Exception) {}
                            finally { try { socket.close() } catch (_: Exception) {} }
                        }.apply { isDaemon = true }.start()
                    } catch (e: Exception) { 
                        if (server.isClosed) break 
                        Thread.sleep(100)
                    }
                }
            }.apply { isDaemon = true; name = "MegaProxy-Accept" }.start()
        } catch (e: Exception) {
            println("WitAnimeDebug: MegaProxy start fail: ${e.message}")
        }
    }

    private fun ensureAlive() {
        if (serverSocket == null || serverSocket?.isClosed == true) {
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
            // [!] 30-minute TTL to aggressively free RAM
            if (now - entry.value.createdAt > 1_800_000) {
                iterator.remove()
            }
        }
    }

    suspend fun resolve(url: String): String? {
        return try {
            ensureAlive()

            val m = Regex("""(?:[#!]|file/)([A-Za-z0-9_-]{8})[#!]([A-Za-z0-9_-]{20,})""").find(url)
                ?: Regex("""([A-Za-z0-9_-]{8})[#!]([A-Za-z0-9_-]{20,})""").find(url)
                ?: return null
                
            val fileId = m.groupValues[1]
            
            var keyStr = m.groupValues[2].replace("-", "+").replace("_", "/")
            val pad = keyStr.length % 4
            if (pad != 0) keyStr += "=".repeat(4 - pad)
            
            val key = try {
                Base64.decode(keyStr, Base64.DEFAULT)
            } catch (e: Exception) { return null }
            
            if (key.size != 32 && key.size != 16) return null

            val aesKey: ByteArray
            val nonce: ByteArray
            if (key.size == 32) {
                aesKey = ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
                nonce = key.copyOfRange(16, 24)
            } else {
                aesKey = key.copyOf(16)
                nonce = ByteArray(8)
            }

            val body = """[{"a":"g","g":1,"ssl":2,"p":"$fileId"}]"""
                .toRequestBody("application/json".toMediaType())
                
            val resp = app.post(
                "https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}",
                headers = mapOf("User-Agent" to USER_AGENTS.random()),
                requestBody = body
            ).text
            
            if (resp.isBlank() || resp.startsWith("-")) return null

            val arr = JSONArray(resp)
            if (arr.length() == 0) return null
            
            val obj = arr.optJSONObject(0) ?: return null
            
            val size = obj.optLong("s", -1)
            val dl = obj.optString("g")
            if (size <= 0 || dl.isBlank()) return null

            val server = serverSocket ?: return null
            val token = "${System.currentTimeMillis()}_${seq.incrementAndGet()}"
            files[token] = MegaFile(dl, size, aesKey, nonce)
            "http://127.0.0.1:${server.localPort}/v/$token.mp4"
        } catch (e: Exception) {
            println("WitAnimeDebug: Mega resolve fail: ${e.message}")
            null
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15_000 // Aggressive timeout
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || !parts[1].startsWith("/v/")) return
            
            val token = parts[1].removePrefix("/v/").substringBefore(".").substringBefore("?")

            val file = files[token]
            if (file == null) {
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

            val req = Request.Builder().url(file.dlUrl)
                .header("Range", "bytes=$start-$end")
                .header("User-Agent", USER_AGENTS.random())
                .header("Accept", "*/*")
                .build()
                
            val resp = httpClient.newCall(req).execute()
            
            // [!] INSTANT FALLBACK TRIGGER: If Mega blocks us (509/403), kill the socket immediately.
            // This forces ExoPlayer to throw an error and instantly load OkRu/StreamWish.
            if (!resp.isSuccessful) {
                if (resp.code == 509 || resp.code == 403) {
                    println("WitAnimeDebug: 🚨 MEGA ${resp.code} LIMIT! Dropping connection instantly for Auto-Fallback.")
                } else {
                    println("WitAnimeDebug: MegaProxy upstream failed: ${resp.code}")
                }
                resp.close()
                return 
            }

            val body = resp.body?.byteStream() ?: run { resp.close(); return }
            
            val iv = ByteBuffer.allocate(16)
            iv.put(file.nonce)
            iv.putLong(start / 16)
            
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(file.aesKey, "AES"), IvParameterSpec(iv.array()))
            
            val buf = ByteArray(64 * 1024)
            var n: Int
            var bytesToSkip = (start % 16).toInt()
            
            while (body.read(buf).also { n = it } != -1) {
                val out = cipher.update(buf, 0, n) ?: continue
                if (bytesToSkip > 0) {
                    if (out.size <= bytesToSkip) {
                        bytesToSkip -= out.size
                        continue
                    } else {
                        output.write(out, bytesToSkip, out.size - bytesToSkip)
                        bytesToSkip = 0
                    }
                } else {
                    output.write(out)
                }
                output.flush()
            }
            try { cipher.doFinal()?.let { output.write(it) } } catch (_: Exception) {}
            resp.close()
        } catch (e: SocketException) {
            // Client disconnected or timeout
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
