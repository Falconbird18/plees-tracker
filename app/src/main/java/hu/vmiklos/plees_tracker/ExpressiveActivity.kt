/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors

/** Shared color system for every first-party screen, including settings and dialogs. */
open class ExpressiveActivity : AppCompatActivity() {
    private var palette: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        SharedPreferencesChangeListener().applyTheme(preferences)
        palette = preferences.getString("expressive_palette", "system")
        setTheme(if (this is MainActivity) R.style.AppTheme_Main else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        when (palette) {
            "coral" -> theme.applyStyle(R.style.ThemeOverlay_Plees_Coral, true)
            "ocean" -> theme.applyStyle(R.style.ThemeOverlay_Plees_Ocean, true)
            "system" -> DynamicColors.applyToActivityIfAvailable(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (
            palette !=
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString("expressive_palette", "system")
        )
            recreate()
    }
}
