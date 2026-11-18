package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaProxy {
    private var serverSocket: ServerSocket? = null
    private val files = ConcurrentHashMap<String, MegaFile>()
    private val seq = AtomicInteger(0)

    private val authToken: String by lazy {
        ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "MegaProxy-Worker").apply { isDaemon = true } }

    // sid sessions from YOUR OWN mega accounts; "" = anonymous per-IP bucket (last)
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
    private val bucketCooldowns = ConcurrentHashMap<String, Long>()

    private class Node(val dl: String, val size: Long, val sid: String, val proxy: String?, val ts: Long)
    private val nodeCache = ConcurrentHashMap<String, Node>()
    private const val NODE_TTL = 10 * 60_000L

    class MegaFile(
        val fileId: String, val aesKey: ByteArray, val nonce: ByteArray, val size: Long,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var lastSid: String = "", @Volatile var proxy: String? = null,
        @Volatile var head: ByteArray? = null
    )

    @Synchronized
    fun start() {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            WitaLog.d("MegaProxy listening on ${server.localPort}")
            Thread {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        pool.execute {
                            try { handleClient(socket) } catch (_: Exception) {}
                            finally { try { socket.close() } catch (_: Exception) {} }
                        }
                    } catch (e: Exception) { if (server.isClosed) break }
                }
            }.apply { isDaemon = true; name = "MegaProxy-Accept" }.start()
        } catch (e: Exception) { WitaLog.e("MegaProxy start fail: ${e.message}") }
    }

    private fun ensureAlive() {
        if (serverSocket == null || serverSocket?.isClosed == true) { serverSocket = null; start() }
        cleanup()
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        files.entries.removeIf { now - it.value.createdAt > 3 * 3600_000L }
        while (files.size > 16) {
            val oldest = files.entries.minByOrNull { it.value.createdAt } ?: break
            files.remove(oldest.key)
        }
        nodeCache.entries.removeIf { now - it.value.ts > NODE_TTL }
    }

    private fun parseSpec(url: String): Triple<String, ByteArray, ByteArray>? {
        val m = Regex("""mega\.(?:nz|co\.nz)/(?:embed|file)/([A-Za-z0-9_-]{4,})#([A-Za-z0-9_-]{20,})""").find(url)
            ?: Regex("""mega\.(?:nz|co\.nz)/#!([A-Za-z0-9_-]{4,})!([A-Za-z0-9_-]{20,})""").find(url)
            ?: return null
        val key = try {
            Base64.decode(m.groupValues[2].replace("-", "+").replace("_", "/"), Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) { return null }
        if (key.size < 32) return null
        val aesKey = ByteArray(16) { (key[it].toInt() xor key[it + 16].toInt()).toByte() }
        return Triple(m.groupValues[1], aesKey, key.copyOfRange(16, 24))
    }

    private suspend fun resolveNode(fileId: String, excludeSid: String? = null, excludeProxy: String? = null): Node? {
        val buckets = MEGA_SIDS.map { it to "account" } + listOf("" to "anonymous-IP")
        for ((sid, label) in buckets) {
            if (sid == excludeSid) continue
            if (sid.isNotBlank() && System.currentTimeMillis() < (bucketCooldowns[sid] ?: 0L)) continue
            val sidParam = if (sid.isNotBlank()) "&sid=$sid" else ""
            val body = """[{"a":"g","g":1,"ssl":1,"p":"$fileId"}]""".toRequestBody("application/json".toMediaType())
            val resp = try {
                app.post("https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}$sidParam",
                    headers = mapOf("User-Agent" to EXTRACTOR_UA), requestBody = body).text
            } catch (_: Exception) { continue }
            val arr = try { JSONArray(resp) } catch (_: Exception) { continue }
            if (arr.length() == 0) continue
            val first = arr.opt(0)
            val errCode = when (first) { is Int -> first; is Long -> first.toInt(); is String -> first.toIntOrNull(); else -> null }
            if (errCode != null) {
                when (errCode) {
                    -17, -18, -24 -> {
                        val resetSecs = if (sid.isNotBlank()) logQuotaReset(sid) else 0L
                        WitaLog.d("Mega quota dead ($label, ~${resetSecs / 60} min) — next bucket")
                        if (sid.isNotBlank()) bucketCooldowns[sid] =
                            System.currentTimeMillis() + (resetSecs * 1000L).coerceIn(300_000L, 6 * 3600_000L)
                    }
                    -15, -16 -> WitaLog.d("Mega sid dead/blocked ($label) — remove from MEGA_SIDS")
                    -9 -> { WitaLog.d("Mega file removed"); return null }
                    else -> WitaLog.d("Mega api error $errCode ($label)")
                }
                continue
            }
            val obj = arr.optJSONObject(0) ?: continue
            val tl = obj.optLong("tl", 0L)
            if (tl > 0) {
                if (sid.isNotBlank()) bucketCooldowns[sid] = System.currentTimeMillis() + tl * 1000L
                continue
            }
            val size = obj.optLong("s", -1)
            val dl = obj.optString("g")
            if (size <= 0 || dl.isBlank()) continue
            val node = Node(dl, size, sid, null, System.currentTimeMillis())
            nodeCache[fileId] = node
            WitaLog.d("Mega node ${size / 1048576}MB ($label)")
            return node
        }

        // LAST RESORT: UltraSurf proxied anonymous bucket (fresh IP = fresh quota)
        if (UltraTunnel.enabled) {
            val res = UltraTunnel.apiPost(
                "https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}",
                """[{"a":"g","g":1,"ssl":1,"p":"$fileId"}]"""
            )
            if (res != null && res.second != excludeProxy) {
                val obj = try { JSONArray(res.first).optJSONObject(0) } catch (_: Exception) { null }
                if (obj != null && obj.optLong("tl", 0L) == 0L) {
                    val size = obj.optLong("s", -1)
                    val dl = obj.optString("g")
                    if (size > 0 && dl.isNotBlank()) {
                        val node = Node(dl, size, "", res.second, System.currentTimeMillis())
                        nodeCache[fileId] = node
                        WitaLog.d("Mega node via UltraTunnel ${res.second} (${size / 1048576}MB)")
                        return node
                    }
                }
            }
        }
        WitaLog.d("Mega ALL BUCKETS DRAINED (direct + proxy)")
        return null
    }

    suspend fun resolve(url: String): String? {
        return try {
            ensureAlive()
            val (fileId, aesKey, nonce) = parseSpec(url) ?: return null
            val cached = nodeCache[fileId]?.takeIf { System.currentTimeMillis() - it.ts < NODE_TTL }
            val node = cached ?: resolveNode(fileId) ?: return null
            val server = serverSocket ?: return null
            val token = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            files[token] = MegaFile(fileId, aesKey, nonce, node.size, lastSid = node.sid, proxy = node.proxy)
            if (node.proxy == null) prewarm(token, node.dl)
            "http://127.0.0.1:${server.localPort}/v/$authToken/$token.mp4"
        } catch (e: Exception) {
            WitaLog.e("Mega resolve fail: ${e.message}")
            null
        }
    }

    private suspend fun logQuotaReset(sid: String): Long {
        try {
            val body = """[{"a":"uq","xfer":1,"pro":1}]""".toRequestBody("application/json".toMediaType())
            val res = app.post("https://g.api.mega.co.nz/cs?id=${seq.incrementAndGet()}&sid=$sid",
                headers = mapOf("User-Agent" to EXTRACTOR_UA), requestBody = body).text
            val obj = (try { JSONArray(res) } catch (_: Exception) { null })?.optJSONObject(0) ?: return 0L
            val bt = obj.optLong("bt", 0L); val tar = obj.optLong("tar", 0L)
            val tah = obj.optJSONArray("tah")
            val used = tah?.let { a -> (0 until a.length()).sumOf { i -> a.optLong(i, 0L) } } ?: 0L
            if (used < bt && tar > 0) return 0L
            var add = true
            var timeLeft = 3600L - (bt % 3600L)
            if (tah != null) for (i in 0 until tah.length()) {
                if (tah.optLong(i, 0L) > 0L) add = false else if (add) timeLeft += 3600L
            }
            return timeLeft
        } catch (_: Exception) { return 0L }
    }

    private fun prewarm(token: String, dl: String) {
        pool.execute {
            try {
                val req = Request.Builder().url("$dl/0-1048575").header("User-Agent", EXTRACTOR_UA).build()
                app.baseClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.byteStream() ?: return@use
                        val bos = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var n: Int; var total = 0
                        while (body.read(buf).also { n = it } != -1 && total < 1_048_576) { bos.write(buf, 0, n); total += n }
                        files[token]?.head = bos.toByteArray()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun writeError(out: java.io.OutputStream, code: Int, msg: String, extra: String = "") {
        try {
            out.write("HTTP/1.1 $code $msg\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }

    private class Upstream(val stream: InputStream, val closer: () -> Unit)

    private fun openUpstream(node: Node, start: Long, end: Long): Upstream? {
        if (node.proxy != null) {
            val rc = UltraTunnel.openRange(node.dl, start, end, node.proxy) ?: return null
            return Upstream(rc.stream) { try { rc.socket.close() } catch (_: Exception) {} }
        }
        val resp = app.baseClient.newCall(
            Request.Builder().url("${node.dl}/$start-$end").header("User-Agent", EXTRACTOR_UA).build()
        ).execute()
        if (!resp.isSuccessful) {
            if (resp.code == 509 && node.sid.isNotBlank()) bucketCooldowns[node.sid] = System.currentTimeMillis() + 10 * 60_000L
            resp.close(); return null
        }
        val s = resp.body?.byteStream() ?: run { resp.close(); return null }
        return Upstream(s) { resp.close() }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("range:", true)) rangeHeader = line.substringAfter(":").trim()
            }

            val segs = path.trim('/').split('/')
            if (segs.size < 3 || segs[0] != "v") return
            if (segs[1] != authToken) { writeError(output, 403, "Forbidden"); return }
            val file = files[segs[2].substringBefore(".")] ?: run { writeError(output, 404, "Not Found"); return }

            var node = nodeCache[file.fileId]?.takeIf { System.currentTimeMillis() - it.ts < NODE_TTL }
                ?: runBlocking { resolveNode(file.fileId) }
            if (node == null) { writeError(output, 502, "Bad Gateway"); return }
            file.lastSid = node.sid; file.proxy = node.proxy
            val totalSize = node.size

            var start = 0L; var end = totalSize - 1; var isRange = false
            if (rangeHeader != null && rangeHeader.startsWith("bytes=", true)) {
                val spec = rangeHeader.substringAfter("=").substringBefore(",").trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val left = spec.substring(0, dash).trim()
                    val right = spec.substring(dash + 1).trim()
                    if (left.isEmpty() && right.isNotEmpty()) {
                        val n = right.toLongOrNull() ?: 0L
                        if (n > 0) { start = (totalSize - n).coerceAtLeast(0L); isRange = true }
                    } else if (left.isNotEmpty()) {
                        val s = left.toLongOrNull()
                        if (s != null && s >= totalSize) {
                            writeError(output, 416, "Range Not Satisfiable", "Content-Range: bytes */$totalSize\r\n"); return
                        }
                        if (s != null) {
                            start = s
                            end = right.toLongOrNull()?.coerceAtMost(totalSize - 1) ?: (totalSize - 1)
                            if (end >= start) isRange = true
                        }
                    }
                }
            }
            val contentLen = end - start + 1
            val headers = buildString {
                append("HTTP/1.1 ${if (isRange) "206 Partial Content" else "200 OK"}\r\n")
                append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                append("Content-Length: $contentLen\r\n")
                if (isRange) append("Content-Range: bytes $start-$end/$totalSize\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            if (method == "HEAD") { output.write(headers); output.flush(); return }
            if (method != "GET") { writeError(output, 405, "Method Not Allowed"); return }

            val head = file.head
            if (head != null && end < head.size) {
                try {
                    val out = decryptRange(file, head, start, end)
                    if (out != null && out.isNotEmpty()) { output.write(headers); output.write(out); output.flush(); return }
                } catch (_: Exception) {}
            }

            val alignedStart = (start / 16) * 16
            val skip = (start - alignedStart).toInt()

            var up = openUpstream(node, alignedStart, end)
            if (up == null) {
                node = runBlocking { resolveNode(file.fileId, excludeSid = node.sid, excludeProxy = node.proxy) }
                    ?: run { writeError(output, 502, "Upstream Expired"); return }
                file.lastSid = node.sid; file.proxy = node.proxy
                nodeCache[file.fileId] = node
                up = openUpstream(node, alignedStart, end)
            }
            if (up == null) { writeError(output, 502, "Upstream Error"); return }

            try {
                val iv = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).put(file.nonce).putLong(alignedStart / 16).array()
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(file.aesKey, "AES"), IvParameterSpec(iv))
                output.write(headers)
                val buf = ByteArray(64 * 1024)
                var n: Int; var toSkip = skip; var remaining = contentLen; var sinceFlush = 0
                while (remaining > 0) {
                    n = up.stream.read(buf)
                    if (n <= 0) break
                    val dec = cipher.update(buf, 0, n) ?: continue
                    var off = 0; var avail = dec.size
                    if (toSkip > 0) {
                        if (avail <= toSkip) { toSkip -= avail; continue }
                        off = toSkip; avail -= toSkip; toSkip = 0
                    }
                    val writeCount = minOf(avail.toLong(), remaining).toInt()
                    if (writeCount > 0) {
                        output.write(dec, off, writeCount)
                        remaining -= writeCount; sinceFlush += writeCount
                        if (sinceFlush >= 256 * 1024) { output.flush(); sinceFlush = 0 }
                    }
                }
                output.flush()
            } finally { up.closer() }
        } catch (_: Exception) {
        } finally { try { socket.close() } catch (_: Exception) {} }
    }

    private fun decryptRange(file: MegaFile, data: ByteArray, start: Long, end: Long): ByteArray? {
        val iv = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).put(file.nonce).putLong(start / 16).array()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(file.aesKey, "AES"), IvParameterSpec(iv))
        val skip = (start % 16).toInt()
        if (skip > 0) cipher.update(ByteArray(skip))
        return cipher.doFinal(data, start.toInt(), (end - start + 1).toInt())
    }
}
