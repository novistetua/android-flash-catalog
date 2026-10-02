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

    private fun boxBlurH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
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

    private fun boxBlurV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
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

    class Shot(val file: File, val score: Double)
    class Detection(val rect: RectF, val touchesBorder: Boolean)

    /** Ищет на однотонном фоне крупный предмет (флешку) и возвращает рамку вокруг него. */
    fun detectObject(src: Bitmap): Detection? {
        val scale = 320f / max(src.width, src.height)
        val w = max(16, (src.width * scale).toInt())
        val h = max(16, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != src) small.recycle()

        // цвет фона: медиана по краю кадра
        val bw = max(2, w / 16)
        val bh = max(2, h / 16)
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < bw || x >= w - bw || y < bh || y >= h - bh) {
                    val p = px[y * w + x]
                    rs.add((p shr 16) and 0xFF)
                    gs.add((p shr 8) and 0xFF)
                    bs.add(p and 0xFF)
                }
            }
        }
        rs.sort(); gs.sort(); bs.sort()
        val br = rs[rs.size / 2]
        val bg = gs[gs.size / 2]
        val bb = bs[bs.size / 2]

        val total = w * h
        val dist = IntArray(total)
        val hist = IntArray(256)
        for (i in 0 until total) {
            val p = px[i]
            val d = (kotlin.math.abs(((p shr 16) and 0xFF) - br) +
                kotlin.math.abs(((p shr 8) and 0xFF) - bg) +
                kotlin.math.abs((p and 0xFF) - bb)) / 3
            dist[i] = d
            hist[d.coerceIn(0, 255)]++
        }

        // порог по Отсу
        var sumAll = 0L
        for (t in 0..255) sumAll += t.toLong() * hist[t]
        var wB = 0
        var sumB = 0L
        var maxVar = -1.0
        var thr = 0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) {
                maxVar = v
                thr = t
            }
        }
        val th = max(thr, 28)

        // самая большая связная область
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var bestArea = 0
        var bMinX = 0
        var bMinY = 0
        var bMaxX = 0
        var bMaxY = 0
        for (start in 0 until total) {
            if (visited[start] || dist[start] <= th) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var area = 0
            while (head < tail) {
                val c = queue[head++]
                val cx = c % w
                val cy = c / w
                area++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0) { val n = c - 1; if (!visited[n] && dist[n] > th) { visited[n] = true; queue[tail++] = n } }
                if (cx < w - 1) { val n = c + 1; if (!visited[n] && dist[n] > th) { visited[n] = true; queue[tail++] = n } }
                if (cy > 0) { val n = c - w; if (!visited[n] && dist[n] > th) { visited[n] = true; queue[tail++] = n } }
                if (cy < h - 1) { val n = c + w; if (!visited[n] && dist[n] > th) { visited[n] = true; queue[tail++] = n } }
            }
            if (area > bestArea) {
                bestArea = area
                bMinX = minX; bMinY = minY; bMaxX = maxX; bMaxY = maxY
            }
        }
        if (bestArea < total * 0.02 || bestArea > total * 0.8) return null
        val boxW = bMaxX - bMinX + 1
        val boxH = bMaxY - bMinY + 1
        if (boxW.toFloat() * boxH < total * 0.03f) return null

        val mx = max(3f, boxW * 0.10f)
        val my = max(3f, boxH * 0.10f)
        val rect = RectF(
            ((bMinX - mx) / w).coerceIn(0f, 1f),
            ((bMinY - my) / h).coerceIn(0f, 1f),
            ((bMaxX + 1 + mx) / w).coerceIn(0f, 1f),
            ((bMaxY + 1 + my) / h).coerceIn(0f, 1f)
        )
        val touches = bMinX <= 1 || bMinY <= 1 || bMaxX >= w - 2 || bMaxY >= h - 2
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
            if (auto) {
                val det = detectObject(bmp)
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
            return Shot(out, sharpness(out) * penalty)
        } catch (e: Throwable) {
            return null
        }
    }
}
