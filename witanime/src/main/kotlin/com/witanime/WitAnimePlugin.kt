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
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(MediaFireExtractor())
        registerExtractorAPI(DoodStreamExtractor())
        registerExtractorAPI(FourSharedExtractor())
        registerExtractorAPI(MegaProxyExtractor())
        
        // Start MegaProxy server
        try {
            MegaProxy.start()
        } catch (e: Exception) {
            println("WitAnimeDebug: MegaProxy start failed: ${e.message}")
        }
    }
}
