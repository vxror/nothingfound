package com.animewitcher

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeWitcherPlugin : Plugin() {
    override fun load(context: Context) {
        // 1. Register the provider
        registerMainAPI(AnimeWitcherProvider())

        // 2. Get SharedPreferences for our settings
        val sharedPref = context.getSharedPreferences("AnimeWitcherPrefs", Context.MODE_PRIVATE)

        // 3. Hook up the settings UI
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                AnimeWitcherSettingsBottomSheet(sharedPref).show(activity.supportFragmentManager, "aw_settings")
            }
        }
    }
}
