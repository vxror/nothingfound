package com.witanime

/**
 * Tagged logging routed through Cloudstream's extension logger
 * (com.lagradost.api.Log) — visible in the app's debug view AND logcat,
 * greppable by one tag, and can never crash the plugin.
 */
object WitaLog {
    private const val TAG = "WitAnime"

    fun d(msg: String) { runCatching { com.lagradost.api.Log.d(TAG, msg) } }
    fun w(msg: String) { runCatching { com.lagradost.api.Log.w(TAG, msg) } }
    fun e(msg: String, tr: Throwable? = null) {
        val full = if (tr != null) "$msg — ${tr.message}" else msg
        runCatching { com.lagradost.api.Log.e(TAG, full) }
    }
}
