package com.novis.flashcatalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class CropView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var bmp: Bitmap? = null
    private val imgRect = RectF()

    // рамка в долях изображения (0..1)
    private var l = 0.1f
    private var t = 0.1f
    private var r = 0.9f
    private var b = 0.9f

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        isAntiAlias = true
    }
    private val grid = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val handle = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var mode = 0 // 0 нет, 1 лв, 2 пв, 3 лн, 4 пн, 5 двигаем
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(bitmap: Bitmap) {
        bmp = bitmap
        l = 0.1f; t = 0.1f; r = 0.9f; b = 0.9f
        layoutImage()
        invalidate()
    }

    fun fractions(): RectF = RectF(l, t, r, b)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
    }

    private fun layoutImage() {
        val bm = bmp ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bm.width, height.toFloat() / bm.height)
        val w = bm.width * scale
        val h = bm.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imgRect.set(left, top, left + w, top + h)
    }

    private fun viewRect(): RectF = RectF(
        imgRect.left + l * imgRect.width(),
        imgRect.top + t * imgRect.height(),
        imgRect.left + r * imgRect.width(),
        imgRect.top + b * imgRect.height()
    )

    override fun onDraw(canvas: Canvas) {
        val bm = bmp ?: return
        canvas.drawBitmap(bm, null, imgRect, null)
        val vr = viewRect()
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imgRect, Path.Direction.CW)
            addRect(vr, Path.Direction.CW)
        }
        canvas.drawPath(path, dim)
        canvas.drawRect(vr, border)
        val dx = vr.width() / 3f
        val dy = vr.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(vr.left + dx * i, vr.top, vr.left + dx * i, vr.bottom, grid)
            canvas.drawLine(vr.left, vr.top + dy * i, vr.right, vr.top + dy * i, grid)
        }
        val rad = 9f * density
        canvas.drawCircle(vr.left, vr.top, rad, handle)
        canvas.drawCircle(vr.right, vr.top, rad, handle)
        canvas.drawCircle(vr.left, vr.bottom, rad, handle)
        canvas.drawCircle(vr.right, vr.bottom, rad, handle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null || imgRect.width() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val vr = viewRect()
                val reach = 40f * density
                mode = when {
                    near(e.x, e.y, vr.left, vr.top, reach) -> 1
                    near(e.x, e.y, vr.right, vr.top, reach) -> 2
                    near(e.x, e.y, vr.left, vr.bottom, reach) -> 3
                    near(e.x, e.y, vr.right, vr.bottom, reach) -> 4
                    vr.contains(e.x, e.y) -> 5
                    else -> 0
                }
                lastX = e.x
                lastY = e.y
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return false
                val fx = (e.x - lastX) / imgRect.width()
                val fy = (e.y - lastY) / imgRect.height()
                lastX = e.x
                lastY = e.y
                val minSize = 0.08f
                when (mode) {
                    1 -> { l = (l + fx).coerceIn(0f, r - minSize); t = (t + fy).coerceIn(0f, b - minSize) }
                    2 -> { r = (r + fx).coerceIn(l + minSize, 1f); t = (t + fy).coerceIn(0f, b - minSize) }
                    3 -> { l = (l + fx).coerceIn(0f, r - minSize); b = (b + fy).coerceIn(t + minSize, 1f) }
                    4 -> { r = (r + fx).coerceIn(l + minSize, 1f); b = (b + fy).coerceIn(t + minSize, 1f) }
                    5 -> {
                        val w = r - l
                        val h = b - t
                        val nl = (l + fx).coerceIn(0f, 1f - w)
                        val nt = (t + fy).coerceIn(0f, 1f - h)
                        l = nl; t = nt; r = nl + w; b = nt + h
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = 0
                return true
            }
        }
        return true
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float, reach: Float): Boolean =
        abs(x - cx) <= reach && abs(y - cy) <= reach
}
