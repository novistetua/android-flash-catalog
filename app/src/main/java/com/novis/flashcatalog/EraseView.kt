package com.novis.flashcatalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Картинка с зумом (щипок) и перемещением (двумя пальцами); одним пальцем рисуем кистью. */
class EraseView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onStrokeStart()
        fun onStroke(x: Float, y: Float) // координаты в пикселях картинки
        fun onStrokeEnd()
    }

    var listener: Listener? = null
    var brushRadius = 18f // в пикселях картинки
    private var bmp: Bitmap? = null
    private val m = Matrix()
    private val inv = Matrix()
    private var fit = 1f
    private var scale = 1f
    private val density = resources.displayMetrics.density

    private val checker = Paint()
    private val ring = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val ringIn = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1f * density
        isAntiAlias = true
    }
    private val dashWhite = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * density
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }
    private val dashDark = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(160, 0, 0, 0)
        strokeWidth = 4.5f * density
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }
    private val linkPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * density
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        isAntiAlias = true
    }
    private val imgPaint = Paint().apply { isFilterBitmap = true }

    private var drawing = false
    private var touchX = -1f
    private var touchY = -1f
    private var multi = false
    private var lastFx = 0f
    private var lastFy = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(fit * 0.8f, fit * 14f)
            val f = ns / scale
            scale = ns
            m.postScale(f, f, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    var brushEnabled = true
    /** Куда сдвинута кисть относительно пальца: 1 — вверх, -1 — вниз, 0 — под пальцем. */
    var offsetDir = 1
    private var panX = 0f
    private var panY = 0f

    private fun focusOf(e: MotionEvent, skip: Int = -1) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n > 0) { lastFx = sx / n; lastFy = sy / n }
    }

    init {
        val tile = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tile.setPixel(0, 0, 0xFFD5DCE0.toInt()); tile.setPixel(1, 1, 0xFFD5DCE0.toInt())
        tile.setPixel(1, 0, 0xFFA9B6BE.toInt()); tile.setPixel(0, 1, 0xFFA9B6BE.toInt())
        val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val sm = Matrix()
        sm.setScale(8f * density, 8f * density)
        shader.setLocalMatrix(sm)
        checker.shader = shader
        checker.isFilterBitmap = false
    }

    fun setBitmap(b: Bitmap) {
        val first = bmp == null
        bmp = b
        if (first) fitToView()
        invalidate()
    }

    private fun fitToView() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        fit = min(width.toFloat() / b.width, height.toFloat() / b.height)
        scale = fit
        m.reset()
        m.postScale(fit, fit)
        m.postTranslate((width - b.width * fit) / 2f, (height - b.height * fit) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fitToView()
    }

    fun resetView() {
        fitToView()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawColor(0xFF263238.toInt())
        c.save()
        c.concat(m)
        // шахматка только под картинкой, в экранных координатах
        c.save()
        c.setMatrix(null)
        val pts = floatArrayOf(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapPoints(pts)
        c.clipRect(pts[0], pts[1], pts[2], pts[3])
        c.drawPaint(checker)
        c.restore()
        c.drawBitmap(b, 0f, 0f, imgPaint)
        c.restore()
        if (touchX >= 0f) {
            val r = brushRadius * scale
            val by = touchY + offY()
            if (offsetDir != 0) {
                // пунктирный кружок «для пальца» и линия к настоящей кисти
                val fr = 30f * density
                c.drawLine(touchX, touchY, touchX, by, linkPaint)
                c.drawCircle(touchX, touchY, fr, dashDark)
                c.drawCircle(touchX, touchY, fr, dashWhite)
            }
            c.drawCircle(touchX, by, r, ring)
            c.drawCircle(touchX, by, r, ringIn)
            c.drawCircle(touchX, by, 2.5f * density, dotPaint)
        }
    }

    /** Сдвиг кисти от пальца по вертикали в пикселях экрана (минус — вверх). */
    private fun offY(): Float {
        if (offsetDir == 0) return 0f
        val off = max(56f * density, brushRadius * scale + 34f * density)
        return -offsetDir * off
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        m.invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return p
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false
                panX = e.x; panY = e.y
                if (brushEnabled) {
                    drawing = true
                    touchX = e.x; touchY = e.y
                    listener?.onStrokeStart()
                    val p = toImage(e.x, e.y + offY())
                    listener?.onStroke(p[0], p[1])
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                if (drawing) { drawing = false; listener?.onStrokeEnd() }
                touchX = -1f
                focusOf(e) // стартовая точка для сдвига двумя пальцами
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // палец поднят: пересчитываем центр по оставшимся, чтобы картинка не прыгала
                focusOf(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        var sx = 0f; var sy = 0f
                        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
                        val fx = sx / e.pointerCount
                        val fy = sy / e.pointerCount
                        m.postTranslate(fx - lastFx, fy - lastFy)
                        lastFx = fx; lastFy = fy
                        invalidate()
                    }
                } else if (!brushEnabled) {
                    m.postTranslate(e.x - panX, e.y - panY)
                    panX = e.x; panY = e.y
                    invalidate()
                } else if (drawing && e.pointerCount == 1) {
                    touchX = e.x; touchY = e.y
                    for (i in 0 until e.historySize) {
                        val hp = toImage(e.getHistoricalX(i), e.getHistoricalY(i) + offY())
                        listener?.onStroke(hp[0], hp[1])
                    }
                    val p = toImage(e.x, e.y + offY())
                    listener?.onStroke(p[0], p[1])
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawing) { drawing = false; listener?.onStrokeEnd() }
                touchX = -1f
                multi = false
                invalidate()
            }
        }
        return true
    }
}
