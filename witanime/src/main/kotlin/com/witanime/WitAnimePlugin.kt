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
        registerExtractorAPI(OkRuExtractor())
        registerExtractorAPI(MegaExtractor())
        registerExtractorAPI(FourSharedExtractor())
        registerExtractorAPI(FileMoonExtractor())

        // Dood family
        registerExtractorAPI(DoodExtractor())
        registerExtractorAPI(DoodToExtractor())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(DoodYtExtractor())
        registerExtractorAPI(DoodWsExtractor())
        registerExtractorAPI(Ds2PlayExtractor())
        registerExtractorAPI(D000dExtractor())

        // StreamWish family — extends the app's BUILT-IN proven extractor
        registerExtractorAPI(Mwish())
        registerExtractorAPI(Dwish())
        registerExtractorAPI(Ewish())
        registerExtractorAPI(WishembedPro())
        registerExtractorAPI(Kswplayer())
        registerExtractorAPI(Wishfast())
        registerExtractorAPI(Streamwish2())
        registerExtractorAPI(SfastwishCom())
        registerExtractorAPI(StrwishXyz())
        registerExtractorAPI(StrwishCom())
        registerExtractorAPI(FlaswishCom())
        registerExtractorAPI(Awish())
        registerExtractorAPI(Obeywish())
        registerExtractorAPI(Jodwish())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(UqloadsXyz())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(Nekowish())
        registerExtractorAPI(Nekostream())
        registerExtractorAPI(Swdyu())
        registerExtractorAPI(Wishonly())
        registerExtractorAPI(Playerwish())
        registerExtractorAPI(StreamHLSTo())
        registerExtractorAPI(HlsWish())
        registerExtractorAPI(StreamWishCom())
        registerExtractorAPI(StreamWishTop())

        try { MegaProxy.start() } catch (e: Exception) {
            println("WitAnimeDebug: MegaProxy start failed: ${e.message}")
        }
    }
}
