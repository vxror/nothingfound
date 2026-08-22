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

        // StreamWish family
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(EmbedWish())

        // Dood (known domains registered for loadExtractor; unknown dood hosts
        // are routed via isDoodLink() in routeLink and never miss)
        registerExtractorAPI(DoodExtractor())
        registerExtractorAPI(DoodToExtractor())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(DoodYtExtractor())
        registerExtractorAPI(DoodWsExtractor())
        registerExtractorAPI(Ds2PlayExtractor())
        registerExtractorAPI(D000dExtractor())

        registerExtractorAPI(FileMoonExtractor())
        registerExtractorAPI(FourSharedExtractor())
        registerExtractorAPI(MediaFireExtractor())
        registerExtractorAPI(MegaExtractor())

        try { MegaProxy.start() } catch (e: Exception) {
            println("WitAnimeDebug: MegaProxy start failed: ${e.message}")
        }
        // NOTE: UniversalExtractor is called directly from routeLink — NOT registered,
        // so it never shadows built-in extractors (mp4upload etc.)
    }
}
