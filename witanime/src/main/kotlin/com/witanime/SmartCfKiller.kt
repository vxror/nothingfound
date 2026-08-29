package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val WITA_CF_TAG = "WitaCF"

private val CF_BLOCK_PHRASES = listOf(
    "just a moment", "checking your browser", "attention required",
    "verify you are human", "challenge-platform", "cf-browser-verification",
    "challenges.cloudflare.com", "ddos-guard", "one more step",
    "checking if the site connection is secure"
)

/**
 * Cloudflare bypass for WitAnime using the pattern proven by real extensions
 * (Anidap / AniKage / MkvBase): a VISIBLE dialog WebView driven by
 * CommonActivity.activity — the official way extensions reach the UI.
 *
 * - "Just a moment…" JS challenge  -> solves itself inside the dialog (seconds)
 * - Interactive checkbox (WARP etc) -> the challenge is visible, you tap it once
 * - Cookies + the User-Agent that earned them persist for 45 minutes
 *   (across app restarts) and are attached to every request afterwards.
 */
object WitaCF {

    private const val PREFS = "WitaCFBypass"
    private const val KEY_DATA = "cf_data"
    private const val TTL_MS = 45L * 60 * 1000

    /** true while WitAnime.search() runs — never pop the dialog then */
    @Volatile var inSearch: Boolean = false

    private val bypassMutex = Mutex()

    private class Entry(val cookies: String, val ua: String, val ts: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false
    private var prefs: android.content.SharedPreferences? = null

    private fun context(): Context? {
        CommonActivity.activity?.let { return it.applicationContext }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
            at.getMethod("getApplication").invoke(current) as? Context
        }.getOrNull()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val ctx = context() ?: return
            prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs?.getString(KEY_DATA, null) ?: return
            try {
                val root = JSONObject(raw)
                root.keys().forEach { host ->
                    val o = root.optJSONObject(host) ?: return@forEach
                    cache[host] = Entry(o.optString("c"), o.optString("ua"), o.optLong("ts"))
                }
            } catch (_: Exception) {}
        }
    }

    private fun persist() {
        try {
            val root = JSONObject()
            cache.forEach { (host, e) ->
                root.put(host, JSONObject().put("c", e.cookies).put("ua", e.ua).put("ts", e.ts))
            }
            prefs?.edit()?.putString(KEY_DATA, root.toString())?.apply()
        } catch (_: Exception) {}
    }

    /** cookies+ua for a host URL ("https://witanime.you"), or null */
    fun cookiesFor(hostUrl: String): Pair<String, String>? {
        ensureLoaded()
        val e = cache[hostUrl] ?: return null
        if (System.currentTimeMillis() - e.ts > TTL_MS) {
            cache.remove(hostUrl)
            persist()
            return null
        }
        if (e.cookies.isBlank()) return null
        return e.cookies to e.ua
    }

    fun save(hostUrl: String, cookies: String, ua: String) {
        ensureLoaded()
        cache[hostUrl] = Entry(cookies, ua, System.currentTimeMillis())
        persist()
        println("$WITA_CF_TAG: saved cookies for $hostUrl")
    }

    fun clear(hostUrl: String? = null) {
        ensureLoaded()
        if (hostUrl == null) cache.clear() else cache.remove(hostUrl)
        persist()
    }

    fun isCfBlocked(resp: NiceResponse?): Boolean {
        if (resp == null) return false
        val code = resp.code
        val body = try { resp.text.lowercase() } catch (_: Exception) { "" }
        if (code == 403 || code == 503 || code == 429) {
            return CF_BLOCK_PHRASES.any { body.contains(it) }
        }
        // occasionally the challenge is served with status 200
        return body.contains("challenge-platform") && body.contains("just a moment")
    }

    /** app.get + Cloudflare awareness. Returns the response (blocked or not), or null on network error. */
    suspend fun get(
        url: String,
        referer: String? = null,
        timeout: Long = 30_000L
    ): NiceResponse? {
        val hostUrl = try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) { return null }

        fun buildHeaders(): Map<String, String> {
            val h = mutableMapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8"
            )
            cookiesFor(hostUrl)?.let { (c, ua) ->
                h["Cookie"] = c
                h["User-Agent"] = ua
            }
            return h
        }

        var resp = try {
            app.get(url, headers = buildHeaders(), referer = referer, timeout = timeout)
        } catch (e: Exception) { null }
        if (!isCfBlocked(resp)) return resp

        // stay silent during background searches
        if (inSearch) return resp
        println("$WITA_CF_TAG: blocked (code=${resp?.code}) for $hostUrl -> starting bypass")

        bypassMutex.withLock {
            // another request may have solved it while we waited on the mutex
            val cached = try {
                app.get(url, headers = buildHeaders(), referer = referer, timeout = timeout)
            } catch (e: Exception) { null }
            if (!isCfBlocked(cached)) return cached

            clear(hostUrl)
            val solved = showBypassDialogAndWait(hostUrl)
            if (!solved) return@withLock

            repeat(2) {
                val r = try {
                    app.get(url, headers = buildHeaders(), referer = referer, timeout = timeout)
                } catch (e: Exception) { null }
                if (!isCfBlocked(r)) return r
            }
        }
        return resp
    }
}

private class WitaCFDialog(
    private val activity: Activity,
    private val targetUrl: String,
    private val onFinished: (Boolean) -> Unit
) {
    companion object {
        private const val POLL_MS = 1500L
        private const val TIMEOUT_MS = 120_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val resolved = AtomicBoolean(false)
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var elapsedMs = 0L

    private val targetHost: String = try {
        val uri = Uri.parse(targetUrl)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) { targetUrl }

    private val poll = object : Runnable {
        override fun run() {
            if (resolved.get()) return
            elapsedMs += POLL_MS
            if (tryExtract()) return
            if (elapsedMs >= TIMEOUT_MS) {
                statusText?.text = "Timed out — انتهت المهلة"
                finish(false)
            } else {
                statusText?.text = "Waiting for verification… (${elapsedMs / 1000}s)"
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    private fun tryExtract(): Boolean {
        try {
            CookieManager.getInstance().flush()
            val cookies = CookieManager.getInstance().getCookie(targetHost) ?: return false
            if (cookies.contains("cf_clearance")) {
                statusText?.text = "Success ✓ — تم التحقق"
                finish(true, cookies)
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun finish(ok: Boolean, cookies: String? = null) {
        if (!resolved.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        if (ok && cookies != null) {
            WitaCF.save(targetHost, cookies, webView?.settings?.userAgentString ?: "")
        }
        try { webView?.stopLoading() } catch (_: Exception) {}
        try { webView?.destroy() } catch (_: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
        try { onFinished(ok) } catch (_: Exception) {}
    }

    fun cancel() { finish(false) }

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screenH = activity.resources.displayMetrics.heightPixels
        val screenW = activity.resources.displayMetrics.widthPixels

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
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).also { it.bottomMargin = dp(10) })

        val webHolder = FrameLayout(activity)
        val wv = WebView(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            // strip WebView tells — CF refuses clearance to flagged UAs
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                override fun onPageFinished(v: WebView?, url: String?) { tryExtract() }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView?, newProgress: Int) {
                    if (!resolved.get()) statusText?.text = "Loading… $newProgress%"
                }
            }
        }
        webView = wv
        webHolder.addView(wv, FrameLayout.LayoutParams(-1, -1))
        root.addView(webHolder, LinearLayout.LayoutParams(-1, (screenH * 0.7f).toInt()))

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(Button(activity).apply {
            text = "Done"
            setOnClickListener {
                CookieManager.getInstance().flush()
                if (!tryExtract()) statusText?.text = "Not verified yet — أكمل التحقق أولاً"
            }
        })
        buttons.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { finish(false) }
        })
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.setCancelable(false)
        d.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!resolved.get()) {
                resolved.set(true)
                try { onFinished(false) } catch (_: Exception) {}
            }
        }
        dialog = d

        // clean slate so the challenge actually shows
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
            listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog").forEach { name ->
                setCookie(targetHost, "$name=; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
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

private suspend fun showBypassDialogAndWait(url: String): Boolean = withContext(Dispatchers.Main) {
    val activity = CommonActivity.activity
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        println("$WITA_CF_TAG: no activity — cannot show bypass dialog")
        return@withContext false
    }
    suspendCancellableCoroutine { cont ->
        val dlg = WitaCFDialog(activity, url) { ok -> if (cont.isActive) cont.resume(ok) }
        try {
            dlg.show()
        } catch (e: Exception) {
            println("$WITA_CF_TAG: dialog failed: ${e.message}")
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation { dlg.cancel() }
    }
}
