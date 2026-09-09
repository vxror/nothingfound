package com.witanime

import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Self-contained Cloudflare auto-clicker for the hidden renderer.
 * Start it with ONE line and forget it — it manages its own timing,
 * stops itself when the render finishes, and dies automatically when
 * the renderer's handler gets cleared on cleanup.
 */
object CfAutoClick {

    private val jsGetCoords = """
        (function(){
            try{
                var box = document.querySelector("html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div");
                if(!box) return "NO_BOX";
                var r = box.getBoundingClientRect();
                if(r.width === 0 && r.height === 0) return "NO_BOX";
                var size = Math.min(36, Math.max(18, Math.round(r.height * 0.55)));
                var margin = Math.round(Math.max(8, r.width * 0.03));
                var centerY = r.top + (r.height / 2);
                var rightSideX = r.right - (size / 2) - margin;
                var leftSideX = r.left + (size / 2) + margin;
                return rightSideX + "," + centerY + "|" + leftSideX + "," + centerY;
            }catch(e){ return "ERROR"; }
        })();
    """.trimIndent()

    /**
     * ONE call starts everything:
     * - waits 3 seconds first (page-load grace)
     * - then tries to click the CF checkbox every ~2.5 seconds
     * - stops the moment shouldStop() becomes true (pass it your done flag)
     * - also dies when the handler's callbacks get cleared on cleanup
     */
    fun startAutoClickLoop(webView: WebView, handler: Handler, shouldStop: () -> Boolean) {
        val startedAt = SystemClock.uptimeMillis()
        var lastClickAt = 0L

        val runnable = object : Runnable {
            override fun run() {
                if (shouldStop()) return
                val now = SystemClock.uptimeMillis()
                if (now - startedAt > 3_000L && now - lastClickAt > 2_500L) {
                    lastClickAt = now
                    attempt(webView)
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.postDelayed(runnable, 1_000L)
    }

    /** one click attempt — async, failure-proof */
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
                        webView.postDelayed({ simulateTouch(webView, lx, ly) }, 250)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** realistic touch: DOWN, 50ms hold, UP — in real screen pixels */
    private fun simulateTouch(view: WebView, cssX: Float, cssY: Float) {
        try {
            val density = view.resources.displayMetrics.density
            val realX = cssX * density
            val realY = cssY * density
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0)
            view.dispatchTouchEvent(down)
            view.postDelayed({
                val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, realX, realY, 0)
                view.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
            }, 50)
        } catch (_: Exception) {}
    }
}
