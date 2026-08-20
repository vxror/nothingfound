package com.animewitcher

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.TextSetting

@CloudstreamPlugin
class AnimeWitcherPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimeWitcherProvider())
    }

    // 🛠️ UI SETTINGS: Adds a text box in the extension settings to change the proxy host
    override val settings = listOf(
        TextSetting("proxy_host", "Proxy Worker Host", "issa-proxy.yazankal.workers.dev")
    )
}
