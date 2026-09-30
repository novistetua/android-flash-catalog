package com.novis.flashcatalog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FrameOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    companion object {
        const val WIDTH_FRAC = 0.92f
        const val ASPECT = 1.6f // ширина / высота рамки

        fun frame(w: Float, h: Float): RectF {
            val fw = w * WIDTH_FRAC
            val fh = fw / ASPECT
            val l = (w - fw) / 2f
            val t = h * 0.45f - fh / 2f
            return RectF(l, t, l + fw, t + fh)
        }

        fun fractions(w: Float, h: Float): RectF {
            val r = frame(w, h)
            return RectF(r.left / w, r.top / h, r.right / w, r.bottom / h)
        }
    }

    private val dim = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val border = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val grid = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = frame(w, h)
        val rad = 12f * resources.displayMetrics.density

        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, w, h, Path.Direction.CW)
            addRoundRect(r, rad, rad, Path.Direction.CW)
        }
        canvas.drawPath(path, dim)
        canvas.drawRoundRect(r, rad, rad, border)

        val dx = r.width() / 3f
        val dy = r.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(r.left + dx * i, r.top, r.left + dx * i, r.bottom, grid)
            canvas.drawLine(r.left, r.top + dy * i, r.right, r.top + dy * i, grid)
        }
    }
}
