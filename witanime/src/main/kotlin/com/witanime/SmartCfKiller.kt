package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-contained Cloudflare solver for the WitAnime extension.
 * - "Just a moment..." JS challenge -> solved automatically in a hidden WebView.
 * - Interactive captcha -> after ~4s the cover lifts so the USER can tap it.
 * No app-internal references: tracks the current Activity by itself, so it
 * works from inside an extension. Failure at any step = original response
 * returned untouched (behaves exactly like before, never worse).
 */
class SmartCfKiller : Interceptor {

    companion object {
        // shared across instances: hosts we recently failed to solve (30 min backoff)
        private val failedUntil = ConcurrentHashMap<String, Long>()
        private val locks = ConcurrentHashMap<String, Any>()

        /** WitAnime.search() sets this so the solver never pops during background searches */
        @Volatile var inSearch: Boolean = false

        /** UA the last challenge was solved with — cf_clearance is bound to it */
        @Volatile var solvedUa: String? = null

        // ---- current-activity tracking (no app internals needed) ----
        private val resumed = AtomicReference<WeakReference<Activity>?>(null)
        private val tracked = AtomicBoolean(false)

        internal fun appContext(): Context? {
            return runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val current = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
                at.getMethod("getApplication").invoke(current) as? Application
            }.getOrNull()
        }

        private fun ensureTracking() {
            if (tracked.get()) return
            val app = appContext() ?: return
            if (!tracked.compareAndSet(false, true)) return
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

        internal fun currentActivity(): Activity? {
            ensureTracking()
            return resumed.get()?.get()
        }
    }

    // per-instance on purpose: a rotated/expired cf_clearance must not be wedged
    // in a static map until app restart.
    private val cookieByHost = ConcurrentHashMap<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        val response = chain.proceed(applySaved(request, host))
        if (!isCloudflareChallenge(response)) return response

        // never pop a solver during search — this source just yields no hits
        if (inSearch) return response

        val finalUrl = response.request.url   // challenge host AFTER redirects
        val finalHost = finalUrl.host
        synchronized(locks.getOrPut(finalHost) { Any() }) {
            if (!cookieByHost.containsKey(finalHost)) {
                // negative cache: don't retry a failing host for 30 min
                val until = failedUntil[finalHost]
                if (until != null && System.currentTimeMillis() < until) return response
                val solved = CfWebViewSolver.solve(finalUrl.toString())
                if (solved == null) {
                    failedUntil[finalHost] = System.currentTimeMillis() + 30 * 60 * 1000L
                    return response
                }
                failedUntil.remove(finalHost)
                cookieByHost[finalHost] = solved.cookie
                solvedUa = solved.userAgent
            }
        }
        response.close()
        val cookie = cookieByHost[finalHost]
        val retried = request.newBuilder()
            .url(finalUrl)
            .apply {
                if (cookie != null) {
                    val existing = request.header("Cookie")
                    header("Cookie", if (existing.isNullOrBlank()) cookie else "$existing; $cookie")
                }
                solvedUa?.let { header("User-Agent", it) }
            }
            .build()
        return chain.proceed(retried)
    }

    fun getCookieHeaders(url: String): Headers {
        val builder = Headers.Builder()
        solvedUa?.let { builder.add("User-Agent", it) }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { builder.add("Cookie", it) }
        return builder.build()
    }

    private fun applySaved(request: Request, host: String): Request {
        val cookie = cookieByHost[host] ?: return request
        val b = request.newBuilder()
        val existing = request.header("Cookie")
        b.header("Cookie", if (existing.isNullOrBlank()) cookie else "$existing; $cookie")
        solvedUa?.let { b.header("User-Agent", it) }
        return b.build()
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val server = response.header("Server").orEmpty().lowercase()
        val cfMitigated = response.header("cf-mitigated") != null
        val statusFlag = response.code in intArrayOf(403, 503, 429) && server.contains("cloudflare")
        val ct = response.header("Content-Type").orEmpty().lowercase()
        val htmlish = ct.contains("text/html") || ct.isEmpty()
        if (!htmlish && !cfMitigated && !statusFlag) return false
        val body = try {
            response.peekBody(20_000L).string() // peek doesn't consume the body
        } catch (_: Exception) {
            return cfMitigated || statusFlag
        }
        return cfMitigated || statusFlag ||
            body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenge-platform") ||
            body.contains("challenges.cloudflare.com") ||
            body.contains("_cf_chl_opt") ||
            body.contains("cf-browser-verification")
    }
}

internal object CfWebViewSolver {
    data class Result(val cookie: String, val userAgent: String)

    /** cover stays up this long; if still unsolved the challenge is interactive
     *  and a human must see it — lift the cover and let them tap. */
    private const val REVEAL_AFTER_MS = 4_000L
    private const val SOLVE_TIMEOUT_SECONDS = 30L
    private const val INTERACTED_TIMEOUT_SECONDS = 90L

    fun solve(url: String): Result? {
        val activity = SmartCfKiller.currentActivity()
        val context: Context = activity ?: SmartCfKiller.appContext() ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Result?>()
        val main = Handler(Looper.getMainLooper())
        val webViewRef = AtomicReference<WebView?>()
        val containerRef = AtomicReference<android.view.View?>()
        val overlayRef = AtomicReference<android.view.View?>()
        val touched = AtomicBoolean(false)

        fun captureIfReady() {
            runCatching { CookieManager.getInstance().flush() }
            val wv = webViewRef.get()
            val curUrl = wv?.url ?: url
            val cookieCur = runCatching { CookieManager.getInstance().getCookie(curUrl) }.getOrNull()
            val cookieOrig = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val cookie = listOfNotNull(cookieCur, cookieOrig).firstOrNull { it.contains("cf_clearance") }
            if (cookie != null) {
                val ua = wv?.settings?.userAgentString ?: ""
                if (ua.isNotEmpty()) SmartCfKiller.solvedUa = ua
                if (ref.compareAndSet(null, Result(cookie, ua))) latch.countDown()
            }
        }

        val startedAt = SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                captureIfReady()
                if (ref.get() != null) return
                if (SystemClock.uptimeMillis() - startedAt > REVEAL_AFTER_MS) {
                    overlayRef.getAndSet(null)?.let { ov ->
                        (ov.parent as? ViewGroup)?.removeView(ov)
                    }
                }
                main.postDelayed(this, 300)
            }
        }

        main.post {
            try {
                @SuppressLint("SetJavaScriptEnabled")
                val wv = WebView(context)
                webViewRef.set(wv)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.databaseEnabled = true
                // strip WebView tells from the UA so CF will issue clearance
                wv.settings.userAgentString = wv.settings.userAgentString
                    .replace("; wv", "")
                    .replace(Regex("Version/\\d+\\.\\d+ "), "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.setOnTouchListener { v, _ ->
                    touched.set(true)
                    v.performClick()
                    false
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        captureIfReady()
                    }
                }
                // attach full-size WebView to the window (required for the
                // challenge JS to render), with a branded cover on top
                if (activity != null) {
                    try {
                        val mp = ViewGroup.LayoutParams.MATCH_PARENT
                        val container = FrameLayout(context)
                        container.addView(wv, FrameLayout.LayoutParams(mp, mp))
                        val overlay = buildVerifyingOverlay(context)
                        overlayRef.set(overlay)
                        container.addView(overlay, FrameLayout.LayoutParams(mp, mp))
                        containerRef.set(container)
                        activity.addContentView(container, ViewGroup.LayoutParams(mp, mp))
                    } catch (_: Throwable) {}
                }
                wv.loadUrl(url)
                main.postDelayed(poll, 400)
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        val solved = try {
            latch.await(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ||
                (touched.get() && latch.await(
                    INTERACTED_TIMEOUT_SECONDS - SOLVE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                ))
        } catch (_: InterruptedException) {
            false
        }

        main.post {
            main.removeCallbacks(poll)
            try {
                containerRef.get()?.let { c -> (c.parent as? ViewGroup)?.removeView(c) }
                webViewRef.get()?.let { w ->
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.stopLoading()
                    w.destroy()
                }
            } catch (_: Throwable) {}
        }
        return if (solved) ref.get() else null
    }

    private fun buildVerifyingOverlay(context: Context): android.view.View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = FrameLayout(context)
        root.setBackgroundColor(0xFF0B0B0F.toInt())
        root.isClickable = true // swallow taps so they don't reach the WebView early

        val chip = LinearLayout(context)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setPadding(dp(16), dp(12), dp(20), dp(12))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(26).toFloat()
        bg.setColor(0xFF17171C.toInt())
        bg.setStroke(dp(1), 0x22FFFFFF)
        chip.background = bg

        val spinner = ProgressBar(context)
        spinner.indeterminateTintList = ColorStateList.valueOf(0xFFFF4D57.toInt())
        val slp = LinearLayout.LayoutParams(dp(18), dp(18))
        slp.marginEnd = dp(12)
        chip.addView(spinner, slp)

        val label = TextView(context)
        label.text = "Verifying protected source…"
        label.setTextColor(0xFFFFFFFF.toInt())
        label.textSize = 14f
        chip.addView(label)

        // don't flash the chip for a fast solve
        chip.visibility = android.view.View.INVISIBLE
        chip.postDelayed({ chip.visibility = android.view.View.VISIBLE }, 700)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        root.addView(chip, lp)
        return root
    }
}
