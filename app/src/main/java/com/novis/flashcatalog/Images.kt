package com.novis.flashcatalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object Images {
    private fun sampleFor(w: Int, h: Int, req: Int): Int {
        var s = 1
        while (w / (s * 2) >= req && h / (s * 2) >= req) s *= 2
        return s
    }

    fun decode(ctx: Context, uri: Uri, req: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, req) }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    fun decodeFile(path: String, req: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, req) }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Берёт снимок, поворачивает по EXIF и вырезает область рамки (доли от кадра).
     * На выходе — уже «прямой» JPEG без EXIF-поворота.
     */
    fun cropToFraction(src: File, f: RectF, outDir: File): File? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            var s = 1
            while (max(bounds.outWidth, bounds.outHeight) / s > 3200) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            var bmp = BitmapFactory.decodeFile(src.path, opts) ?: return null

            val orientation = ExifInterface(src.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (deg != 0) {
                val m = Matrix()
                m.postRotate(deg.toFloat())
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated != bmp) bmp.recycle()
                bmp = rotated
            }

            val x = (f.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (f.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val w = ((f.right - f.left) * bmp.width).toInt().coerceIn(1, bmp.width - x)
            val h = ((f.bottom - f.top) * bmp.height).toInt().coerceIn(1, bmp.height - y)
            val cropped = Bitmap.createBitmap(bmp, x, y, w, h)

            val out = File(outDir, "frame_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            return out
        } catch (e: Exception) {
            return null
        }
    }

    /** Картинка из файла, уже повёрнутая по EXIF, не больше maxDim по длинной стороне. */
    fun loadUpright(path: String, maxDim: Int): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var s = 1
            while (max(bounds.outWidth, bounds.outHeight) / s > maxDim) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            var bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            val orientation = ExifInterface(path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (deg != 0) {
                val rotated = rotate(bmp, deg)
                if (rotated != bmp) bmp.recycle()
                bmp = rotated
            }
            return bmp
        } catch (e: Exception) {
            return null
        }
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Резкость живого кадра: дисперсия лапласиана яркости в центре кадра. */
    fun lumaSharpness(image: ImageProxy): Double {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = image.width
        val h = image.height
        val x0 = maxOf(w / 4, 1)
        val x1 = minOf(w * 3 / 4, w - 1)
        val y0 = maxOf(h / 4, 1)
        val y1 = minOf(h * 3 / 4, h - 1)
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val i = y * rs + x * ps
                val c = buf.get(i).toInt() and 0xFF
                val l = buf.get(i - ps).toInt() and 0xFF
                val r = buf.get(i + ps).toInt() and 0xFF
                val u = buf.get(i - rs).toInt() and 0xFF
                val d = buf.get(i + rs).toInt() and 0xFF
                val lap = (4 * c - l - r - u - d).toDouble()
                sum += lap
                sum2 += lap * lap
                n++
                x += 2
            }
            y += 2
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    /** Резкость готового снимка (для выбора лучшего кадра серии). */
    fun sharpness(file: File): Double {
        val b = decodeFile(file.path, 900) ?: return 0.0
        val w = b.width
        val h = b.height
        if (w < 8 || h < 8) return 0.0
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val x0 = maxOf(w / 10, 1)
        val x1 = minOf(w * 9 / 10, w - 1)
        val y0 = maxOf(h / 10, 1)
        val y1 = minOf(h * 9 / 10, h - 1)
        var n = 0
        var sum = 0.0
        var sum2 = 0.0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val i = y * w + x
                val lap = (4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]).toDouble()
                sum += lap
                sum2 += lap * lap
                n++
            }
        }
        b.recycle()
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val div = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum / div
                val add = src[row + minOf(x + r + 1, w - 1)]
                val sub = src[row + maxOf(x - r, 0)]
                sum += add - sub
            }
        }
    }

    fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val div = (2 * r + 1).toFloat()
        for (x in 0 until w) {
            var sum = 0f
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / div
                val add = src[minOf(y + r + 1, h - 1) * w + x]
                val sub = src[maxOf(y - r, 0) * w + x]
                sum += add - sub
            }
        }
    }

    /** Нерезкая маска по яркости: усиливает края, цвета не трогает, слабый шум не усиливает. */
    fun unsharp(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val r = (max(w, h) / 800).coerceIn(1, 4)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            y[i] = ((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f
        }
        val blurred = y.copyOf()
        val tmp = FloatArray(w * h)
        repeat(2) {
            boxBlurH(blurred, tmp, w, h, r)
            boxBlurV(tmp, blurred, w, h, r)
        }
        for (i in px.indices) {
            val d = (y[i] - blurred[i]) * amount
            if (d > -3f && d < 3f) continue
            val p = px[i]
            val rr = (((p shr 16) and 0xFF) + d).toInt().coerceIn(0, 255)
            val gg = (((p shr 8) and 0xFF) + d).toInt().coerceIn(0, 255)
            val bb = ((p and 0xFF) + d).toInt().coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    class Shot(val file: File, val score: Double, val detected: Boolean = true)
    class Detection(val rect: RectF, val touchesBorder: Boolean)

    /** Ищет на однотонном фоне предмет (флешку) и возвращает рамку вокруг него. */
    fun detectObject(src: Bitmap): Detection? {
        val seg = segmentObject(src) ?: return null
        val sw = seg.sw
        val sh = seg.sh
        var minX = sw; var minY = sh; var maxX = -1; var maxY = -1; var area = 0
        for (i in 0 until sw * sh) {
            if (!seg.keep[i]) continue
            area++
            val x = i % sw
            val y = i / sw
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < 0 || area > sw * sh * 0.85) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val mx = max(3f, bw * 0.08f)
        val my = max(3f, bh * 0.08f)
        val rect = RectF(
            ((minX - mx) / sw).coerceIn(0f, 1f),
            ((minY - my) / sh).coerceIn(0f, 1f),
            ((maxX + 1 + mx) / sw).coerceIn(0f, 1f),
            ((maxY + 1 + my) / sh).coerceIn(0f, 1f)
        )
        val touches = minX <= 1 || minY <= 1 || maxX >= sw - 2 || maxY >= sh - 2
        return Detection(rect, touches)
    }

    /**
     * Берёт снимок на весь кадр: поворачивает по EXIF, при автообрезке находит флешку,
     * иначе режет по рамке; возвращает файл и оценку резкости.
     */
    fun processShot(src: File, frame: RectF, outDir: File, auto: Boolean): Shot? {
        try {
            val bmp = loadUpright(src.path, 3200) ?: return null
            var f = frame
            var penalty = 1.0
            var found = true
            if (auto) {
                val det = detectObject(bmp)
                if (det == null) found = false
                if (det != null) {
                    f = det.rect
                    if (det.touchesBorder) penalty = 0.3
                }
            }
            val x = (f.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (f.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val w = ((f.right - f.left) * bmp.width).toInt().coerceIn(1, bmp.width - x)
            val h = ((f.bottom - f.top) * bmp.height).toInt().coerceIn(1, bmp.height - y)
            val cropped = Bitmap.createBitmap(bmp, x, y, w, h)
            val out = File(outDir, "frame_${System.nanoTime()}.jpg")
            FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return Shot(out, sharpness(out) * penalty, found)
        } catch (e: Throwable) {
            return null
        }
    }

    class Seg(val keep: BooleanArray, val sw: Int, val sh: Int)

    /** Находит предмет на однотонном фоне. Возвращает маску на уменьшенной копии или null. */
    fun segmentObject(src: Bitmap): Seg? {
        try {
            val w = src.width
            val h = src.height
            val sc = minOf(1f, 640f / max(w, h))
            val sw = max(16, (w * sc).toInt())
            val sh = max(16, (h * sc).toInt())
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small != src) small.recycle()
            val keep = Segment.run(px, sw, sh) ?: return null
            var kArea = 0
            var kMinX = sw; var kMinY = sh; var kMaxX = -1; var kMaxY = -1
            for (i in keep.indices) {
                if (!keep[i]) continue
                kArea++
                val x = i % sw; val y = i / sw
                if (x < kMinX) kMinX = x
                if (x > kMaxX) kMaxX = x
                if (y < kMinY) kMinY = y
                if (y > kMaxY) kMaxY = y
            }
            val bboxArea = (kMaxX - kMinX + 1).toLong() * (kMaxY - kMinY + 1)
            if (bboxArea <= 0 || kArea.toDouble() / bboxArea < 0.5) return null
            return Seg(keep, sw, sh)
        } catch (e: Throwable) {
            return null
        }
    }

    /**
     * Убирает однотонный фон: заливка от краёв кадра, которая идёт по плавным переходам света
     * и останавливается на резкой границе предмета. Возвращает ARGB-картинку с прозрачным фоном.
     * Пиксели предмета не меняются, ничего не дорисовывается.
     */
    fun removeBackground(src: Bitmap): Bitmap? {
        try {
            val w = src.width
            val h = src.height
            val seg = segmentObject(src) ?: return null
            val sw = seg.sw
            val sh = seg.sh
            val total = sw * sh
            val keep = seg.keep

            val m = FloatArray(total) { if (keep[it]) 1f else 0f }
            val tmp = FloatArray(total)
            repeat(2) {
                boxBlurH(m, tmp, sw, sh, 2)
                boxBlurV(tmp, m, sw, sh, 2)
            }
            val mp = IntArray(total)
            for (i in 0 until total) {
                val v = (m[i] * 255f).toInt().coerceIn(0, 255)
                mp[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val maskSmall = Bitmap.createBitmap(mp, sw, sh, Bitmap.Config.ARGB_8888)
            val maskFull = if (sw == w && sh == h) maskSmall else Bitmap.createScaledBitmap(maskSmall, w, h, true)
            val mf = IntArray(w * h)
            maskFull.getPixels(mf, 0, w, 0, 0, w, h)
            val pf = IntArray(w * h)
            src.getPixels(pf, 0, w, 0, 0, w, h)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            for (i in pf.indices) {
                val v = mf[i] and 0xFF
                val a = ((v - 110) * 255 / 70).coerceIn(0, 255)
                pf[i] = (a shl 24) or (pf[i] and 0x00FFFFFF)
                if (a > 20) {
                    val x = i % w
                    val y = i / w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            val full = Bitmap.createBitmap(pf, w, h, Bitmap.Config.ARGB_8888)
            if (maxX < 0) return full
            // обрезаем по границам флешки с небольшим полем, чтобы она была по центру картинки
            val pad = max(8, (max(maxX - minX, maxY - minY) * 0.03f).toInt())
            val cl = (minX - pad).coerceAtLeast(0)
            val ct = (minY - pad).coerceAtLeast(0)
            val cr = (maxX + 1 + pad).coerceAtMost(w)
            val cb = (maxY + 1 + pad).coerceAtMost(h)
            return Bitmap.createBitmap(full, cl, ct, cr - cl, cb - ct)
        } catch (e: Throwable) {
            return null
        }
    }
}
