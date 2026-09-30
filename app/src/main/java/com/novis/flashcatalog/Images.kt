package com.novis.flashcatalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
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
}
