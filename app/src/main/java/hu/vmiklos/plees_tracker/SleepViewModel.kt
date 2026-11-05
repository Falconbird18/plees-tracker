/*
 * Copyright 2023 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.content.ContentResolver
import android.content.Context
import android.text.format.DateFormat
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.launch

/** This is the view model of SleepActivity, providing coroutine scopes. */
class SleepViewModel : ViewModel() {

    private var sleepCommentCallback: SleepCommentCallback? = null
    private var sleepWakesCallback: SleepWakesCallback? = null

    fun showSleep(activity: SleepActivity, sid: Int) {
        val viewModel = this
        viewModelScope.launch {
            val sleep = DataModel.getSleepById(sid)
            activity.findViewById<StageSummaryView>(R.id.sleep_stages).showSleep(sleep)

            val startDate = activity.findViewById<TextView>(R.id.sleep_start_date)
            val compactView = DataModel.getCompactView()
            startDate.text = DataModel.formatDateTime(Date(sleep.start), false, compactView)
            val startTime = activity.findViewById<TextView>(R.id.sleep_start_time)
            startTime.text = DataModel.formatDateTime(Date(sleep.start), true, compactView)
            val stopDate = activity.findViewById<TextView>(R.id.sleep_stop_date)
            stopDate.text = DataModel.formatDateTime(Date(sleep.stop), false, compactView)
            val stopTime = activity.findViewById<TextView>(R.id.sleep_stop_time)
            stopTime.text = DataModel.formatDateTime(Date(sleep.stop), true, compactView)
            val rating = activity.findViewById<RatingBar>(R.id.sleep_item_rating)
            rating.rating = sleep.rating.toFloat()
            rating.onRatingBarChangeListener =
                SleepRateCallback(
                    viewModel,
                    sleep,
                    activity.applicationContext,
                    activity.contentResolver,
                )
            val comment = activity.findViewById<AppCompatEditText>(R.id.sleep_item_comment)
            viewModel.sleepCommentCallback?.let {
                comment.removeTextChangedListener(it)
            }
            comment.setText(sleep.comment)
            viewModel.sleepCommentCallback = SleepCommentCallback(viewModel, sleep)
            comment.addTextChangedListener(viewModel.sleepCommentCallback)

            val wakes = activity.findViewById<AppCompatEditText>(R.id.sleep_item_wakes)
            viewModel.sleepWakesCallback?.let {
                wakes.removeTextChangedListener(it)
            }
            wakes.setText(sleep.wakes.toString())
            viewModel.sleepWakesCallback = SleepWakesCallback(viewModel, sleep)
            wakes.addTextChangedListener(viewModel.sleepWakesCallback)
        }
    }

    fun editSleepDate(
        activity: SleepActivity,
        sid: Int,
        isStart: Boolean,
        context: Context,
        cr: ContentResolver,
    ) {
        viewModelScope.launch {
            val sleep = DataModel.getSleepById(sid)
            val dateTime = Calendar.getInstance()
            dateTime.time = if (isStart) Date(sleep.start) else Date(sleep.stop)

            // MaterialDatePicker represents dates at UTC midnight; retain the local clock time.
            val selection =
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear()
                    set(dateTime[Calendar.YEAR], dateTime[Calendar.MONTH], dateTime[Calendar.DATE])
                }
            val picker =
                MaterialDatePicker.Builder.datePicker()
                    .setTitleText(if (isStart) R.string.started_on else R.string.stopped_on)
                    .setSelection(selection.timeInMillis)
                    .build()
            picker.addOnPositiveButtonClickListener { value ->
                val chosen =
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = value }
                dateTime.set(chosen[Calendar.YEAR], chosen[Calendar.MONTH], chosen[Calendar.DATE])
                if (isStart) sleep.start = dateTime.timeInMillis
                else sleep.stop = dateTime.timeInMillis
                if (sleep.start < sleep.stop) updateSleep(activity, sleep, context, cr)
                else Toast.makeText(context, R.string.negative_duration, Toast.LENGTH_SHORT).show()
            }
            picker.show(activity.supportFragmentManager, "sleep_date")
        }
    }

    fun editSleepTime(
        activity: SleepActivity,
        sid: Int,
        isStart: Boolean,
        context: Context,
        cr: ContentResolver,
    ) {
        viewModelScope.launch {
            val sleep = DataModel.getSleepById(sid)
            val dateTime = Calendar.getInstance()
            dateTime.time = if (isStart) Date(sleep.start) else Date(sleep.stop)

            val picker =
                MaterialTimePicker.Builder()
                    .setTitleText(if (isStart) R.string.started_on else R.string.stopped_on)
                    .setHour(dateTime[Calendar.HOUR_OF_DAY])
                    .setMinute(dateTime[Calendar.MINUTE])
                    .setTimeFormat(
                        if (DateFormat.is24HourFormat(activity)) TimeFormat.CLOCK_24H
                        else TimeFormat.CLOCK_12H
                    )
                    .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
                    .build()
            picker.addOnPositiveButtonClickListener {
                dateTime.set(Calendar.HOUR_OF_DAY, picker.hour)
                dateTime.set(Calendar.MINUTE, picker.minute)
                if (isStart) sleep.start = dateTime.timeInMillis
                else sleep.stop = dateTime.timeInMillis
                if (sleep.start < sleep.stop) updateSleep(activity, sleep, context, cr)
                else Toast.makeText(context, R.string.negative_duration, Toast.LENGTH_SHORT).show()
            }
            picker.show(activity.supportFragmentManager, "sleep_time")
        }
    }

    private fun updateSleep(
        activity: SleepActivity,
        sleep: Sleep,
        context: Context,
        cr: ContentResolver,
    ) {
        viewModelScope.launch {
            DataModel.updateSleep(sleep)
            DataModel.backupSleeps(context, cr)
            showSleep(activity, sleep.sid)
        }
    }

    fun updateSleep(sleep: Sleep) {
        viewModelScope.launch {
            DataModel.updateSleep(sleep)
        }
    }

    fun updateSleep(sleep: Sleep, context: Context, cr: ContentResolver) {
        viewModelScope.launch {
            DataModel.updateSleep(sleep)
            DataModel.backupSleeps(context, cr)
        }
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
