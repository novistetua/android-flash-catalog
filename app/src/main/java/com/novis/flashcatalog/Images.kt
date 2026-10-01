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
}
