package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // 1. Register the Main Provider
        registerMainAPI(WitAnime())
        
        // 2. Register Video Host Extractors
        registerExtractorAPI(VideaExtractor())       // Hungarian XML/RC4
        registerExtractorAPI(VideasFrExtractor())    // French CDN m3u8
        registerExtractorAPI(MailruExtractor())      // Mail.ru
        
        // 3. Register StreamWish Variants
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(CdnwishCom())
        
        // 4. Register File Hosts
        registerExtractorAPI(MediaFireExtractor())
        registerExtractorAPI(FourSharedExtractor())
        
        // 5. Register DoodStream Variants
        registerExtractorAPI(DoodStreamExtractor())
        registerExtractorAPI(DoodStreamComExtractor())
        registerExtractorAPI(DoodStreamToExtractor())
        registerExtractorAPI(DoodStreamWatchExtractor())
        
        // 6. Register Mega & Universal Fallback
        registerExtractorAPI(MegaProxyExtractor())
        registerExtractorAPI(UniversalExtractor())
        
        // 7. Boot the local MegaProxy streaming server
        try { 
            MegaProxy.start() 
        } catch (e: Exception) { 
            println("WitAnimeDebug: MegaProxy start failed: ${e.message}") 
        }
    }
}
