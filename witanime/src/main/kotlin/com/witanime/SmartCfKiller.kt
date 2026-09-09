package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewTreeObserver
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
import com.lagradost.cloudstream3.ui.settings.Globals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private object ActivityResolver {
    private val resumed = AtomicReference<WeakReference<Activity>?>(null)
    private val registered = AtomicBoolean(false)

    fun warmup() {
        runCatching {
            val a = CommonActivity.activity
            if (a != null && !a.isFinishing && !a.isDestroyed) resumed.set(WeakReference(a))
        }
        ensureRegistered()
    }

    fun current(): Activity? {
        runCatching {
            val a = CommonActivity.activity
            if (a != null && !a.isFinishing && !a.isDestroyed) return a
        }
        ensureRegistered()
        resumed.get()?.get()?.let { a ->
            if (!a.isFinishing && !a.isDestroyed) return a
        }
        return scanActivityRecords()
    }

    private fun application(): Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val cur = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
        at.getMethod("getApplication").invoke(cur) as? Application
    }.getOrNull()

    private fun ensureRegistered() {
        if (registered.get()) return
        val app = application() ?: return
        if (!registered.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed.set(WeakReference(a)) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun scanActivityRecords(): Activity? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val cur = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
        val recordsField = at.getDeclaredField("mActivities")
        recordsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val records = recordsField.get(cur) as? Map<Any, Any> ?: return@runCatching null
        for (record in records.values) {
            val act = runCatching {
                val f = record.javaClass.getDeclaredField("activity")
                f.isAccessible = true
                f.get(record) as? Activity
            }.getOrNull() ?: continue
            if (!act.isFinishing && !act.isDestroyed) return@runCatching act
        }
        null
    }.getOrNull()
}

object WitaWeb {

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "checking your browser", "attention required",
        "verify you are human", "one more step", "ddos-guard"
    )

    // [v159] hidden renders now run in PARALLEL (up to 3 at once) — the old
    // single mutex serialized every page behind every other page, which under
    // WARP (where every fetch is a render) made the whole extension crawl
    private val renderSlots = Semaphore(3)

    private class CacheEntry(val html: String, val ts: Long)
    private val htmlCache = ConcurrentHashMap<String, CacheEntry>()

    // [v159] 10 minutes — going back after an episode hits the cache
    private const val CACHE_TTL = 600_000L

    // [v159] wrong states (e.g. WARP left replay=false, now on real IP) self-correct in 2 min
    private const val REPLAY_TTL = 2 * 60_000L

    @Volatile var wasChallenged: Boolean = false

    /** the WebView user-agent the clearance was earned with — persisted to disk */
    @Volatile var webUa: String? = null
        set(value) {
            field = value
            if (value != null) persistUa(value)
        }

    @Volatile private var replayWorks: Boolean? = null
    @Volatile private var replayCheckedAt = 0L

    private const val UA_PREFS = "wita_cf_prefs"
    private const val UA_KEY = "web_ua"

    private fun appCtx(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val cur = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
        at.getMethod("getApplication").invoke(cur) as? Context
    }.getOrNull()

    private fun persistUa(ua: String) {
        try {
            appCtx()?.getSharedPreferences(UA_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(UA_KEY, ua)?.apply()
        } catch (_: Exception) {}
    }

    private fun loadPersistedUa(): String? {
        return try {
            appCtx()?.getSharedPreferences(UA_PREFS, Context.MODE_PRIVATE)
                ?.getString(UA_KEY, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    fun warmup() {
        ActivityResolver.warmup()
        if (webUa == null) {
            webUa = loadPersistedUa()
        }
    }

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

    /** [v159] remove a proven-dead clearance cookie so nothing keeps sending it —
     *  a stale clearance makes Cloudflare serve HARDER challenges */
    fun expireClearance(url: String) {
        try {
            val host = try {
                val u = Uri.parse(url)
                "${u.scheme}://${u.host}"
            } catch (_: Exception) { url }
            CookieManager.getInstance().apply {
                listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                    setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
                flush()
            }
        } catch (_: Exception) {}
    }

    suspend fun fetchHtml(url: String, silent: Boolean = false): String? {
        htmlCache[url]?.let { e ->
            if (System.currentTimeMillis() - e.ts < CACHE_TTL) return e.html
            htmlCache.remove(url)
        }
        return renderSlots.withPermit {
            htmlCache[url]?.let { e ->
                if (System.currentTimeMillis() - e.ts < CACHE_TTL) return@withPermit e.html
            }
            WitaLog.d("hidden render (invisible) for $url")
            val hidden = HiddenRender.fetch(url)
            if (hidden != null) {
                WitaLog.d("hidden render OK — no UI shown")
                htmlCache[url] = CacheEntry(hidden, System.currentTimeMillis())
                return@withPermit hidden
            }
            if (silent) {
                WitaLog.d("silent mode (background prefetch) — dialog suppressed")
                return@withPermit null
            }
            WitaLog.d("challenge needs a human → visible dialog")
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
    settings.userAgentString = settings.userAgentString
        .replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+ "), "")
}

/**
 * [v159] hidden renderer: fresh full-size WebView per render (v153 proven
 * design), pushed off-screen. Poll/settle tightened for speed (250ms/350ms).
 * The CfAutoClick loop robot-clicks the CF box invisibly after ~1s.
 */
private object HiddenRender {
    private const val POLL_MS = 250L      // [v159] faster poll — pages that pass need speed
    private const val SETTLE_MS = 350L    // [v159] content HTML rarely needs a full second
    private const val CHALLENGE_GRACE_MS = 15_000L
    private const val HARD_TIMEOUT_MS = 30_000L
    private const val MIN_HTML_LEN = 2_000

    @Volatile var lastSuccessAt: Long = 0L

    // NOTE: no stale-cookie clearing here on purpose — the hidden renderer may
    // still pass silently with existing cookies. Clearing belongs in the
    // fallback dialog only, where the cookie is proven dead.
    suspend fun fetch(url: String): String? = withContext(Dispatchers.Main) {
        val activity: Activity? = ActivityResolver.current()
        if (activity == null) {
            WitaLog.d("no activity available — hidden render skipped")
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            var webView: WebView? = null
            var container: FrameLayout? = null
            var capturedUa: String? = null
            val startedAt = SystemClock.uptimeMillis()

            fun cleanup() {
                handler.removeCallbacksAndMessages(null)
                val c = container
                container = null
                val wv = webView
                webView = null
                try { (c?.parent as? ViewGroup)?.removeView(c) } catch (_: Exception) {}
                try { wv?.stopLoading() } catch (_: Exception) {}
                try { wv?.destroy() } catch (_: Exception) {}
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
                // [v157] invisible auto-clicker — robot-clicks the CF checkbox
                CfAutoClick.startAutoClickLoop(wv, handler) { done.get() }
                capturedUa = wv.settings.userAgentString
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(wv, true)
                }

                val c = FrameLayout(activity)
                container = c
                c.addView(wv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                activity.addContentView(c, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                // full size (Cloudflare needs real dimensions) but off-screen
                c.translationX = -10000f
                c.translationY = -10000f

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
                WitaLog.e("hidden render error ${e.message}")
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
        val activity: Activity? = ActivityResolver.current()
        if (activity == null) {
            WitaLog.d("no activity — dialog skipped")
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val dlg = WitaRenderDialog(activity, url, timeoutMs) { html ->
                if (cont.isActive) cont.resume(html)
            }
            try {
                dlg.show()
            } catch (e: Exception) {
                WitaLog.e("dialog error ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { dlg.cancel() }
        }
    }
}

object WitaImgProxy {

    private var serverSocket: ServerSocket? = null

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
                WitaLog.d("image proxy listening on ${server.localPort}")
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
                WitaLog.e("image proxy start fail: ${e.message}")
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

    private fun writeImage(output: java.io.OutputStream, bytes: ByteArray, contentType: String) {
        try {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun fetchImage(target: String, ua: String?, cookie: String?): Pair<ByteArray, String>? {
        return try {
            val builder = Request.Builder().url(target)
                .header("Referer", "https://witanime.you/")
            if (ua != null) builder.header("User-Agent", ua)
            if (cookie != null) builder.header("Cookie", cookie)
            app.baseClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val ct = resp.header("Content-Type") ?: ""
                if (!ct.startsWith("image/")) return null
                val b = resp.body?.bytes() ?: return null
                if (b.isEmpty() || b.size > 24 * 1024 * 1024) return null
                b to ct
            }
        } catch (_: Exception) { null }
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

        while (true) {
            val l = input.readLine() ?: break
            if (l.isEmpty()) break
        }

        val cached = synchronized(cache) { cache[target] }
        if (cached != null) {
            writeImage(output, cached, "image/jpeg")
            return
        }

        val cookie = runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
        val ua = WitaWeb.webUa
        var result: Pair<ByteArray, String>? = null
        if (ua != null) {
            result = fetchImage(target, ua, cookie)
        }
        if (result == null) {
            result = fetchImage(target, null, null)
        }

        if (result == null) { write404(output); return }
        val (bytes, contentType) = result
        synchronized(cache) { cache[target] = bytes }
        writeImage(output, bytes, contentType)
    }

    fun proxyUrl(target: String): String? {
        val port = start() ?: return null
        return try {
            "http://127.0.0.1:$port/img?u=" + java.net.URLEncoder.encode(target, "UTF-8")
        } catch (_: Exception) { null }
    }
}

/**
 * [v158] the fallback dialog: stale CF cookies are EXPIRED before loading
 * (proven dead here, and a dead clearance makes CF serve a harder challenge),
 * D-pad fake cursor for Android TV, Done button to force extraction.
 * [v157] CfAutoClick runs here too — often solves before the overlay lifts.
 */
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
        private const val CURSOR_STEP_DP = 24f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var overlay: View? = null
    private var cursorView: View? = null
    private var holderRef: FrameLayout? = null
    private val cursorPos = floatArrayOf(0f, 0f)
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

    // ---- TV D-pad cursor ----

    private fun moveCursor(dx: Float, dy: Float) {
        val holder = holderRef ?: return
        val size = cursorView?.layoutParams?.width ?: 0
        cursorPos[0] = (cursorPos[0] + dx).coerceIn(0f, holder.width.toFloat() - size)
        cursorPos[1] = (cursorPos[1] + dy).coerceIn(0f, holder.height.toFloat() - size)
        cursorView?.translationX = cursorPos[0] - size / 2f
        cursorView?.translationY = cursorPos[1] - size / 2f
    }

    private fun clickAtCursor() {
        val wv = webView ?: return
        val x = cursorPos[0]
        val y = cursorPos[1]
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, x, y, 0)
        try {
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
        } catch (_: Exception) {
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val isTv = try { Globals.isLayout(Globals.TV) } catch (e: Throwable) { false }

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

        root.addView(TextView(activity).apply {
            text = if (isTv) "D-pad moves the cursor, OK/Enter clicks the checkbox."
                   else "Solve the CAPTCHA below, then tap Done."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, dp(8))
        })

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).also { it.bottomMargin = dp(10) })

        val holder = FrameLayout(activity)
        holderRef = holder
        val wv = WebView(activity)
        webView = wv
        wv.configForCf()
        // [v157] auto-click here too — often solves the challenge BEFORE the
        // overlay lifts at 4s, so the user never even sees the checkbox
        CfAutoClick.startAutoClickLoop(wv, handler) { done.get() }
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

        if (isTv) {
            val cursorSize = (22 * density).toInt()
            val cursor = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.argb(160, 255, 50, 50))
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
            }
            cursorView = cursor
            holder.addView(cursor)   // last child = on top of the cover

            holder.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    holder.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    cursorPos[0] = holder.width / 2f
                    cursorPos[1] = holder.height / 2f
                    cursor.translationX = cursorPos[0] - cursorSize / 2f
                    cursor.translationY = cursorPos[1] - cursorSize / 2f
                }
            })

            val step = CURSOR_STEP_DP * density
            holder.isFocusable = true
            holder.isFocusableInTouchMode = true
            holder.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveCursor(0f, -step); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveCursor(0f, step); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveCursor(-step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveCursor(step, 0f); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { clickAtCursor(); true }
                    else -> false
                }
            }
        }

        root.addView(holder, LinearLayout.LayoutParams(-1, (screenH * 0.7f).toInt()))

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                extractNow()
                if (!done.get()) status("No cookies yet. Solve the CAPTCHA first.")
            }
        })
        btnRow.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finish(null) }
        })
        root.addView(
            btnRow,
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
            // [v158] the old clearance is PROVEN dead at this point (hidden render
            // already failed with it) and Cloudflare serves a HARDER challenge when
            // a dead clearance is present — expire it so the solve starts clean
            val host = try {
                val u = Uri.parse(targetUrl)
                "${u.scheme}://${u.host}"
            } catch (_: Exception) { targetUrl }
            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                try { setCookie(host, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT") } catch (_: Exception) {}
            }
            flush()
        }

        d.show()
        d.window?.apply {
            setLayout((screenW * 0.95f).toInt(), (screenH * 0.9f).toInt())
            setBackgroundDrawable(ColorDrawable(0xFF16161C.toInt()))
        }
        wv.requestFocus()
        if (isTv) holder.requestFocus()
        wv.loadUrl(targetUrl)
        handler.postDelayed(poll, POLL_MS)
    }
}
