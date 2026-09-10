package com.kawaii

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KawaiiAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KawaiiAnime())
    }
}
