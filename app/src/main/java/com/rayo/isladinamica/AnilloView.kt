package com.rayo.isladinamica

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Charging ring around the camera (guide §7.2, v1.11). A thin arc on a
 * faint track, drawn in its own non-touchable window so it never
 * interferes with the island's touches. Green while charging or above
 * 15%, red at 15% or less when not charging. Progress is animated with
 * ObjectAnimator on the "progreso" property.
 */
class AnilloView(ctx: Context) : View(ctx) {
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }
    var color = 0xFF34C759.toInt()
    var progreso = 0f
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(c: Canvas) {
        val m = trazo.strokeWidth / 2
        val r = RectF(m, m, width - m, height - m)
        trazo.color = color
        trazo.alpha = 50
        c.drawArc(r, 0f, 360f, false, trazo) // faint track
        trazo.alpha = 255
        c.drawArc(r, -90f, 360f * progreso, false, trazo)
    }
}
