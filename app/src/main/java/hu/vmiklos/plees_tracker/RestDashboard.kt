/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.widget.TextViewCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Coordinates a picked night, editable goals, and calendar-day history without altering records.
 */
class RestDashboard(private val activity: MainActivity, restoredSelection: Int = 0) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private var all: List<Sleep> = emptyList()
    private var history: List<Sleep> = emptyList()
    var selectedId = restoredSelection
        private set

    var onOpenNight: (() -> Unit)? = null
    val journal =
        RecyclerView(activity).apply {
            id = R.id.sleeps
            isNestedScrollingEnabled = true
        }
    val journalEmpty =
        TextView(activity).apply {
            id = R.id.no_sleeps
            setText(R.string.journal_empty)
            textSize = 16f
        }
    val exploreButton = button(R.string.explore_graphs).apply { id = R.id.explore_graphs }
    val comparisonButton =
        button(R.string.all_statistics, tonal = true).apply { id = R.id.compare_statistics }
    private val night = activity.findViewById<LinearLayout>(R.id.statistics_panel)
    private val past = activity.findViewById<LinearLayout>(R.id.history_panel)
    private val dateButton =
        button(R.string.choose_night, tonal = true).apply { id = R.id.night_date_selector }
    private val previous = buttonText("‹")
    private val next = buttonText("›")
    private val heroDuration = text("—", 80f, true).apply { id = R.id.night_duration }
    private val heroGoal = text("", 15f).apply { id = R.id.night_goal_summary }
    private val bloom = GoalBloomView(activity)
    private val progress =
        LinearProgressIndicator(activity, null, 0).apply {
            max = 100
            trackThickness = dp(7)
            trackCornerRadius = dp(4)
            waveAmplitude = dp(3)
            setWavelength(dp(24))
            waveSpeed = 0
            setWaveAmplitudeRampProgressMax(1f)
        }
    private val ring = StageRingView(activity).apply { id = R.id.night_stage_ring }
    private val stageLegend = ChipGroup(activity).apply { isSingleSelection = true }
    private val stageNote = text(R.string.stage_explanation, 13f)
    private val timeline =
        StageTimelineView(activity).apply {
            contentDescription = activity.getString(R.string.stage_timeline_description)
        }
    private val bedtimeActual = text("", 26f, true)
    private val wakeActual = text("", 26f, true)
    private val bedtimeTarget = text("", 14f)
    private val wakeTarget = text("", 14f)
    private val bedtimeOffset = text("", 13f)
    private val wakeOffset = text("", 13f)
    private val weeklyValue = text("", 35f, true)
    private val weekCaption = text("", 13f)
    private val garden = RhythmGardenView(activity)
    private val recent =
        ChipGroup(activity).apply {
            isSingleLine = true
            isSingleSelection = true
            isSelectionRequired = true
        }
    private val empty = text(R.string.no_night_body, 18f)
    private val emptyGoals =
        button(R.string.tune_goals, tonal = true).apply {
            setOnClickListener { RestGoalSheet.show(activity) }
        }
    private val edit = button(R.string.notes_and_edit, tonal = true)
    private val count = text("0", 48f, true).apply { id = R.id.fragment_stats_sleeps }
    private val average = text("—", 32f, true)
    private val periodCaption = text("", 13f)
    private val bars = RestHistoryChart(activity, true).apply { id = R.id.history_stack_chart }
    private val trend = RestHistoryChart(activity, false).apply { id = R.id.history_goal_chart }
    private val historyGarden = RhythmGardenView(activity)
    private val historyWeekly = text("", 28f, true)
    private var nightDetail: LinearLayout? = null
    private val goalListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (
            key in
            setOf(
                "ideal_sleep_length",
                "goal_bedtime_minutes",
                "goal_wakeup_minutes",
                "goal_consistent_nights",
            )
        )
            render()
    }

    init {
        buildNight()
        buildHistory()
        previous.contentDescription = activity.getString(R.string.previous_night)
        next.contentDescription = activity.getString(R.string.next_night)
        previous.setOnClickListener { move(1) }
        next.setOnClickListener { move(-1) }
        dateButton.setOnClickListener { showNightPicker() }
        edit.setOnClickListener {
            all.firstOrNull { it.sid == selectedId }
                ?.let { sleep ->
                    activity.startActivity(
                        Intent(activity, SleepActivity::class.java).putExtra("sid", sleep.sid)
                    )
                }
        }
        bars.onSelect = ::openFromChart
        trend.onSelect = ::openFromChart
        preferences.registerOnSharedPreferenceChangeListener(goalListener)
        render()
    }

    fun dispose() {
        preferences.unregisterOnSharedPreferenceChangeListener(goalListener)
    }

    fun submitAll(sleeps: List<Sleep>) {
        val followingLatest = selectedId == 0 || selectedId == all.firstOrNull()?.sid
        val sorted = sleeps.sortedByDescending { it.stop }
        val newLatestWasInserted =
            sorted.firstOrNull()?.let { newest -> all.none { it.sid == newest.sid } } ?: false
        all = sorted
        if ((followingLatest && newLatestWasInserted) || all.none { it.sid == selectedId })
            selectedId = all.firstOrNull()?.sid ?: 0
        render()
    }

    fun submitHistory(sleeps: List<Sleep>) {
        history = sleeps
        renderHistory()
    }

    fun selectNight(sleep: Sleep) {
        selectedId = sleep.sid
        renderNight()
        onOpenNight?.invoke()
    }

    private fun move(amount: Int) {
        val index = all.indexOfFirst { it.sid == selectedId }
        all.getOrNull(index + amount)?.let { selectNight(it) }
    }

    private fun openFromChart(sleep: Sleep?) {
        if (sleep == null)
            Toast.makeText(activity, R.string.no_record_day, Toast.LENGTH_SHORT).show()
        else selectNight(sleep)
    }

    private fun showNightPicker() {
        if (all.isEmpty()) return
        val labels =
            all.map {
                RestInsights.date(it.stop, "EEE, MMM d") +
                    " · " +
                    RestInsights.duration(it.stop - it.start)
            }
                .toTypedArray()
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.choose_night)
            .setSingleChoiceItems(labels, all.indexOfFirst { it.sid == selectedId }) { dialog, index
                ->
                selectNight(all[index])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildNight() {
        night.removeAllViews()
        add(night, text(R.string.night_eyebrow, 11f, true), bottom = 10)
        add(night, text(R.string.night_headline, 42f, true), bottom = 18)
        val chooser = row()
        chooser.addView(previous, LinearLayout.LayoutParams(dp(48), dp(56)))
        chooser.addView(
            dateButton,
            LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        chooser.addView(next, LinearLayout.LayoutParams(dp(48), dp(56)))
        add(night, chooser, bottom = 18)
        add(night, empty, bottom = 12)
        add(night, emptyGoals, height = 60, bottom = 20)
        val details = column()
        nightDetail = details
        add(night, details)
        val hero = card(com.google.android.material.R.attr.colorPrimaryContainer, 56f, 24f)
        val content = column(24)
        val primaryText = com.google.android.material.R.attr.colorOnPrimaryContainer
        val label =
            text(R.string.logged_rest, 11f, true).apply { setTextColor(restColor(primaryText)) }
        add(content, label)
        val heroRow = row()
        heroDuration.setTextColor(hero.restColor(primaryText))
        heroDuration.maxLines = 2
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            heroDuration,
            30,
            80,
            2,
            android.util.TypedValue.COMPLEX_UNIT_SP,
        )
        heroRow.addView(heroDuration, LinearLayout.LayoutParams(0, dp(112), 1f))
        heroRow.addView(
            bloom,
            LinearLayout.LayoutParams(dp(108), dp(108)).apply { marginStart = dp(10) },
        )
        add(content, heroRow, bottom = 4)
        heroGoal.setTextColor(hero.restColor(primaryText))
        add(content, heroGoal, bottom = 12)
        add(content, progress, height = 20)
        val tune =
            button(R.string.tune_goals, tonal = true).apply {
                setOnClickListener { RestGoalSheet.show(activity) }
            }
        add(content, tune, height = 48, top = 12)
        hero.addView(content)
        add(details, hero, bottom = 24)
        add(details, text(R.string.stages_headline, 35f, true), bottom = 4)
        add(details, text(R.string.stages_ring_caption, 13f), bottom = 8)
        add(details, ring, height = 218)
        add(details, stageLegend, bottom = 12)
        add(details, stageNote, bottom = 24)
        val story = card(com.google.android.material.R.attr.colorSurfaceContainer, 32f, 32f)
        val storyBody = column(20)
        add(storyBody, text(R.string.overnight_story, 24f, true), bottom = 6)
        add(storyBody, text(R.string.timeline_subtitle, 13f), bottom = 8)
        add(storyBody, timeline, height = 208)
        story.addView(storyBody)
        add(details, story, bottom = 28)
        add(details, text(R.string.goals_headline, 34f, true), bottom = 16)
        val schedule = row()
        fun timing(
            title: Int,
            value: TextView,
            target: TextView,
            offset: TextView,
            color: Int,
            weight: Float,
        ): MaterialCardView {
            val panel = card(color, 32f, 16f)
            val body = column(18)
            add(body, text(title, 10f, true), bottom = 12)
            add(body, value, bottom = 8)
            add(body, target, bottom = 8)
            add(body, offset)
            panel.addView(body)
            schedule.addView(
                panel,
                LinearLayout.LayoutParams(0, -2, weight).apply { marginEnd = dp(8) },
            )
            return panel
        }
        timing(
            R.string.bedtime_label,
            bedtimeActual,
            bedtimeTarget,
            bedtimeOffset,
            com.google.android.material.R.attr.colorSecondaryContainer,
            1.1f,
        )
        timing(
            R.string.wakeup_label,
            wakeActual,
            wakeTarget,
            wakeOffset,
            com.google.android.material.R.attr.colorTertiaryContainer,
            .9f,
        )
        add(details, schedule, bottom = 16)
        val rhythm = card(com.google.android.material.R.attr.colorSurfaceContainer, 24f, 48f)
        val rhythmBody = column(22)
        add(rhythmBody, text(R.string.rhythm_garden, 23f, true), bottom = 10)
        add(rhythmBody, weeklyValue)
        add(rhythmBody, weekCaption, bottom = 12)
        add(rhythmBody, garden, height = 72)
        add(rhythmBody, text(R.string.garden_key, 11f), bottom = 12)
        add(rhythmBody, text(R.string.schedule_rule, 13f))
        val tuneAgain =
            button(R.string.tune_goals, tonal = true).apply {
                setOnClickListener { RestGoalSheet.show(activity) }
            }
        add(rhythmBody, tuneAgain, height = 56, top = 12)
        rhythm.addView(rhythmBody)
        add(details, rhythm, bottom = 20)
        add(details, edit, height = 60, bottom = 24)
        add(details, text(R.string.recent_nights, 20f, true), bottom = 8)
        val recentScroll =
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(recent)
            }
        add(details, recentScroll)
    }

    private fun buildHistory() {
        past.removeAllViews()
        add(past, text(R.string.history_eyebrow, 11f, true), bottom = 10)
        add(past, text(R.string.history_headline, 44f, true), bottom = 20)
        val chips =
            ChipGroup(activity).apply {
                isSingleLine = true
                isSingleSelection = true
                isSelectionRequired = true
            }
        for (
            (value, title) in
            listOf(
                "-7" to R.string.history_period_week,
                "-14" to R.string.history_period_fortnight,
                "-30" to R.string.history_period_month,
                "-365" to R.string.history_period_year,
                "0" to R.string.history_period_all,
            )
        ) {
            val chip =
                Chip(activity).apply {
                    id = View.generateViewId()
                    setText(title)
                    tag = value
                    isCheckable = true
                    isChecked = value == preferences.getString("dashboard_duration", "-7")
                }
            chips.addView(chip)
        }
        chips.setOnCheckedStateChangeListener { group, ids ->
            ids.firstOrNull()?.let { id ->
                preferences.edit {
                    putString("dashboard_duration", group.findViewById<View>(id).tag as String)
                }
            }
        }
        add(
            past,
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            },
            bottom = 16,
        )
        val metrics = row()
        fun metric(value: TextView, title: Int, color: Int, weight: Float) {
            val panel = card(color, 40f, 18f)
            val body = column(20)
            add(body, value, bottom = 6)
            add(body, text(title, 10f, true))
            panel.addView(body)
            metrics.addView(
                panel,
                LinearLayout.LayoutParams(0, -2, weight).apply { marginEnd = dp(8) },
            )
        }
        metric(
            count,
            R.string.tracked_rests,
            com.google.android.material.R.attr.colorPrimaryContainer,
            .85f,
        )
        metric(
            average,
            R.string.per_recorded_day,
            com.google.android.material.R.attr.colorSecondaryContainer,
            1.15f,
        )
        add(past, metrics, bottom = 24)
        add(past, text(R.string.history_stacked, 34f, true), bottom = 6)
        add(past, text(R.string.history_stacked_hint, 14f), bottom = 6)
        add(past, periodCaption, bottom = 12)
        val barCard = card(com.google.android.material.R.attr.colorSurfaceContainer, 32f, 32f)
        barCard.addView(bars, LinearLayout.LayoutParams(-1, dp(274)))
        add(past, barCard, bottom = 12)
        val key = ChipGroup(activity)
        restStageOrder.forEach { stage ->
            key.addView(
                Chip(activity).apply {
                    text =
                        android.text
                            .SpannableString("● " + activity.getString(stage.label()))
                            .apply {
                                setSpan(
                                    android.text.style.ForegroundColorSpan(
                                        restColor(stage.colorAttribute())
                                    ),
                                    0,
                                    1,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            }
                    isClickable = false
                    isCheckable = false
                }
            )
        }
        add(past, key, bottom = 24)
        add(past, text(R.string.history_goal_chart, 34f, true), bottom = 6)
        add(past, text(R.string.history_goal_hint, 14f), bottom = 8)
        val trendCard = card(com.google.android.material.R.attr.colorTertiaryContainer, 48f, 24f)
        trendCard.addView(trend, LinearLayout.LayoutParams(-1, dp(250)))
        add(past, trendCard, bottom = 8)
        add(past, text(R.string.chart_legend, 12f), bottom = 24)
        val rhythm = card(com.google.android.material.R.attr.colorSurfaceContainer, 28f, 44f)
        val body = column(22)
        add(body, text(R.string.rhythm_garden, 23f, true), bottom = 10)
        add(body, historyWeekly, bottom = 8)
        add(body, historyGarden, height = 72)
        add(body, text(R.string.goal_week_caption, 13f), bottom = 12)
        add(
            body,
            button(R.string.tune_goals, tonal = true).apply {
                setOnClickListener { RestGoalSheet.show(activity) }
            },
            height = 56,
        )
        rhythm.addView(body)
        add(past, rhythm, bottom = 28)
        add(past, text(R.string.history_journal, 34f, true), bottom = 12)
        add(past, journalEmpty, bottom = 12)
        add(past, journal, height = 480, bottom = 16)
        add(past, exploreButton, height = 60)
        add(past, comparisonButton, height = 60)
    }

    private fun render() {
        renderNight()
        renderHistory()
    }

    private fun renderNight() {
        val goals = RestGoals.read(preferences)
        val sleep = all.firstOrNull { it.sid == selectedId }
        empty.visibility = if (sleep == null) View.VISIBLE else View.GONE
        emptyGoals.visibility = empty.visibility
        nightDetail?.visibility = if (sleep == null) View.GONE else View.VISIBLE
        previous.isEnabled = all.indexOf(sleep) < all.lastIndex && sleep != null
        next.isEnabled = all.indexOf(sleep) > 0
        dateButton.text =
            sleep?.let { RestInsights.date(it.stop, "EEE, MMM d") }
            ?: activity.getString(R.string.choose_night)
        if (sleep == null) return
        val duration = (sleep.stop - sleep.start).coerceAtLeast(0)
        val value = RestInsights.duration(duration)
        heroDuration.text =
            SpannableString(value).apply {
                if (value.indexOf('h') >= 0)
                    setSpan(
                        RelativeSizeSpan(.48f),
                        value.indexOf('h'),
                        value.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
            }
        heroGoal.text =
            activity.getString(
                R.string.of_duration_goal,
                RestInsights.duration((goals.hours * 3600000).toLong()),
            )
        bloom.percentage = (duration / (goals.hours * 36000)).toInt().coerceIn(0, 999)
        progress.setProgressCompat(bloom.percentage.coerceAtMost(100), true)
        val intervals = RestInsights.intervals(sleep)
        val totals =
            intervals
                .groupBy { it.stage }
                .mapValues { (_, rows) -> rows.sumOf { it.end - it.start } }
        ring.show(totals)
        timeline.intervals = intervals
        stageLegend.removeAllViews()
        for (stage in restStageOrder) {
            val label =
                activity.getString(
                    R.string.selected_stage_value,
                    activity.getString(stage.label()),
                    RestInsights.duration(totals[stage] ?: 0),
                )
            val chip =
                Chip(activity).apply {
                    text =
                        SpannableString("●  " + label).apply {
                            setSpan(
                                android.text.style.ForegroundColorSpan(
                                    restColor(stage.colorAttribute())
                                ),
                                0,
                                1,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    isCheckable = true
                    setOnClickListener { ring.highlight(stage) }
                }
            stageLegend.addView(chip)
        }
        val source = SensorNight.decode(sleep.sensorData)
        stageNote.text =
            activity.getString(
            if (totals.keys.all { it == EstimatedStage.UNKNOWN })
                R.string.stage_unavailable_body
            else R.string.stage_explanation
        ) +
            (
                source?.let {
                    "\n" +
                        activity.getString(
                            R.string.rest_sensor_source,
                            activity.resources
                                .getStringArray(R.array.sensor_modes)[
                                if (it.mode == "sound") 1 else 0
                            ],
                        ) +
                        if (it.issue.isNotEmpty()) "\n" + it.issue else ""
                } ?: ""
                )
        fun timing(
            time: Long,
            target: Int,
            value: TextView,
            targetView: TextView,
            offset: TextView,
        ) {
            value.text = RestGoalSheet.clock(activity, RestInsights.minute(time))
            targetView.text =
                activity.getString(R.string.target_time, RestGoalSheet.clock(activity, target))
            val delta = RestInsights.clockDifference(RestInsights.minute(time), target)
            offset.text =
                if (kotlin.math.abs(delta) <= 30) activity.getString(R.string.on_target)
                else
                    activity.getString(
                        if (delta < 0) R.string.minutes_early else R.string.minutes_late,
                        kotlin.math.abs(delta),
                    )
        }
        timing(sleep.start, goals.bedtime, bedtimeActual, bedtimeTarget, bedtimeOffset)
        timing(sleep.stop, goals.wakeup, wakeActual, wakeTarget, wakeOffset)
        val states = RestInsights.weeklySchedule(all, sleep.stop, goals)
        weeklyValue.text =
            activity.getString(
                R.string.weekly_progress,
                states.count { it == true },
                goals.consistentNights,
            )
        weekCaption.text =
            activity.getString(R.string.week_ending, RestInsights.date(sleep.stop, "MMM d"))
        garden.show(states, sleep.stop)
        recent.setOnCheckedStateChangeListener(null)
        recent.removeAllViews()
        all.take(10).forEach { record ->
            recent.addView(
                Chip(activity).apply {
                    id = View.generateViewId()
                    tag = record.sid
                    text = RestInsights.date(record.stop, "MMM d")
                    isCheckable = true
                    isChecked = record.sid == selectedId
                }
            )
        }
        recent.setOnCheckedStateChangeListener { group, ids ->
            ids.firstOrNull()?.let { id ->
                all.firstOrNull { it.sid == group.findViewById<View>(id).tag }
                    ?.let { selectNight(it) }
            }
        }
    }

    private fun renderHistory() {
        val goals = RestGoals.read(preferences)
        count.text = history.size.toString()
        val groups = history.groupBy { RestInsights.midnight(it.stop) }
        average.text =
            if (groups.isEmpty()) "—"
            else
                RestInsights.duration(
                    history.sumOf { (it.stop - it.start).coerceAtLeast(0) } / groups.size
                )
        val period = preferences.getString("dashboard_duration", "-7")?.toIntOrNull() ?: -7
        val ending = System.currentTimeMillis()
        val monthly = period == -365 || period == 0
        val span = RestInsights.monthSpan(history.minOfOrNull { it.stop } ?: ending, ending)
        val yearly = period == 0 && span > 24
        val days =
            if (monthly)
                RestInsights.aggregate(
                    history,
                    ending,
                    yearly,
                    if (period == -365) 12
                    else if (yearly)
                        RestInsights.yearSpan(history.minOfOrNull { it.stop } ?: ending, ending)
                    else span,
                )
            else
                RestInsights.days(
                    history,
                    ending,
                    if (period == -14) 14 else if (period == -7) 7 else 30,
                )
        periodCaption.text =
            activity.getString(
            if (yearly) R.string.history_yearly_average
            else if (monthly) R.string.history_monthly_average
            else R.string.history_latest_days,
            days.size,
        ) +
            "\n" +
            activity.getString(
                R.string.chart_target,
                RestInsights.duration((goals.hours * 3600000).toLong()),
            )
        bars.show(days, goals.hours)
        trend.show(days, goals.hours)
        val states = RestInsights.weeklySchedule(all, ending, goals)
        historyWeekly.text =
            activity.getString(
                R.string.weekly_progress,
                states.count { it == true },
                goals.consistentNights,
            )
        historyGarden.show(states, ending)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun column(padding: Int = 0) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (padding > 0) setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        }

    private fun row() =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun text(res: Int, size: Float, bold: Boolean = false) =
        text(activity.getString(res), size, bold)

    private fun text(value: String, size: Float, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(restColor(com.google.android.material.R.attr.colorOnSurface))
            if (bold) typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        }

    private fun button(res: Int, tonal: Boolean = false) =
        buttonText(activity.getString(res), tonal)

    private fun buttonText(value: String, tonal: Boolean = false) =
        MaterialButton(
            activity,
            null,
            if (tonal) com.google.android.material.R.attr.materialButtonTonalStyle
            else com.google.android.material.R.attr.materialButtonStyle,
        )
            .apply {
                text = value
                isAllCaps = false
            }

    private fun card(color: Int, large: Float, small: Float) =
        MaterialCardView(activity).apply {
            radius = dp(small.toInt()).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(restColor(color))
            shapeAppearanceModel =
                ShapeAppearanceModel.builder()
                    .setTopLeftCornerSize(dp(large.toInt()).toFloat())
                    .setTopRightCornerSize(dp(small.toInt()).toFloat())
                    .setBottomRightCornerSize(dp(large.toInt()).toFloat())
                    .setBottomLeftCornerSize(dp(small.toInt()).toFloat())
                    .build()
        }

    private fun add(
        parent: LinearLayout,
        child: View,
        height: Int = -2,
        top: Int = 0,
        bottom: Int = 0,
    ) {
        parent.addView(
            child,
            LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply {
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            },
        )
    }
}
