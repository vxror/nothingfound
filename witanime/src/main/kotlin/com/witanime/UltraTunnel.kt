package com.witanime

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** TLS-to-proxy tunnel (PAC "HTTPS host:443" semantics) for Mega failover.
 *  Real cert + hostname verification on BOTH legs. Best-effort, rotates dead hosts. */
object UltraTunnel {
    private val POOL_A = listOf(
        "efr-marktplatz.club","voteshirleysew.club","carolinafreigh.fun","purposebydesig.club",
        "mmgr.club","webdrone.club","ictabernacle.info","jewelscollecti.icu","berniesbodybla.fun",
        "fullmanfirm.icu","miamigoskennel.fun","goedkoopgeluid.icu","tzoa.fun","kimberlymilano.icu",
        "crosscards.site","jgwynphotoarts.pro","indextraub.online","purecambogiasl.live",
        "zebpay.site","southwestcoast.pro"
    )
    private val POOL_B = listOf(
        "www.mohegansuncasi.online","www.hullwiper.live","www.anokhiiwade.info","www.tecnopay.online",
        "www.i-mopachores.live","www.wpfaststart.info","www.art1p.pro","www.roccosiffredi.online",
        "www.saifrizvi.live","www.rmtonthebeach.info","www.donelikeyester.space","www.yoakumselfstor.website",
        "www.passportsusa.space","www.uatgc.website","www.ahikeleher.site","www.helpinghandsof.space",
        "www.onedaijo.website","www.templarknights.site","www.aoeah.space","www.stratfordrc.website",
        "www.all4women.space","www.keukataxi.website","www.carimflex.site","www.peacefulbodies.xyz",
        "www.vogelsenmeer.xyz","www.9ts3tpia.world","www.ruimte-18.xyz","www.nogielectric.world",
        "www.bodrumexpresso.xyz","www.jaqandlelights.world"
    )

    @Volatile var enabled = true
    private val deadUntil = ConcurrentHashMap<String, Long>()
    private val rnd = SecureRandom()

    fun penalize(host: String, ms: Long = 10 * 60_000L) { deadUntil[host] = System.currentTimeMillis() + ms }

    private fun pool(): List<String> {
        val now = System.currentTimeMillis()
        return (POOL_A + POOL_B).filter { now >= (deadUntil[it] ?: 0L) }.shuffled(kotlin.random.Random(rnd.nextLong()))
    }

    private fun sni(sock: SSLSocket, host: String) {
        if (Build.VERSION.SDK_INT >= 24) try {
            val p = sock.sslParameters
            p.serverNames = listOf(SNIHostName(host))
            sock.sslParameters = p
        } catch (_: Exception) {}
    }

    private fun readLine(inp: InputStream): String {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) bos.write(b)
        }
        return bos.toString("US-ASCII")
    }

    private fun tunnel(proxy: String, target: String): SSLSocket? {
        var ps: SSLSocket? = null
        return try {
            val f = SSLContext.getDefault().socketFactory
            ps = f.createSocket() as SSLSocket
            ps.connect(InetSocketAddress(proxy, 443), 8_000)
            ps.soTimeout = 12_000
            sni(ps, proxy)
            ps.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(proxy, ps.session)) {
                ps.close(); penalize(proxy); return null
            }
            ps.getOutputStream().apply {
                write("CONNECT $target:443 HTTP/1.1\r\nHost: $target:443\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
            }
            val status = readLine(ps.getInputStream())
            if (!status.contains(" 200")) { ps.close(); penalize(proxy, 5 * 60_000L); return null }
            while (readLine(ps.getInputStream()).isNotEmpty()) {}
            val ts = f.createSocket(ps, target, 443, true) as SSLSocket
            ts.useClientMode = true
            sni(ts, target)
            ts.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target, ts.session)) { ts.close(); return null }
            ts.soTimeout = 30_000
            ts
        } catch (_: Exception) {
            try { ps?.close() } catch (_: Exception) {}
            penalize(proxy, 5 * 60_000L); null
        }
    }

    fun apiPost(url: String, json: String): Pair<String, String>? {
        if (!enabled) return null
        val u = java.net.URI(url)
        val host = u.host
        val path = if (u.rawQuery != null) "${u.rawPath}?${u.rawQuery}" else u.rawPath
        val payload = json.toByteArray(Charsets.UTF_8)
        for (proxy in pool().take(6)) {
            val s = tunnel(proxy, host) ?: continue
            try {
                s.getOutputStream().apply {
                    write(("POST $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $EXTRACTOR_UA\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(payload); flush()
                }
                val inp = s.getInputStream()
                val status = readLine(inp)
                var chunked = false; var len = -1L
                while (true) {
                    val l = readLine(inp); if (l.isEmpty()) break
                    val low = l.lowercase()
                    if (low.startsWith("transfer-encoding:") && low.contains("chunked")) chunked = true
                    if (low.startsWith("content-length:")) len = l.substringAfter(":").trim().toLongOrNull() ?: -1L
                }
                if (!status.contains(" 200")) continue
                val body = readBody(inp, chunked, len)
                if (body.isNotEmpty()) return String(body, Charsets.UTF_8) to proxy
            } catch (_: Exception) { penalize(proxy, 5 * 60_000L) }
            finally { try { s.close() } catch (_: Exception) {} }
        }
        return null
    }

    class RangeConn(val socket: SSLSocket, val stream: InputStream, val status: Int, val proxy: String)

    fun openRange(dlUrl: String, start: Long, end: Long, preferred: String? = null): RangeConn? {
        if (!enabled) return null
        val u = java.net.URI(dlUrl)
        val host = u.host
        val path = "${u.rawPath}/$start-$end"
        val order = (listOfNotNull(preferred) + pool()).distinct().take(6)
        for (proxy in order) {
            val s = tunnel(proxy, host) ?: continue
            try {
                s.getOutputStream().apply {
                    write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: $EXTRACTOR_UA\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)); flush()
                }
                val inp = s.getInputStream()
                val code = Regex("""\s(\d{3})\s""").find(readLine(inp))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                var chunked = false
                while (true) {
                    val l = readLine(inp); if (l.isEmpty()) break
                    val low = l.lowercase()
                    if (low.startsWith("transfer-encoding:") && low.contains("chunked")) chunked = true
                }
                if (code == 200 || code == 206) return RangeConn(s, if (chunked) ChunkedStream(inp) else inp, code, proxy)
                if (code == 509) penalize(proxy)
                try { s.close() } catch (_: Exception) {}
            } catch (_: Exception) {
                try { s.close() } catch (_: Exception) {}
                penalize(proxy, 5 * 60_000L)
            }
        }
        return null
    }

    private fun readBody(inp: InputStream, chunked: Boolean, len: Long): ByteArray {
        val src = if (chunked) ChunkedStream(inp) else inp
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n); total += n
            if (len in 1..total || total > 2 * 1024 * 1024) break
        }
        return bos.toByteArray()
    }

    private class ChunkedStream(private val inp: InputStream) : InputStream() {
        private var remaining = 0L
        private var started = false
        private var eof = false
        private fun nextChunk(): Boolean {
            val size = readLine(inp).trim().substringBefore(";").trim().toLongOrNull(16) ?: return false
            if (size == 0L) { readLine(inp); eof = true; return false }
            remaining = size; return true
        }
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (!started || remaining == 0L) {
                if (started) readLine(inp)
                if (!nextChunk()) return -1
                started = true
            }
            val n = inp.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n <= 0) { eof = true; return -1 }
            remaining -= n
            return n
        }
    }
}
