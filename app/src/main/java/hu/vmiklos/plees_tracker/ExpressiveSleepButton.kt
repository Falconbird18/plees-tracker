/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Region
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import com.google.android.material.button.MaterialButton

/** Official Expressive cookie → sunny polygons; native button semantics, tinting, and ripple. */
class ExpressiveSleepButton
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : MaterialButton(context, attrs) {
    private val morph by lazy {
        // Material's Cookie 9 and Sunny catalog geometry, constructed with public AndroidX APIs.
        // The MaterialShapes catalog itself is currently restricted to library-internal use.
        Morph(catalogShape(9, .5f, -90f), catalogShape(8, .15f, 0f))
    }

    private fun catalogShape(points: Int, rounding: Float, rotation: Float): RoundedPolygon {
        val shape =
            RoundedPolygon.star(
                points,
                radius = 1f,
                innerRadius = .8f,
                rounding = CornerRounding(rounding),
            )
                .transformed(Matrix().apply { setRotate(rotation) })
        val bounds = shape.calculateMaxBounds()
        val size = maxOf(bounds[2] - bounds[0], bounds[3] - bounds[1])
        val transform =
            Matrix().apply {
                setScale(1f / size, 1f / size)
                preTranslate(-(bounds[0] + bounds[2]) / 2f, -(bounds[1] + bounds[3]) / 2f)
                postTranslate(.5f, .5f)
            }
        return shape.transformed(transform)
    }

    private val polygon = Path()
    private val matrix = Matrix()
    private var progress = 0f
    private var tracking = false
    private var ready = false
    private var animator: ValueAnimator? = null

    init {
        cornerRadius = 0
        elevation = 0f
        stateListAnimator = null
        ready = true
    }

    fun setTrackingState(value: Boolean) {
        if (tracking == value) return
        tracking = value
        animateShape()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (ready) animateShape()
    }

    private fun animateShape() {
        val target =
            if (tracking) {
                if (isPressed) .82f else 1f
            } else {
                if (isPressed) .18f else 0f
            }
        animator?.cancel()
        if (!isLaidOut) {
            progress = target
            invalidate()
            return
        }
        animator =
            ValueAnimator.ofFloat(progress, target).apply {
                duration = if (isPressed) 180 else 550
                interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun updatePath() {
        morph.toPath(progress, polygon)
        matrix.setScale(width.toFloat(), height.toFloat())
        polygon.transform(matrix)
    }

    override fun draw(canvas: Canvas) {
        updatePath()
        val saved = canvas.save()
        canvas.clipPath(polygon)
        super.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            updatePath()
            val inside =
                Region()
                    .apply { setPath(polygon, Region(0, 0, width, height)) }
                    .contains(event.x.toInt(), event.y.toInt())
            if (!inside) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                val handled = super.onTouchEvent(cancel)
                cancel.recycle()
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
