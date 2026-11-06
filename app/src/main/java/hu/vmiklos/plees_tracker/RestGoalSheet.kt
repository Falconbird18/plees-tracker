/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

object RestGoalSheet {
    fun clock(context: android.content.Context, minute: Int): String {
        val time =
            Calendar.getInstance()
                .apply {
                    set(Calendar.HOUR_OF_DAY, minute / 60)
                    set(Calendar.MINUTE, minute % 60)
                }
                .time
        return android.text.format.DateFormat.getTimeFormat(context).format(time)
    }

    fun show(activity: AppCompatActivity) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val goals = RestGoals.read(preferences)
        var bedtime = goals.bedtime
        var wakeup = goals.wakeup
        val sheet = BottomSheetDialog(activity)
        val scroll = androidx.core.widget.NestedScrollView(activity)
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 32))
            }
        scroll.addView(body)
        fun label(res: Int, size: Float = 18f): TextView =
            TextView(activity).apply {
                setText(res)
                textSize = size
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                    )
                )
                body.addView(
                    this,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12) },
                )
            }
        label(R.string.tune_goals, 30f)
        val durationLabel = label(R.string.duration_goal)
        val duration =
            Slider(activity).apply {
                id = R.id.goal_duration_slider
                valueFrom = 2f
                valueTo = 14f
                stepSize = .5f
                value = goals.hours
                setLabelFormatter { RestInsights.duration((it * 3600000).toLong()) }
            }
        body.addView(duration)
        fun durationText(value: Float) {
            durationLabel.text =
                activity.getString(R.string.duration_goal) +
                " · " +
                RestInsights.duration((value * 3600000).toLong())
        }
        durationText(duration.value)
        duration.addOnChangeListener { _, value, _ -> durationText(value) }
        fun timeButton(title: Int, initial: Int, onSet: (Int) -> Unit): MaterialButton {
            val button =
                MaterialButton(
                    activity,
                    null,
                    com.google.android.material.R.attr.materialButtonTonalStyle,
                )
            fun showValue(value: Int) {
                button.text = activity.getString(title) + " · " + clock(activity, value)
            }
            showValue(initial)
            button.setOnClickListener {
                val current = if (title == R.string.bedtime_goal) bedtime else wakeup
                val picker =
                    MaterialTimePicker.Builder()
                        .setTitleText(title)
                        .setHour(current / 60)
                        .setMinute(current % 60)
                        .setTimeFormat(
                            if (android.text.format.DateFormat.is24HourFormat(activity))
                                TimeFormat.CLOCK_24H
                            else TimeFormat.CLOCK_12H
                        )
                        .build()
                picker.addOnPositiveButtonClickListener {
                    val value = picker.hour * 60 + picker.minute
                    onSet(value)
                    showValue(value)
                }
                picker.show(activity.supportFragmentManager, "goal_time")
            }
            body.addView(button, LinearLayout.LayoutParams(-1, dp(activity, 64)))
            return button
        }
        timeButton(R.string.bedtime_goal, bedtime) { bedtime = it }
        timeButton(R.string.wakeup_goal, wakeup) { wakeup = it }
        val weeklyLabel = label(R.string.weekly_goal)
        val weekly =
            Slider(activity).apply {
                id = R.id.goal_weekly_slider
                valueFrom = 1f
                valueTo = 7f
                stepSize = 1f
                value = goals.consistentNights.toFloat()
            }
        body.addView(weekly)
        fun weeklyText(value: Float) {
            weeklyLabel.text = activity.getString(R.string.weekly_goal_value, value.toInt())
        }
        weeklyText(weekly.value)
        weekly.addOnChangeListener { _, value, _ -> weeklyText(value) }
        label(R.string.schedule_rule, 14f)
        label(R.string.goal_schedule_note, 14f)
        val save =
            MaterialButton(activity).apply {
                id = R.id.save_rest_goals
                setText(R.string.save_goals)
                setOnClickListener {
                    RestGoals(duration.value, bedtime, wakeup, weekly.value.toInt())
                        .save(preferences)
                    sheet.dismiss()
                }
            }
        body.addView(
            save,
            LinearLayout.LayoutParams(-1, dp(activity, 64)).apply { topMargin = dp(activity, 16) },
        )
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun dp(context: android.content.Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
