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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object WitaWeb {

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "checking your browser", "attention required",
        "verify you are human", "one more step", "ddos-guard"
    )

    private val renderMutex = Mutex()

    fun looksChallenge(html: String?): Boolean {
        if (html.isNullOrBlank()) return true
        val l = html.lowercase()
        if (CHALLENGE_TITLES.any { l.contains(it) }) return true
        return l.contains("challenge-platform") && l.contains("cloudflare")
    }

    fun isChallengeTitle(title: String): Boolean {
        val l = title.lowercase()
        return CHALLENGE_TITLES.any { l.contains(it) }
    }

    /**
     * Hidden-first strategy:
     * 1) If the browser already holds cf_clearance for this host, render in an
     *    INVISIBLE 1x1 WebView — page loads with the browser's own fingerprint,
     *    no UI shown at all.
     * 2) Only when no clearance exists (or it expired and the hidden render got
     *    re-challenged) fall back to the visible dialog so a human can solve.
     */
    suspend fun fetchHtml(url: String, timeoutMs: Long = 120_000L): String? =
        renderMutex.withLock {
            val host = try { Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
            val hasClearance = withContext(Dispatchers.Main) {
                runCatching {
                    CookieManager.getInstance().getCookie("https://$host")
                }.getOrNull()?.contains("cf_clearance") == true
            }

            if (hasClearance) {
                val hidden = HiddenRender.fetch(url)
                if (hidden != null) {
                    println("WitaCF: hidden render OK — no UI shown")
                    return@withLock hidden
                }
                println("WitaCF: clearance stale (re-challenged) → visible dialog")
            } else {
                println("WitaCF: no clearance yet → visible dialog (solve once)")
            }
            VisibleRender.fetch(url, timeoutMs)
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
    // strip WebView tells — CF refuses clearance to flagged UAs
    settings.userAgentString = settings.userAgentString
        .replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+ "), "")
}

/**
 * Invisible renderer: 1x1 WebView attached to the window (attached = it loads
 * and executes JS; 1x1 = nobody sees it). Works whenever the clearance cookie
 * is still valid — the server returns the page directly, no challenge runs.
 */
private object HiddenRender {
    private const val POLL_MS = 400L
    private const val CHALLENGE_GRACE_MS = 6_000L  // auto-solve grace if challenged anyway
    private const val HARD_TIMEOUT_MS = 15_000L

    suspend fun fetch(url: String): String? = withContext(Dispatchers.Main) {
        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val done = AtomicBoolean(false)
            var webView: WebView? = null
            val startedAt = SystemClock.uptimeMillis()

            fun cleanup() {
                handler.removeCallbacksAndMessages(null)
                val wv = webView ?: return
                try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                try { wv.stopLoading() } catch (_: Exception) {}
                try { wv.destroy() } catch (_: Exception) {}
                webView = null
            }

            fun finish(html: String?) {
                if (!done.compareAndSet(false, true)) return
                cleanup()
                if (cont.isActive) cont.resume(html)
            }

            try {
                val wv = WebView(activity)
                webView = wv
                wv.configForCf()
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(wv, true)
                }
                // attached (so it renders & runs JS) but 1x1 pixel = invisible
                activity.addContentView(wv, ViewGroup.LayoutParams(1, 1))
                wv.loadUrl(url)

                val poll = object : Runnable {
                    override fun run() {
                        if (done.get()) return
                        val elapsed = SystemClock.uptimeMillis() - startedAt
                        val wv2 = webView ?: return
                        if (elapsed > HARD_TIMEOUT_MS) { finish(null); return }
                        wv2.evaluateJavascript("document.title") { jsTitle ->
                            if (done.get()) return@evaluateJavascript
                            val title = unwrapJs(jsTitle).orEmpty()
                            if (WitaWeb.isChallengeTitle(title)) {
                                // challenge active — an invisible WebView cannot complete
                                // interactive ones; after a short grace, give up quietly
                                if (elapsed > CHALLENGE_GRACE_MS) finish(null)
                                else handler.postDelayed(this, POLL_MS)
                                return@evaluateJavascript
                            }
                            wv2.evaluateJavascript("document.documentElement.outerHTML") { jsHtml ->
                                if (done.get()) return@evaluateJavascript
                                val html = unwrapJs(jsHtml)
                                if (html != null && html.length > 300 && !WitaWeb.looksChallenge(html)) {
                                    finish(html)
                                } else {
                                    handler.postDelayed(this, POLL_MS)
                                }
                            }
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

/** Visible dialog renderer — only shown when a challenge needs a human. */
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

private class WitaRenderDialog(
    private val activity: Activity,
    private val targetUrl: String,
    private val timeoutMs: Long,
    private val onResult: (String?) -> Unit
) {
    companion object {
        private const val POLL_MS = 600L
        private const val REVEAL_AFTER_MS = 4_000L
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
        try { webView?.stopLoading() } catch (_: Exception) {}
        try { webView?.destroy() } catch (_: Exception) {}
        webView = null
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
        try { onResult(html) } catch (_: Exception) {}
    }

    fun cancel() = finish(null)

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
                wv.evaluateJavascript("document.documentElement.outerHTML") { jsHtml ->
                    if (done.get()) return@evaluateJavascript
                    val html = unwrapJs(jsHtml)
                    if (html != null && html.length > 300 && !WitaWeb.looksChallenge(html)) {
                        status("Loaded ✓")
                        finish(html)
                    } else {
                        status("Loading… (${elapsed / 1000}s)")
                        handler.postDelayed(poll, POLL_MS)
                    }
                }
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
