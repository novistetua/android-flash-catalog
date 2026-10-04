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
import kotlin.math.max
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
        l = 0.05f; t = 0.05f; r = 0.95f; b = 0.95f
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
        val bw = 14f * density
        val bt = 3.5f * density
        val cx = (vr.left + vr.right) / 2f
        val cy = (vr.top + vr.bottom) / 2f
        canvas.drawRoundRect(cx - bw, vr.top - bt, cx + bw, vr.top + bt, bt, bt, handle)
        canvas.drawRoundRect(cx - bw, vr.bottom - bt, cx + bw, vr.bottom + bt, bt, bt, handle)
        canvas.drawRoundRect(vr.left - bt, cy - bw, vr.left + bt, cy + bw, bt, bt, handle)
        canvas.drawRoundRect(vr.right - bt, cy - bw, vr.right + bt, cy + bw, bt, bt, handle)
    }

    private var anchorX = 0f
    private var anchorY = 0f

    private fun fx(x: Float) = ((x - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
    private fun fy(y: Float) = ((y - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null || imgRect.width() <= 0f) return false
        val minSize = 0.04f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val vr = viewRect()
                val reach = 36f * density
                val edge = 26f * density
                val inX = e.x >= vr.left - reach && e.x <= vr.right + reach
                val inY = e.y >= vr.top - reach && e.y <= vr.bottom + reach
                mode = when {
                    near(e.x, e.y, vr.left, vr.top, reach) -> 1
                    near(e.x, e.y, vr.right, vr.top, reach) -> 2
                    near(e.x, e.y, vr.left, vr.bottom, reach) -> 3
                    near(e.x, e.y, vr.right, vr.bottom, reach) -> 4
                    inY && abs(e.x - vr.left) <= edge -> 6
                    inY && abs(e.x - vr.right) <= edge -> 7
                    inX && abs(e.y - vr.top) <= edge -> 8
                    inX && abs(e.y - vr.bottom) <= edge -> 9
                    vr.contains(e.x, e.y) -> 5
                    imgRect.contains(e.x, e.y) -> 10
                    else -> 0
                }
                lastX = e.x
                lastY = e.y
                if (mode == 10) {
                    // новый прямоугольник от точки касания
                    anchorX = fx(e.x); anchorY = fy(e.y)
                    l = anchorX; r = anchorX; t = anchorY; b = anchorY
                    invalidate()
                }
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) return false
                val dx = (e.x - lastX) / imgRect.width()
                val dy = (e.y - lastY) / imgRect.height()
                lastX = e.x
                lastY = e.y
                when (mode) {
                    1 -> { l = (l + dx).coerceIn(0f, r - minSize); t = (t + dy).coerceIn(0f, b - minSize) }
                    2 -> { r = (r + dx).coerceIn(l + minSize, 1f); t = (t + dy).coerceIn(0f, b - minSize) }
                    3 -> { l = (l + dx).coerceIn(0f, r - minSize); b = (b + dy).coerceIn(t + minSize, 1f) }
                    4 -> { r = (r + dx).coerceIn(l + minSize, 1f); b = (b + dy).coerceIn(t + minSize, 1f) }
                    6 -> l = (l + dx).coerceIn(0f, r - minSize)
                    7 -> r = (r + dx).coerceIn(l + minSize, 1f)
                    8 -> t = (t + dy).coerceIn(0f, b - minSize)
                    9 -> b = (b + dy).coerceIn(t + minSize, 1f)
                    5 -> {
                        val w = r - l
                        val h = b - t
                        val nl = (l + dx).coerceIn(0f, 1f - w)
                        val nt = (t + dy).coerceIn(0f, 1f - h)
                        l = nl; t = nt; r = nl + w; b = nt + h
                    }
                    10 -> {
                        val cx = fx(e.x); val cy = fy(e.y)
                        l = min(anchorX, cx); r = max(anchorX, cx)
                        t = min(anchorY, cy); b = max(anchorY, cy)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 10) {
                    // слишком маленький выделенный прямоугольник растягиваем до минимума
                    if (r - l < 0.08f) { val c = (l + r) / 2f; l = (c - 0.04f).coerceAtLeast(0f); r = (l + 0.08f).coerceAtMost(1f); l = r - 0.08f }
                    if (b - t < 0.08f) { val c = (t + b) / 2f; t = (c - 0.04f).coerceAtLeast(0f); b = (t + 0.08f).coerceAtMost(1f); t = b - 0.08f }
                    invalidate()
                }
                mode = 0
                return true
            }
        }
        return true
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float, reach: Float): Boolean =
        abs(x - cx) <= reach && abs(y - cy) <= reach
}
