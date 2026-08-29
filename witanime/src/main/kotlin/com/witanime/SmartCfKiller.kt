package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object WitaWeb {

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "checking your browser", "attention required",
        "verify you are human", "one more step", "ddos-guard"
    )

    private val renderMutex = Mutex()

    private class CacheEntry(val html: String, val ts: Long)
    private val htmlCache = ConcurrentHashMap<String, CacheEntry>()
    private const val CACHE_TTL = 30_000L
    private const val REPLAY_TTL = 10 * 60_000L

    @Volatile var wasChallenged: Boolean = false

    /** the WebView user-agent the clearance was earned with (its REAL UA,
     *  cleaned of WebView tells — NOT the app UA; CF rejects mismatched UAs) */
    @Volatile var webUa: String? = null

    @Volatile private var replayWorks: Boolean? = null
    @Volatile private var replayCheckedAt = 0L

    fun looksChallenge(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val l = html.lowercase()
        if (CHALLENGE_TITLES.any { l.contains(it) }) return true
        return l.contains("challenge-platform") && l.contains("cloudflare")
    }

    fun isChallengeTitle(title: String): Boolean {
        val l = title.lowercase()
        return CHALLENGE_TITLES.any { l.contains(it) }
    }

    fun replayWorks(): Boolean? =
        if (System.currentTimeMillis() - replayCheckedAt < REPLAY_TTL) replayWorks else null

    fun setReplayWorks(v: Boolean) {
        replayWorks = v
        replayCheckedAt = System.currentTimeMillis()
    }

    fun renderedRecently(): Boolean =
        HiddenRender.lastSuccessAt > 0 && System.currentTimeMillis() - HiddenRender.lastSuccessAt < 120_000L

    suspend fun clearanceCookie(url: String): String? = withContext(Dispatchers.Main) {
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?.takeIf { it.contains("cf_clearance") }
    }

    suspend fun fetchHtml(url: String): String? {
        htmlCache[url]?.let { e ->
            if (System.currentTimeMillis() - e.ts < CACHE_TTL) return e.html
            htmlCache.remove(url)
        }
        return renderMutex.withLock {
            htmlCache[url]?.let { e ->
                if (System.currentTimeMillis() - e.ts < CACHE_TTL) return@withLock e.html
            }
            println("WitaCF: hidden render (invisible) for $url")
            val hidden = HiddenRender.fetch(url)
            if (hidden != null) {
                println("WitaCF: hidden render OK — no UI shown")
                return@withLock hidden
            }
            println("WitaCF: challenge needs a human → visible dialog")
            val html = VisibleRender.fetch(url, 120_000L)
            if (html != null) {
                htmlCache[url] = CacheEntry(html, System.currentTimeMillis())
            }
            html
        }
    }
}

private fun unwrapJs(result: String?): String? {
    if (result == null || result == "null") return null
    return try {
        JSONArray("[$result]").optString(0)
    } catch (e: Exception) {
        result
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configForCf() {
    isFocusable = true
    isFocusableInTouchMode = true
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadsImagesAutomatically = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.mediaPlaybackRequiresUserGesture = false
    // [v143 — REVERTED] the WebView must keep its REAL UA (only the WebView
    // tells removed). v142 forced the app UA and CF refused to issue clearance
    // because the UA string didn't match the browser's actual fingerprint —
    // the challenge then never completes, even in the visible dialog.
    settings.userAgentString = settings.userAgentString
        .replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+ "), "")
}

private object HiddenRender {
    private const val POLL_MS = 400L
    private const val SETTLE_MS = 1_000L
    private const val CHALLENGE_GRACE_MS = 8_000L
    private const val HARD_TIMEOUT_MS = 20_000L
    private const val MIN_HTML_LEN = 2_000

    @Volatile var lastSuccessAt: Long = 0L

    suspend fun fetch(url: String): String? = withContext(Dispatchers.Main) {
        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            var webView: WebView? = null
            var capturedUa: String? = null
            val startedAt = SystemClock.uptimeMillis()

            fun cleanup() {
                handler.removeCallbacksAndMessages(null)
                val wv = webView ?: return
                webView = null
                try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                try { wv.stopLoading() } catch (_: Exception) {}
                try { wv.destroy() } catch (_: Exception) {}
            }

            fun finish(html: String?) {
                if (!done.compareAndSet(false, true)) return
                if (html != null) {
                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                    capturedUa?.let { WitaWeb.webUa = it }
                    lastSuccessAt = System.currentTimeMillis()
                }
                cleanup()
                if (cont.isActive) cont.resume(html)
            }

            fun extractNow() {
                val wv2 = webView ?: return
                wv2.evaluateJavascript("document.documentElement.outerHTML") { jsHtml ->
                    if (done.get()) return@evaluateJavascript
                    val html = unwrapJs(jsHtml)
                    if (html != null && html.length > MIN_HTML_LEN && !WitaWeb.looksChallenge(html)) {
                        finish(html)
                    }
                }
            }

            try {
                val wv = WebView(activity)
                webView = wv
                wv.configForCf()
                capturedUa = wv.settings.userAgentString
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(wv, true)
                }
                activity.addContentView(wv, ViewGroup.LayoutParams(1, 1))
                wv.loadUrl(url)

                val poll = object : Runnable {
                    override fun run() {
                        if (done.get()) return
                        val elapsed = SystemClock.uptimeMillis() - startedAt
                        if (webView == null) return
                        if (elapsed > HARD_TIMEOUT_MS) { finish(null); return }
                        webView?.evaluateJavascript("document.title") { jsTitle ->
                            if (done.get()) return@evaluateJavascript
                            val title = unwrapJs(jsTitle).orEmpty()
                            if (WitaWeb.isChallengeTitle(title)) {
                                if (elapsed > CHALLENGE_GRACE_MS) finish(null)
                                else handler.postDelayed(this, POLL_MS)
                                return@evaluateJavascript
                            }
                            handler.postDelayed({
                                if (!done.get()) {
                                    extractNow()
                                    if (!done.get()) handler.postDelayed(this, POLL_MS)
                                }
                            }, SETTLE_MS)
                        }
                    }
                }
                handler.postDelayed(poll, POLL_MS)
            } catch (e: Exception) {
                println("WitaCF: hidden render error ${e.message}")
                finish(null)
            }

            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    handler.post { cleanup() }
                }
            }
        }
    }
}

private object VisibleRender {
    suspend fun fetch(url: String, timeoutMs: Long): String? = withContext(Dispatchers.Main) {
        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val dlg = WitaRenderDialog(activity, url, timeoutMs) { html ->
                if (cont.isActive) cont.resume(html)
            }
            try {
                dlg.show()
            } catch (e: Exception) {
                println("WitaCF: dialog error ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { dlg.cancel() }
        }
    }
}

/**
 * [v143 NEW] Local image proxy. The app's image loader (Coil) sends the app UA,
 * but the clearance cookie is bound to the WebView's UA — Cloudflare rejects the
 * mismatch. We can't change Coil's UA, so instead posters under WARP point at
 * this localhost server, which fetches each image with the EXACT UA + cookie
 * the clearance was earned with. This reproduces the v135 working image path.
 */
object WitaImgProxy {

    private var serverSocket: ServerSocket? = null

    // small LRU so scrolling doesn't re-fetch
    private val cache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            return values.sumOf { it.size } > 24 * 1024 * 1024
        }
    }

    fun start(): Int? {
        synchronized(this) {
            if (serverSocket != null && serverSocket?.isClosed == false) return serverSocket?.localPort
            return try {
                val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                println("WitaCF: image proxy listening on ${server.localPort}")
                Thread {
                    while (!server.isClosed) {
                        try {
                            val socket = server.accept()
                            Thread {
                                try { handle(socket) } catch (_: Exception) {}
                                finally { try { socket.close() } catch (_: Exception) {} }
                            }.apply { isDaemon = true }.start()
                        } catch (e: Exception) { if (server.isClosed) break }
                    }
                }.apply { isDaemon = true; name = "WitaImgProxy-Accept" }.start()
                server.localPort
            } catch (e: Exception) {
                println("WitaCF: image proxy start fail: ${e.message}")
                null
            }
        }
    }

    private fun write404(output: java.io.OutputStream) {
        try {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 20_000
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val output = socket.getOutputStream()
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2 || !parts[1].startsWith("/img")) { write404(output); return }
        val target = Uri.parse("http://localhost" + parts[1]).getQueryParameter("u")
        if (target.isNullOrBlank() || !target.startsWith("http")) { write404(output); return }

        // drain headers
        while (true) {
            val l = input.readLine() ?: break
            if (l.isEmpty()) break
        }

        val cached = synchronized(cache) { cache[target] }
        if (cached != null) {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${cached.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(cached)
            output.flush()
            return
        }

        val cookie = runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
        val ua = WitaWeb.webUa

        val req = Request.Builder().url(target)
            .header("Referer", "https://witanime.you/")
            .apply {
                ua?.let { header("User-Agent", it) }
                cookie?.let { header("Cookie", it) }
            }
            .build()

        app.baseClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { write404(output); return }
            val contentType = resp.header("Content-Type") ?: ""
            if (!contentType.startsWith("image/")) { write404(output); return } // challenged/garbage
            val bytes = resp.body?.bytes() ?: run { write404(output); return }
            if (bytes.isEmpty() || bytes.size > 24 * 1024 * 1024) { write404(output); return }
            synchronized(cache) { cache[target] = bytes }
            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
        }
    }

    fun proxyUrl(target: String): String? {
        val port = start() ?: return null
        return try {
            "http://127.0.0.1:$port/img?u=" + java.net.URLEncoder.encode(target, "UTF-8")
        } catch (_: Exception) { null }
    }
}

private class WitaRenderDialog(
    private val activity: Activity,
    private val targetUrl: String,
    private val timeoutMs: Long,
    private val onResult: (String?) -> Unit
) {
    companion object {
        private const val POLL_MS = 600L
        private const val REVEAL_AFTER_MS = 4_000L
        private const val SETTLE_MS = 1_000L
        private const val MIN_HTML_LEN = 2_000
    }

    private val handler = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var overlay: View? = null
    private val startedAt = SystemClock.uptimeMillis()

    private fun status(msg: String) { statusText?.text = msg }

    private fun liftOverlay() {
        overlay?.let { ov ->
            (ov.parent as? ViewGroup)?.removeView(ov)
            overlay = null
            status("Solve the verification below ↓")
        }
    }

    private fun finish(html: String?) {
        if (!done.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        if (html != null) {
            try { CookieManager.getInstance().flush() } catch (_: Exception) {}
            webView?.settings?.userAgentString?.takeIf { it.isNotBlank() }?.let { WitaWeb.webUa = it }
        }
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
        val wv = webView
        webView = null
        wv?.let {
            try { (it.parent as? ViewGroup)?.removeView(it) } catch (_: Exception) {}
            try { it.stopLoading() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        try { onResult(html) } catch (_: Exception) {}
    }

    fun cancel() = finish(null)

    private fun extractNow() {
        val wv = webView ?: return
        wv.evaluateJavascript("document.documentElement.outerHTML") { jsHtml ->
            if (done.get()) return@evaluateJavascript
            val html = unwrapJs(jsHtml)
            if (html != null && html.length > MIN_HTML_LEN && !WitaWeb.looksChallenge(html)) {
                status("Loaded ✓")
                finish(html)
            }
        }
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (done.get()) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            if (elapsed > timeoutMs) {
                status("Timed out — انتهت المهلة")
                finish(null)
                return
            }
            val wv = webView
            if (wv == null) {
                handler.postDelayed(this, POLL_MS)
                return
            }
            if (elapsed > REVEAL_AFTER_MS && overlay?.parent != null) liftOverlay()

            wv.evaluateJavascript("document.title") { jsTitle ->
                if (done.get()) return@evaluateJavascript
                val title = unwrapJs(jsTitle).orEmpty()
                if (WitaWeb.isChallengeTitle(title)) {
                    status("Verifying… (${elapsed / 1000}s)")
                    handler.postDelayed(poll, POLL_MS)
                    return@evaluateJavascript
                }
                status("Loading… (${elapsed / 1000}s)")
                handler.postDelayed({
                    if (!done.get()) {
                        extractNow()
                        if (!done.get()) handler.postDelayed(poll, POLL_MS)
                    }
                }, SETTLE_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }

        root.addView(TextView(activity).apply {
            text = "WitAnime — Cloudflare Verification"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })

        val status = TextView(activity).apply {
            text = "Loading…"
            textSize = 12f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, dp(8))
        }
        statusText = status
        root.addView(status)

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).also { it.bottomMargin = dp(10) })

        val holder = FrameLayout(activity)
        val wv = WebView(activity)
        webView = wv
        wv.configForCf()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
        }
        holder.addView(wv, FrameLayout.LayoutParams(-1, -1))

        val cover = FrameLayout(activity).apply {
            setBackgroundColor(0xFF0B0B0F.toInt())
            isClickable = true
        }
        val chip = TextView(activity).apply {
            text = "Verifying protected source…"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        cover.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        overlay = cover
        holder.addView(cover, FrameLayout.LayoutParams(-1, -1))

        root.addView(holder, LinearLayout.LayoutParams(-1, (screenH * 0.7f).toInt()))

        val cancel = Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finish(null) }
        }
        root.addView(
            cancel,
            LinearLayout.LayoutParams(-2, -2).also {
                it.topMargin = dp(10)
                it.gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.setCancelable(false)
        d.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!done.get()) {
                done.set(true)
                try { onResult(null) } catch (_: Exception) {}
            }
        }
        dialog = d

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
            flush()
        }

        d.show()
        d.window?.apply {
            setLayout((screenW * 0.95f).toInt(), (screenH * 0.9f).toInt())
            setBackgroundDrawable(ColorDrawable(0xFF16161C.toInt()))
        }
        wv.requestFocus()
        wv.loadUrl(targetUrl)
        handler.postDelayed(poll, POLL_MS)
    }
}
