package com.animewitcher

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeWitcherPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeWitcherProvider())

        val sharedPref = context.getSharedPreferences("AnimeWitcherPrefs", Context.MODE_PRIVATE)

        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                AnimeWitcherSettingsBottomSheet(sharedPref).show(activity.supportFragmentManager, "aw_settings")
            }
        }
    }
}
