/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.view.View
import androidx.preference.PreferenceManager
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestGoalsTest {
    @Test
    fun goalsPersistWithoutChangingReminders() {
        val prefs =
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        prefs
            .edit()
            .clear()
            .putString("bedtime", "22:00")
            .putBoolean("daily_reminder", false)
            .commit()
        val goals = RestGoals(9.5f, 23 * 60 + 30, 8 * 60, 6)
        goals.save(prefs)
        assertEquals(goals, RestGoals.read(prefs))
        assertFalse(prefs.getBoolean("daily_reminder", true))
        assertEquals("22:00", prefs.getString("bedtime", null))
    }

    @Test
    fun goalSheetSavesOnlyWhenSaveIsPressed() {
        val prefs =
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        RestGoalSheet.show(controller.get())
        var dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<Slider>(R.id.goal_duration_slider).value = 9f
        dialog.dismiss()
        assertEquals(8f, RestGoals.read(prefs).hours)
        RestGoalSheet.show(controller.get())
        dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<Slider>(R.id.goal_duration_slider).value = 9f
        dialog.findViewById<Slider>(R.id.goal_weekly_slider).value = 6f
        dialog.findViewById<View>(R.id.save_rest_goals).performClick()
        assertEquals(9f, RestGoals.read(prefs).hours)
        assertEquals(6, RestGoals.read(prefs).consistentNights)
        controller.pause().stop().destroy()
    }
}
