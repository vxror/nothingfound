package com.cimanow

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.witanime.WitAnime
import com.witanime.VideaExtractor
import com.witanime.MailruExtractor

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WitAnime())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())
    }
}
