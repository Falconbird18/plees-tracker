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
import kotlin.math.cos
import kotlin.math.sin

/** Vector artwork that follows the selected color scheme at every size. */
class SleepSkyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val container =
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        val scale = minOf(height / 136f, width / 260f)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        paint.color = primary
        canvas.drawCircle(0f, 0f, 48f, paint)
        paint.color = container
        canvas.drawCircle(23f, -18f, 43f, paint)
        paint.color = primary
        for (
            (x, y, radius) in
            listOf(
                Triple(-100f, -30f, 11f),
                Triple(98f, 22f, 15f),
                Triple(70f, -43f, 7f),
                Triple(-67f, 43f, 6f),
            )
        ) {
            val path = android.graphics.Path()
            for (i in 0..7) {
                val angle = i * Math.PI / 4
                val r = if (i % 2 == 0) radius else radius * .28f
                val px = x + cos(angle).toFloat() * r
                val py = y + sin(angle).toFloat() * r
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }
}
