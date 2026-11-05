/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.util.Date

class StageTimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    var intervals: List<StageInterval> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intervals.isEmpty()) return
        val density = resources.displayMetrics.density
        val text = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val start = intervals.first().start
        val stop = intervals.last().end
        val left = 76 * density
        val right = width.toFloat() - 6 * density
        val row = (height - 40 * density) / 5f
        val stages =
            listOf(
                EstimatedStage.AWAKE,
                EstimatedStage.REM,
                EstimatedStage.LIGHT,
                EstimatedStage.DEEP,
                EstimatedStage.UNKNOWN,
            )
        paint.textSize =
            android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                11f,
                resources.displayMetrics,
            )
        for ((index, stage) in stages.withIndex()) {
            paint.color = text
            canvas.drawText(context.getString(stage.label()), 0f, row * index + row * .65f, paint)
        }
        for (interval in intervals) {
            val x =
                left +
                    (right - left) *
                    ((interval.start - start).toDouble() / (stop - start)).toFloat()
            val end =
                left +
                    (right - left) * ((interval.end - start).toDouble() / (stop - start)).toFloat()
            val y = row * stages.indexOf(interval.stage) + row * .5f
            paint.color = MaterialColors.getColor(this, interval.stage.colorAttribute())
            // Keep short events inside their actual time interval; round line caps would
            // visually join sparse awakenings into a misleading continuous awake period.
            val corner = minOf(4 * density, (end - x) / 2).coerceAtLeast(0f)
            canvas.drawRoundRect(
                x,
                y - 6 * density,
                maxOf(x + 1, end),
                y + 6 * density,
                corner,
                corner,
                paint,
            )
        }
        paint.color = text
        paint.textSize =
            android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                11f,
                resources.displayMetrics,
            )
        val first = android.text.format.DateFormat.getTimeFormat(context).format(Date(start))
        val last = android.text.format.DateFormat.getTimeFormat(context).format(Date(stop))
        canvas.drawText(first, left, height - 4 * density, paint)
        canvas.drawText(last, right - paint.measureText(last), height - 4 * density, paint)
    }
}

fun EstimatedStage.label(): Int =
    when (this) {
        EstimatedStage.AWAKE -> R.string.stage_awake
        EstimatedStage.LIGHT -> R.string.stage_light
        EstimatedStage.DEEP -> R.string.stage_deep
        EstimatedStage.REM -> R.string.stage_rem
        EstimatedStage.UNKNOWN -> R.string.stage_unknown
    }

fun EstimatedStage.colorAttribute(): Int =
    when (this) {
        EstimatedStage.AWAKE -> androidx.appcompat.R.attr.colorError
        EstimatedStage.LIGHT -> com.google.android.material.R.attr.colorSecondary
        EstimatedStage.DEEP -> androidx.appcompat.R.attr.colorPrimary
        EstimatedStage.REM -> com.google.android.material.R.attr.colorTertiary
        EstimatedStage.UNKNOWN -> com.google.android.material.R.attr.colorOutline
    }
