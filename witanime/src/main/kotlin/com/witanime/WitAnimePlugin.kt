package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WitAnime())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())
        
        // Start MegaProxy safely with error handling
        try {
            MegaProxy.start()
        } catch (e: Exception) {
            android.util.Log.e("WitAnime", "MegaProxy failed to start", e)
        }
    }
}
