package com.witanime

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Auto-clicks the Cloudflare challenge checkbox inside a WebView.
 * Adapted from the visible-solver approach: JS finds the box, computes
 * two candidate click points, dispatches realistic touch events.
 * Called by the hidden renderer BEFORE falling back to the visible dialog —
 * worst case it does nothing, best case the user never sees anything.
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

    /** One click attempt. Async and failure-proof — safe to call every ~2s
     *  from the hidden renderer's poll loop while a challenge is showing. */
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
