/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.os.Bundle
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class OnboardingActivity : ExpressiveActivity() {
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_onboarding)
        DataModel.handleWindowInsets(this)
        page = savedInstanceState?.getInt("page") ?: 0
        findViewById<MaterialButton>(R.id.intro_next).setOnClickListener {
            if (page < 2) {
                page++
                render()
            } else complete()
        }
        findViewById<MaterialButton>(R.id.intro_skip).setOnClickListener { complete() }
        findViewById<MaterialButton>(R.id.intro_palette).setOnClickListener {
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val values = resources.getStringArray(R.array.palette_values)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.palette_title)
                .setSingleChoiceItems(
                    R.array.palette_names,
                    values.indexOf(preferences.getString("expressive_palette", "system")),
                ) { dialog, which ->
                    preferences.edit { putString("expressive_palette", values[which]) }
                    dialog.dismiss()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        findViewById<TextView>(R.id.intro_title)
            .setText(
                listOf(R.string.onboard_title, R.string.onboard_patterns, R.string.onboard_colors)[
                    page
                ]
            )
        findViewById<TextView>(R.id.intro_body)
            .setText(
                listOf(
                    R.string.onboard_body,
                    R.string.onboard_patterns_body,
                    R.string.onboard_colors_body,
                )[page]
            )
        findViewById<TextView>(R.id.intro_progress).text = getString(R.string.intro_step, page + 1)
        findViewById<MaterialButton>(R.id.intro_next)
            .setText(if (page == 2) R.string.onboard_done else R.string.onboard_next)
        findViewById<MaterialButton>(R.id.intro_palette).visibility =
            if (page == 2) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun complete() {
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean("onboarding_complete", true)
        }
        finish()
    }
}
