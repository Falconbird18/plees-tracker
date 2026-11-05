/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ExpressiveNavigationTest {
    @Test
    fun statisticsSelectionSurvivesRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId =
            R.id.nav_statistics
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tracking_panel).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.statistics_panel).visibility)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val restored =
            Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().get()
        assertEquals(
            R.id.nav_statistics,
            restored.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId,
        )
    }

    @Test
    fun onboardingCompletesOnlyAfterFinalPage() {
        val preferences =
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        preferences.edit().clear().commit()
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        val activity = controller.get()
        val next = activity.findViewById<MaterialButton>(R.id.intro_next)
        next.performClick()
        assertFalse(preferences.getBoolean("onboarding_complete", false))
        next.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.intro_palette).visibility)
        next.performClick()
        assertTrue(preferences.getBoolean("onboarding_complete", false))
        assertTrue(activity.isFinishing)
    }

    @Test
    @Config(sdk = [24])
    fun everyPaletteWorksBeforeDynamicColorSupport() {
        val preferences =
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        val colors = mutableListOf<Int>()
        for (palette in listOf("system", "lilac", "coral", "ocean")) {
            preferences.edit().putString("expressive_palette", palette).commit()
            val controller = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
            val button = controller.get().findViewById<View>(R.id.intro_next)
            assertTrue(button.isEnabled)
            colors.add(
                com.google.android.material.color.MaterialColors.getColor(
                    button,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            )
            controller.pause().stop().destroy()
        }
        assertEquals(colors[0], colors[1]) // Material You falls back to lilac before Android 12.
        assertEquals(3, colors.toSet().size)
    }
}
