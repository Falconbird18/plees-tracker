/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class StageSummaryView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        inflate(context, R.layout.stage_summary, this)
    }

    fun showSleep(sleep: Sleep?) {
        val night = sleep?.sensorData?.takeIf { it.isNotBlank() }?.let(SensorNight::decode)
        val timeline = findViewById<StageTimelineView>(R.id.stage_timeline)
        val source = findViewById<TextView>(R.id.stage_source)
        val breakdown = findViewById<ChipGroup>(R.id.stage_breakdown)
        val explanation = findViewById<TextView>(R.id.stage_explanation)
        breakdown.removeAllViews()
        if (night == null) {
            timeline.visibility = View.GONE
            source.setText(R.string.stage_empty)
            explanation.visibility = View.GONE
            return
        }
        val intervals = SleepStageEstimator.estimate(night, sleep.start, sleep.stop)
        val total = (sleep.stop - sleep.start).coerceAtLeast(1)
        val covered =
            night.epochs
                .filter { it.samples >= 30 && it.end - it.start >= 20000 }
                .sumOf {
                    (minOf(it.end, sleep.stop) - maxOf(it.start, sleep.start)).coerceAtLeast(0)
                }
        val mode =
            resources.getStringArray(R.array.sensor_modes)[if (night.mode == "sound") 1 else 0]
        val coverage = (covered.toDouble() / total * 100).toInt().coerceIn(0, 100)
        source.text =
            context.getString(R.string.stage_source, mode, coverage) +
            (if (night.issue.isNotEmpty()) "\n" + night.issue else "")
        explanation.visibility = View.VISIBLE
        explanation.setText(
            if (intervals.all { it.stage == EstimatedStage.UNKNOWN }) R.string.stage_short
            else R.string.stage_explanation
        )
        timeline.visibility = View.VISIBLE
        timeline.intervals = intervals
        val summaries = mutableListOf<String>()
        for (
            stage in
            listOf(
                EstimatedStage.AWAKE,
                EstimatedStage.LIGHT,
                EstimatedStage.DEEP,
                EstimatedStage.REM,
                EstimatedStage.UNKNOWN,
            )
        ) {
            val minutes = intervals.filter { it.stage == stage }.sumOf { it.end - it.start } / 60000
            val label =
                context.getString(R.string.stage_minutes, context.getString(stage.label()), minutes)
            summaries.add(label)
            val chip =
                Chip(context).apply {
                    text = label
                    isClickable = false
                    isCheckable = false
                }
            breakdown.addView(chip)
        }
        timeline.contentDescription =
            context.getString(R.string.stage_timeline_description) +
            ". " +
            summaries.joinToString(". ")
    }
}
