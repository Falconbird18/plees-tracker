/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.room.Room
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render real Android views for visual QA; output is kept under build/, never in source control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveScreenshotsTest {
    private fun capture(view: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1233, 2673)
        val bitmap = Bitmap.createBitmap(1233, 2673, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/screenshots/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 10000)
    }

    @Test
    fun renderPrimaryScreens() {
        val preferences =
            PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        preferences
            .edit()
            .putString("expressive_palette", "lilac")
            .putBoolean("follow_system_theme", false)
            .putBoolean("dark_mode", false)
            .putBoolean("onboarding_complete", true)
            .commit()
        val intro = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        capture(intro.get().window.decorView, "onboarding")
        intro.pause().stop().destroy()
        val context = RuntimeEnvironment.getApplication()
        DataModel.init(context, preferences)
        DataModel.database.close()
        val direct = Executor { it.run() }
        DataModel.database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(direct)
                .setTransactionExecutor(direct)
                .build()
        runBlocking {
            for (day in 0..6) {
                val record = Sleep()
                record.stop = System.currentTimeMillis() - day * 86400000L
                record.start = record.stop - (7 * 3600000L + day * 300000L)
                record.rating = 4
                record.comment = "A quiet evening and a good book."
                if (day == 0)
                    record.sensorData =
                        SensorNight(
                            "motion",
                            (0 until 840).map { epoch ->
                                val activity =
                                    if (epoch % 23 == 0) .08 else .002 + (epoch % 7) * .0001
                                SensorEpoch(
                                    record.start + epoch * 30000L,
                                    record.start + (epoch + 1) * 30000L,
                                    activity,
                                    activity * activity,
                                    300,
                                    epoch < 5,
                                )
                            },
                        )
                            .encode()
                DataModel.database.sleepDao().insert(record)
            }
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        capture(main.get().window.decorView, "sleep-light")
        main
            .get()
            .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.navigation
            )
            .selectedItemId = R.id.nav_statistics
        capture(main.get().window.decorView, "statistics-light")
        main.pause().stop().destroy()
        val settings = Robolectric.buildActivity(PreferencesActivity::class.java).setup()
        capture(settings.get().window.decorView, "settings-light")
        settings.pause().stop().destroy()
        val sid = runBlocking { DataModel.database.sleepDao().getAll().first().sid }
        val editor =
            Robolectric.buildActivity(
                SleepActivity::class.java,
                Intent(context, SleepActivity::class.java).putExtra("sid", sid),
            )
                .setup()
        capture(editor.get().window.decorView, "sleep-editor")
        editor.pause().stop().destroy()
        val graphs = Robolectric.buildActivity(GraphsActivity::class.java).setup()
        graphs
            .get()
            .findViewById<com.google.android.material.chip.ChipGroup>(R.id.graph_choices)
            .check(R.id.graph_length)
        capture(graphs.get().window.decorView, "trends-light")
        graphs.pause().stop().destroy()
        preferences
            .edit()
            .putBoolean("dark_mode", true)
            .putString("expressive_palette", "coral")
            .commit()
        val dark = Robolectric.buildActivity(MainActivity::class.java).setup()
        capture(dark.get().window.decorView, "sleep-dark-coral")
        dark.pause().stop().destroy()
    }
}
