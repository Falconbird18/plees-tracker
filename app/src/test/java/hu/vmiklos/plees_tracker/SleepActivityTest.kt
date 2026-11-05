/*
 * Copyright 2026 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RatingBar
import android.widget.TextView
import androidx.core.view.descendants
import androidx.preference.PreferenceManager
import androidx.room.Room
import com.google.android.material.timepicker.MaterialTimePicker
import java.util.Calendar
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Tests for SleepActivity, run on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SleepActivityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        android.provider.Settings.System.putString(
            context.contentResolver,
            android.provider.Settings.System.TIME_12_24,
            "24",
        )
        DataModel.init(context, PreferenceManager.getDefaultSharedPreferences(context))
        DataModel.database.close()
        // Run Room queries on the calling thread, so the activity's viewModelScope coroutines
        // can be driven deterministically by idling the main looper.
        val direct = Executor { it.run() }
        DataModel.database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(direct)
                .setTransactionExecutor(direct)
                .build()
        ShadowDialog.reset()
        DataModel.registerOnBackupSleeps(null)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createSleep(): Int {
        val sleep = Sleep()
        val start = Calendar.getInstance()
        start.set(Calendar.HOUR_OF_DAY, 9)
        sleep.start = start.timeInMillis
        val stop = Calendar.getInstance()
        stop.set(Calendar.HOUR_OF_DAY, 23)
        stop.set(Calendar.MINUTE, 59)
        sleep.stop = stop.timeInMillis
        return runBlocking {
            DataModel.database.sleepDao().insert(sleep)
            DataModel.database.sleepDao().getAll().first().sid
        }
    }

    private fun setTime(activity: SleepActivity, viewId: Int, hour: Int, minute: Int) {
        activity.findViewById<TextView>(viewId).performClick()
        idle()
        activity.supportFragmentManager.executePendingTransactions()
        val picker =
            activity.supportFragmentManager.findFragmentByTag("sleep_time") as MaterialTimePicker
        val dialog = picker.requireDialog()
        dialog
            .findViewById<ViewGroup>(com.google.android.material.R.id.material_hour_text_input)
            .descendants
            .filterIsInstance<EditText>()
            .first()
            .setText(hour.toString())
        dialog
            .findViewById<ViewGroup>(com.google.android.material.R.id.material_minute_text_input)
            .descendants
            .filterIsInstance<EditText>()
            .first()
            .setText(minute.toString())
        dialog
            .findViewById<android.view.View>(
                com.google.android.material.R.id.material_timepicker_ok_button
            )
            .performClick()
        idle()
    }

    @Test
    fun testUpdate() {
        val sid = createSleep()
        val intent = Intent(context, SleepActivity::class.java).putExtra("sid", sid)
        val activity = Robolectric.buildActivity(SleepActivity::class.java, intent).setup().get()
        idle()

        setTime(activity, R.id.sleep_start_time, 10, 0)
        setTime(activity, R.id.sleep_stop_time, 22, 0)

        assertEquals(
            "10:00",
            activity.findViewById<TextView>(R.id.sleep_start_time).text.toString(),
        )
        assertEquals(
            "22:00",
            activity.findViewById<TextView>(R.id.sleep_stop_time).text.toString(),
        )
    }

    @Test
    fun testRatingTriggersAutomaticBackup() {
        val sid = createSleep()
        val intent = Intent(context, SleepActivity::class.java).putExtra("sid", sid)
        val activity = Robolectric.buildActivity(SleepActivity::class.java, intent).setup().get()
        idle()

        var called = 0
        DataModel.registerOnBackupSleeps { called++ }
        val ratingBar = activity.findViewById<RatingBar>(R.id.sleep_item_rating)
        ratingBar.rating = 3.0f
        idle()

        assertTrue(called > 0)
        DataModel.registerOnBackupSleeps(null)
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
