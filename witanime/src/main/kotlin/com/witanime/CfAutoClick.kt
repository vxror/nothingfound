package com.witanime

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import java.util.concurrent.ThreadLocalRandom

/**
 * [v2] Cloudflare auto-clicker:
 *  - box finder falls back to scanning challenge iframes when the classic
 *    CSS path fails (CF changes layout regularly)
 *  - touch simulation humanized: pressure, finger jitter, a settling drag,
 *    randomized hold time — turnstile rejects perfectly clean synthetic taps
 */
object CfAutoClick {

    private val jsGetCoords = """
        (function(){
            try{
                var box = document.querySelector("html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div");
                if(box){
                    var r = box.getBoundingClientRect();
                    if(r.width !== 0 && r.height !== 0){
                        var size = Math.min(36, Math.max(18, Math.round(r.height * 0.55)));
                        var margin = Math.round(Math.max(8, r.width * 0.03));
                        var centerY = r.top + (r.height / 2);
                        var rightSideX = r.right - (size / 2) - margin;
                        var leftSideX = r.left + (size / 2) + margin;
                        return rightSideX + "," + centerY + "|" + leftSideX + "," + centerY;
                    }
                }
                var frames = document.querySelectorAll("iframe");
                for (var i = 0; i < frames.length; i++) {
                    var r2 = frames[i].getBoundingClientRect();
                    if (r2.width > 100 && r2.height > 40 && r2.width < 600 && r2.height < 200) {
                        var nearLeft = Math.round(r2.left + Math.min(45, r2.width * 0.18));
                        var center = Math.round(r2.left + (r2.width / 2));
                        var cy = Math.round(r2.top + (r2.height / 2));
                        return nearLeft + "," + cy + "|" + center + "," + cy;
                    }
                }
                return "NO_BOX";
            }catch(e){ return "ERROR"; }
        })();
    """.trimIndent()

    fun startAutoClickLoop(webView: WebView, handler: Handler, shouldStop: () -> Boolean) {
        val startedAt = SystemClock.uptimeMillis()
        var lastClickAt = 0L

        val runnable = object : Runnable {
            override fun run() {
                if (shouldStop()) return
                val now = SystemClock.uptimeMillis()
                if (now - startedAt > 1_200L && now - lastClickAt > 1_800L) {
                    lastClickAt = now
                    attempt(webView)
                }
                handler.postDelayed(this, 900L)
            }
        }
        handler.postDelayed(runnable, 900L)
    }

    fun attempt(webView: WebView) {
        try {
            webView.evaluateJavascript(jsGetCoords) { res ->
                try {
                    val clean = res?.removeSurrounding("\"") ?: return@evaluateJavascript
                    if (!clean.contains("|")) return@evaluateJavascript
                    val sides = clean.split("|")
                    val (rx, ry) = sides[0].split(",").map { it.toFloatOrNull() }
                    val (lx, ly) = sides[1].split(",").map { it.toFloatOrNull() }
                    if (rx != null && ry != null) simulateTouch(webView, rx, ry)
                    if (lx != null && ly != null) {
                        webView.postDelayed({ simulateTouch(webView, lx, ly) }, 400)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** humanized tap: jitter, pressure, settling drag, randomized hold */
    private fun simulateTouch(view: WebView, cssX: Float, cssY: Float) {
        try {
            val rnd = ThreadLocalRandom.current()
            val density = view.resources.displayMetrics.density
            val jx = (rnd.nextFloat() - 0.5f) * 4f
            val jy = (rnd.nextFloat() - 0.5f) * 4f
            val x = (cssX + jx) * density
            val y = (cssY + jy) * density
            val pressure = 0.85f + rnd.nextFloat() * 0.3f
            val size = 0.9f + rnd.nextFloat() * 0.2f
            val downTime = SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, pressure, size, 0, 1f, 1f, 0, 0)
            view.dispatchTouchEvent(down)

            // finger settling: tiny drag before release
            val move = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_MOVE, x + 1.5f, y + 1f, pressure, size, 0, 1f, 1f, 0, 0)
            view.dispatchTouchEvent(move)

            val hold = 80L + (Math.random() * 110).toLong()
            view.postDelayed({
                val up = MotionEvent.obtain(downTime, downTime + 40 + hold, MotionEvent.ACTION_UP, x + 1.5f, y + 1f, pressure, size, 0, 1f, 1f, 0, 0)
                view.dispatchTouchEvent(up)
                down.recycle(); move.recycle(); up.recycle()
            }, hold)
        } catch (_: Exception) {}
    }
}
