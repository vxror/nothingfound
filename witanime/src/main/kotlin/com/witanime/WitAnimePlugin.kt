package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WitAnime())

        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(VideasFrExtractor())
        registerExtractorAPI(MailruExtractor())

        // StreamWish family
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(EmbedWish())

        // Dood — known domains registered; unknown dood hosts caught by isDoodLink() in routeLink
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
        // UniversalExtractor is invoked directly from routeLink — intentionally NOT registered,
        // so it never shadows built-in extractors (mp4upload, uqload, streamtape, ...)
    }
}
