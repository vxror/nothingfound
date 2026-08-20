package com.animewitcher

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AnimeWitcherSettingsBottomSheet(private val sharedPref: SharedPreferences) : BottomSheetDialogFragment() {
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentContainerView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, ProxySettingsFragment(sharedPref))
            .commit()
    }

    class ProxySettingsFragment(private val sharedPref: SharedPreferences) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.preferenceDataStore = null
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val proxyPref = EditTextPreference(ctx).apply {
                key = "animewitcher_proxy_host"
                title = "Proxy Worker Host"
                setDefaultValue("issa-proxy.yazankal.workers.dev")
                
                // Load and display current value
                val saved = sharedPref.getString("animewitcher_proxy_host", "issa-proxy.yazankal.workers.dev")
                summary = saved
                text = saved

                setOnPreferenceChangeListener { _, newValue ->
                    val newVal = newValue as String
                    sharedPref.edit().putString("animewitcher_proxy_host", newVal).apply()
                    summary = newVal
                    true
                }
            }
            preferenceScreen.addPreference(proxyPref)
        }
    }
}
