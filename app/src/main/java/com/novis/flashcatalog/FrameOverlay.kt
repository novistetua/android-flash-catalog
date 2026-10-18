package com.novis.flashcatalog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class FrameOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    companion object {
        const val WIDTH_FRAC = 0.92f
        const val ASPECT = 1.6f // ширина / высота рамки

        /** Тип рамки: sd (microSD/SD), usb (флешка стоймя, узкая и высокая), ssd (1.43:1), free (рамку двигает сам пользователь). */
        @Volatile var format = "sd"

        /** Свободная рамка в долях экрана (left, top, right, bottom). */
        @Volatile var free = RectF(0.10f, 0.28f, 0.90f, 0.62f)

        val FORMATS = listOf("sd", "usb", "ssd", "free")

        fun formatName(f: String): String = when (f) {
            "usb" -> "USB-флешка (стоймя)"
            "ssd" -> "SSD / диск (1.43 : 1)"
            "free" -> "Свободная рамка"
            else -> "microSD / SD"
        }

        fun frame(w: Float, h: Float): RectF {
            if (format == "free") {
                val f = free
                return RectF(f.left * w, f.top * h, f.right * w, f.bottom * h)
            }
            var fw = w * WIDTH_FRAC
            var fh = fw / ASPECT
            when (format) {
                "usb" -> { fh = h * 0.68f; fw = fh * 0.42f; if (fw > w * 0.92f) { fw = w * 0.92f; fh = fw / 0.42f } }
                "ssd" -> { fh = fw / 1.43f }
            }
            val l = (w - fw) / 2f
            val t = h * 0.45f - fh / 2f
            return RectF(l, t, l + fw, t + fh)
        }

        /** Читает сохранённые настройки рамки. */
        fun load(ctx: Context) {
            val p = ctx.getSharedPreferences("fc", Context.MODE_PRIVATE)
            format = p.getString("fmt", "sd")?.takeIf { it in FORMATS } ?: "sd"
            try {
                val v = p.getString("free_rect", null)?.split(",")?.map { it.toFloat() }
                if (v != null && v.size == 4 && v[2] - v[0] > 0.1f && v[3] - v[1] > 0.1f) free = RectF(v[0], v[1], v[2], v[3])
            } catch (e: Exception) { /* оставляем по умолчанию */ }
        }

        fun setFormat(ctx: Context, f: String) {
            format = f
            ctx.getSharedPreferences("fc", Context.MODE_PRIVATE).edit().putString("fmt", f).apply()
        }

        /** Диалог выбора типа рамки. onChosen вызывается после выбора. */
        fun choose(act: android.app.Activity, onChosen: (String) -> Unit) {
            val cur = FORMATS.indexOf(format).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("Тип рамки")
                .setSingleChoiceItems(FORMATS.map { formatName(it) }.toTypedArray(), cur) { d, i ->
                    setFormat(act, FORMATS[i])
                    d.dismiss()
                    if (FORMATS[i] == "free") android.widget.Toast.makeText(act, "Тяни рамку за углы и стороны, двигай за середину", android.widget.Toast.LENGTH_LONG).show()
                    onChosen(FORMATS[i])
                }
                .setNegativeButton("Отмена", null)
                .show()
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

    /** Короткое касание внутри свободной рамки (не перетаскивание): передаём наружу, например для фокуса. */
    var onTap: ((Float, Float) -> Unit)? = null
    private var dragMode = 0 // 1 лево, 2 верх, 4 право, 8 низ, 16 перенос
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var movedFar = false

    private fun hit(x: Float, y: Float): Int {
        val r = frame(width.toFloat(), height.toFloat())
        val tol = 30f * resources.displayMetrics.density
        val inY = y > r.top - tol && y < r.bottom + tol
        val inX = x > r.left - tol && x < r.right + tol
        var m = 0
        if (inY && Math.abs(x - r.left) < tol) m = m or 1
        if (inX && Math.abs(y - r.top) < tol) m = m or 2
        if (inY && Math.abs(x - r.right) < tol) m = m or 4
        if (inX && Math.abs(y - r.bottom) < tol) m = m or 8
        if (m != 0) return m
        return if (r.contains(x, y)) 16 else 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (format != "free" || width == 0 || height == 0) return false
        val w = width.toFloat()
        val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hit(e.x, e.y)
                if (dragMode == 0) return false
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; movedFar = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 0) return false
                if (e.pointerCount > 1) return true
                val dx = (e.x - lastX) / w
                val dy = (e.y - lastY) / h
                lastX = e.x; lastY = e.y
                if (Math.abs(e.x - downX) > 12f * resources.displayMetrics.density || Math.abs(e.y - downY) > 12f * resources.displayMetrics.density) movedFar = true
                val f = RectF(free)
                val minW = 0.12f
                val minH = 0.08f
                if (dragMode == 16) {
                    val fw = f.width(); val fh = f.height()
                    f.left = (f.left + dx).coerceIn(0f, 1f - fw); f.right = f.left + fw
                    f.top = (f.top + dy).coerceIn(0f, 1f - fh); f.bottom = f.top + fh
                } else {
                    if (dragMode and 1 != 0) f.left = (f.left + dx).coerceIn(0f, f.right - minW)
                    if (dragMode and 4 != 0) f.right = (f.right + dx).coerceIn(f.left + minW, 1f)
                    if (dragMode and 2 != 0) f.top = (f.top + dy).coerceIn(0f, f.bottom - minH)
                    if (dragMode and 8 != 0) f.bottom = (f.bottom + dy).coerceIn(f.top + minH, 1f)
                }
                free = f
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == 0) return false
                val wasMove = dragMode == 16
                dragMode = 0
                val f = free
                context.getSharedPreferences("fc", Context.MODE_PRIVATE).edit().putString("free_rect", "${f.left},${f.top},${f.right},${f.bottom}").apply()
                if (e.actionMasked == MotionEvent.ACTION_UP && wasMove && !movedFar) onTap?.invoke(e.x, e.y)
                return true
            }
        }
        return dragMode != 0
    }

    private var focusX = -1f
    private var focusY = -1f
    private val ring = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 2f * resources.displayMetrics.density
    }

    fun showFocus(x: Float, y: Float) {
        focusX = x
        focusY = y
        invalidate()
        postDelayed({
            focusX = -1f
            invalidate()
        }, 900)
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
        if (format == "free") {
            val hr = 7f * resources.displayMetrics.density
            val hp = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            for (px in floatArrayOf(r.left, r.right)) for (py in floatArrayOf(r.top, r.bottom)) canvas.drawCircle(px, py, hr, hp)
            canvas.drawCircle((r.left + r.right) / 2f, r.top, hr, hp); canvas.drawCircle((r.left + r.right) / 2f, r.bottom, hr, hp)
            canvas.drawCircle(r.left, (r.top + r.bottom) / 2f, hr, hp); canvas.drawCircle(r.right, (r.top + r.bottom) / 2f, hr, hp)
        }
        if (focusX >= 0f) {
            canvas.drawCircle(focusX, focusY, 36f * resources.displayMetrics.density, ring)
        }
    }
}
