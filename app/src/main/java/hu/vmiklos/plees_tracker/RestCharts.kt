/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.roundToInt

val restStageOrder =
    listOf(
        EstimatedStage.AWAKE,
        EstimatedStage.LIGHT,
        EstimatedStage.DEEP,
        EstimatedStage.REM,
        EstimatedStage.UNKNOWN,
    )

fun View.restColor(attribute: Int): Int = MaterialColors.getColor(this, attribute)

fun View.restDp(value: Float): Float = value * resources.displayMetrics.density

fun View.restSp(value: Float): Float =
    android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

/** Finite, system-duration-scale-aware reveal animations. */
abstract class RestChart(context: Context) : View(context) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected var reveal = 1f
    private var animator: ValueAnimator? = null

    protected fun revealData() {
        animator?.cancel()
        if (!isAttachedToWindow) {
            reveal = 1f
            invalidate()
            return
        }
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 550
                interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener {
                    reveal = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    protected fun label(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float = 11f,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.color = restColor(com.google.android.material.R.attr.colorOnSurface)
        paint.textSize = restSp(size)
        paint.textAlign = align
        paint.typeface =
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }
}

class StageRingView(context: Context) : RestChart(context) {
    private var totals: Map<EstimatedStage, Long> = emptyMap()
    private var highlighted: EstimatedStage? = null

    fun show(totals: Map<EstimatedStage, Long>) {
        this.totals = totals
        highlighted = null
        revealData()
        contentDescription =
            totals.entries.joinToString(". ") {
                context.getString(
                    R.string.selected_stage_value,
                    context.getString(it.key.label()),
                    RestInsights.duration(it.value),
                )
            }
    }

    fun highlight(stage: EstimatedStage) {
        highlighted = if (highlighted == stage) null else stage
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val total = totals.values.sum().coerceAtLeast(1)
        val size = minOf(width, height).toFloat()
        val stroke = restDp(25f)
        val rect =
            RectF(
                (width - size) / 2 + stroke,
                (height - size) / 2 + stroke,
                (width + size) / 2 - stroke,
                (height + size) / 2 - stroke,
            )
        var angle = -90f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.BUTT
        for (stage in restStageOrder) {
            val sweep = (totals[stage] ?: 0L).toFloat() / total * 360f
            val gap = minOf(3f, sweep * .15f)
            paint.color = restColor(stage.colorAttribute())
            paint.alpha = if (highlighted == null || stage == highlighted) 255 else 70
            if (sweep > 0)
                canvas.drawArc(rect, angle + gap / 2, (sweep - gap) * reveal, false, paint)
            angle += sweep
        }
        paint.alpha = 255
        val coverage = RestInsights.estimatedCoverage(totals)
        val title =
            highlighted?.let { RestInsights.duration(totals[it] ?: 0) }
                ?: if (coverage == 0) "—" else "$coverage%"
        val subtitle =
            highlighted?.let { context.getString(it.label()) }
                ?: context.getString(
                    if (coverage == 0) R.string.no_stage_data else R.string.estimated_coverage
                )
        label(canvas, title, width / 2f, height / 2f + restDp(4f), 35f, Paint.Align.CENTER)
        label(canvas, subtitle, width / 2f, height / 2f + restDp(28f), 11f, Paint.Align.CENTER)
    }
}

class GoalBloomView(context: Context) : View(context) {
    var percentage = 0
        set(value) {
            field = value
            contentDescription = context.getString(R.string.goal_percent, value)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloom =
        RoundedPolygon.star(12, innerRadius = .86f, rounding = CornerRounding(.25f)).normalized()

    override fun onDraw(canvas: Canvas) {
        val path = bloom.toPath()
        path.transform(Matrix().apply { setScale(width.toFloat(), height.toFloat()) })
        paint.color = restColor(com.google.android.material.R.attr.colorSecondaryContainer)
        canvas.drawPath(path, paint)
        paint.color = restColor(com.google.android.material.R.attr.colorOnSecondaryContainer)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = restSp(28f)
        paint.typeface =
            android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.BOLD)
        canvas.drawText(
            context.getString(R.string.goal_percent, percentage),
            width / 2f,
            height / 2f,
            paint,
        )
        paint.textSize = restSp(10f)
        canvas.drawText(
            context.getString(R.string.goal_badge),
            width / 2f,
            height / 2f + restDp(20f),
            paint,
        )
    }
}

class RhythmGardenView(context: Context) : RestChart(context) {
    private var states: List<Boolean?> = emptyList()
    private var ending = 0L

    fun show(states: List<Boolean?>, ending: Long) {
        this.states = states
        this.ending = ending
        revealData()
        contentDescription =
            context.getString(R.string.garden_accessibility, states.count { it == true }) +
            ". " +
            context.getString(R.string.garden_key)
    }

    override fun onDraw(canvas: Canvas) {
        if (states.isEmpty()) return
        val cell = width / 7f
        for ((i, state) in states.withIndex()) {
            val cx = cell * (i + .5f)
            val cy = restDp(24f)
            paint.style = if (state == true) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = restDp(2f)
            paint.color =
                restColor(
                    if (state == true) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOutline
                )
            if (state == true) {
                val path =
                    RoundedPolygon.star(6, innerRadius = .82f, rounding = CornerRounding(.3f))
                        .normalized()
                        .toPath()
                path.transform(
                    Matrix().apply {
                        setScale(restDp(36f) * reveal, restDp(36f) * reveal)
                        postTranslate(cx - restDp(18f) * reveal, cy - restDp(18f) * reveal)
                    }
                )
                canvas.drawPath(path, paint)
            } else canvas.drawCircle(cx, cy, restDp(15f), paint)
            if (state != true)
                label(
                    canvas,
                    if (state == null) "—" else "·",
                    cx,
                    cy + restDp(4f),
                    14f,
                    Paint.Align.CENTER,
                )
            label(
                canvas,
                RestInsights.date(RestInsights.plusDays(ending, i - 6), "EEE"),
                cx,
                restDp(61f),
                11f,
                Paint.Align.CENTER,
            )
        }
    }
}

class RestHistoryChart(context: Context, private val stacked: Boolean) : RestChart(context) {
    private var days: List<RestDay> = emptyList()
    private var goal = 8f
    var onSelect: ((Sleep?) -> Unit)? = null

    fun show(days: List<RestDay>, goal: Float) {
        this.days = days
        this.goal = goal
        revealData()
        contentDescription =
            days.joinToString(". ") { day ->
            RestInsights.date(day.date, "MMM d") +
                ": " +
                if (day.sleeps.isEmpty()) context.getString(R.string.no_record_day)
                else RestInsights.duration(day.duration)
        } + ". " + context.getString(R.string.chart_legend)
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val left = restDp(31f)
        val right = width - restDp(12f)
        val top = restDp(24f)
        val bottom = height - restDp(37f)
        val maxHours = max(goal + 2, days.maxOfOrNull { it.duration / 3600000f } ?: 10f)
        fun y(hours: Float) = bottom - (bottom - top) * hours / maxHours
        val step = (right - left) / days.size
        fun x(index: Int) = left + step * (index + .5f)
        paint.strokeWidth = restDp(1f)
        paint.style = Paint.Style.STROKE
        for (tick in listOf(0f, maxHours / 2, maxHours)) {
            paint.color = restColor(com.google.android.material.R.attr.colorOutlineVariant)
            canvas.drawLine(left, y(tick), right, y(tick), paint)
            label(
                canvas,
                String.format(java.util.Locale.getDefault(), "%.0f", tick),
                left - restDp(8f),
                y(tick) + restDp(4f),
                10f,
                Paint.Align.RIGHT,
            )
            paint.style = Paint.Style.STROKE
        }
        paint.color = restColor(com.google.android.material.R.attr.colorTertiary)
        paint.strokeWidth = restDp(2f)
        paint.pathEffect = DashPathEffect(floatArrayOf(restDp(5f), restDp(5f)), 0f)
        canvas.drawLine(left, y(goal), right, y(goal), paint)
        paint.pathEffect = null
        if (stacked) {
            for ((i, day) in days.withIndex()) {
                var accumulated = 0f
                val barWidth = minOf(step * .65f, restDp(34f))
                if (day.sleeps.isEmpty()) {
                    paint.color = restColor(com.google.android.material.R.attr.colorOutlineVariant)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = restDp(1.5f)
                    canvas.drawRoundRect(
                        x(i) - barWidth / 2,
                        bottom - restDp(8f),
                        x(i) + barWidth / 2,
                        bottom,
                        restDp(4f),
                        restDp(4f),
                        paint,
                    )
                }
                for (stage in restStageOrder.reversed()) {
                    val hours = (day.stages[stage] ?: 0) / 3600000f * reveal
                    if (hours <= 0) continue
                    paint.style = Paint.Style.FILL
                    paint.color = restColor(stage.colorAttribute())
                    canvas.drawRect(
                        x(i) - barWidth / 2,
                        y(accumulated + hours),
                        x(i) + barWidth / 2,
                        y(accumulated),
                        paint,
                    )
                    accumulated += hours
                }
            }
        } else {
            val path = Path()
            var connected = false
            for ((i, day) in days.withIndex()) {
                if (day.sleeps.isEmpty()) {
                    connected = false
                    continue
                }
                val yy = y(day.duration / 3600000f * reveal)
                if (connected) path.lineTo(x(i), yy) else path.moveTo(x(i), yy)
                connected = true
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = restDp(3.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = restColor(androidx.appcompat.R.attr.colorPrimary)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            for ((i, day) in days.withIndex()) if (day.sleeps.isNotEmpty())
                canvas.drawCircle(x(i), y(day.duration / 3600000f * reveal), restDp(4.5f), paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        for (i in days.indices) if (
            days.size <= 7 || i == 0 || i == days.lastIndex || i == days.size / 2
        )
            label(
                canvas,
                RestInsights.date(
                    days[i].date,
                    if (days[i].labelPattern != "MMM d") days[i].labelPattern
                    else if (days.size <= 7) "EEE" else "MMM d",
                ),
                x(i),
                height - restDp(12f),
                10f,
                Paint.Align.CENTER,
            )
        if (days.all { it.sleeps.isEmpty() })
            label(
                canvas,
                context.getString(R.string.no_stage_data),
                width / 2f,
                height / 2f,
                14f,
                Paint.Align.CENTER,
            )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && days.isNotEmpty()) {
            val left = restDp(31f)
            val right = width - restDp(12f)
            val index =
                (((event.x - left) / (right - left)) * days.size - .5f)
                    .roundToInt()
                    .coerceIn(0, days.lastIndex)
            onSelect?.invoke(
                if (days[index].labelPattern == "MMM d") days[index].mainSleep
                else days[index].sleeps.maxByOrNull { it.stop }
            )
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
