package com.novis.flashcatalog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

/**
 * Съёмка через старый Camera API (API1). На ряде телефонов (в т.ч. Xiaomi) именно он
 * показывает приложениям дополнительные объективы, например макро.
 */
@Suppress("DEPRECATION")
class MacroActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var overlay: FrameOverlay
    private lateinit var infoView: TextView
    private lateinit var btnShot: Button
    private lateinit var btnTorch: Button
    private lateinit var btnSwitch: Button

    private var cam: Camera? = null
    private var backIds: List<Int> = emptyList()
    private var pos = 0
    private var torch = false
    private var surfaceReady = false
    private var permOk = false
    private var shooting = false
    private val handler = Handler(Looper.getMainLooper())

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            permOk = true
            openCamera()
        } else {
            Toast.makeText(this, "Без доступа к камере фото не сделать", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_macro)
        container = findViewById(R.id.container)
        surface = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        infoView = findViewById(R.id.info)
        btnShot = findViewById(R.id.btnShot)
        btnTorch = findViewById(R.id.btnTorch)
        btnSwitch = findViewById(R.id.btnSwitch)

        val ids = ArrayList<Int>()
        val ci = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, ci)
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_BACK) ids.add(i)
        }
        backIds = ids
        if (ids.isEmpty()) {
            Toast.makeText(this, "Старый API не показывает ни одной задней камеры", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // дополнительные объективы обычно идут последними
        pos = ids.size - 1

        surface.holder.addCallback(this)
        btnShot.setOnClickListener { shoot() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnSwitch.setOnClickListener {
            pos = (pos + 1) % backIds.size
            openCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permOk = true
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseCamera()
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady) openCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        releaseCamera()
    }

    private fun releaseCamera() {
        try {
            cam?.stopPreview()
        } catch (e: Exception) {
            // ничего
        }
        try {
            cam?.release()
        } catch (e: Exception) {
            // ничего
        }
        cam = null
    }

    private fun pickSize(sizes: List<Camera.Size>, maxW: Int, maxH: Int): Camera.Size? {
        val ratio = 4.0 / 3.0
        val good = sizes.filter { abs(it.width.toDouble() / it.height - ratio) < 0.02 && it.width <= maxW && it.height <= maxH }
        return good.maxByOrNull { it.width * it.height }
    }

    private fun openCamera() {
        if (!permOk || !surfaceReady || backIds.isEmpty()) return
        releaseCamera()
        val id = backIds[pos]
        try {
            val c = Camera.open(id)
            val ci = Camera.CameraInfo()
            Camera.getCameraInfo(id, ci)
            val p = c.parameters

            val pic = pickSize(p.supportedPictureSizes, 5000, 5000) ?: p.supportedPictureSizes.maxByOrNull { it.width * it.height }
            if (pic != null) p.setPictureSize(pic.width, pic.height)
            val prev = pickSize(p.supportedPreviewSizes, 1280, 960) ?: p.supportedPreviewSizes.first()
            p.setPreviewSize(prev.width, prev.height)

            val modes = p.supportedFocusModes ?: emptyList<String>()
            val focus = when {
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) -> Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                modes.contains(Camera.Parameters.FOCUS_MODE_AUTO) -> Camera.Parameters.FOCUS_MODE_AUTO
                else -> null
            }
            if (focus != null) p.focusMode = focus
            p.setRotation(ci.orientation)
            val flashModes = p.supportedFlashModes ?: emptyList<String>()
            if (flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                p.flashMode = if (torch) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            }
            c.parameters = p
            c.setDisplayOrientation(ci.orientation % 360)
            c.setPreviewDisplay(surface.holder)

            // рамка предпросмотра в портретной ориентации: ширина / высота = prev.height / prev.width
            val w = container.width
            if (w > 0) {
                val lp = container.layoutParams
                lp.height = (w.toFloat() * prev.width / prev.height).toInt()
                container.layoutParams = lp
            }
            c.startPreview()
            cam = c

            infoView.text = "Объектив #$id (${pos + 1} из ${backIds.size}), фото ${pic?.width}x${pic?.height}, " +
                "фокус: " + (focus ?: "фиксированный") + "\nКнопка «Объектив» переключает камеры. " +
                "У макро-объектива фокус обычно фиксированный, около 4 см."
        } catch (e: Exception) {
            cam = null
            Toast.makeText(this, "Объектив #$id не открылся: ${e.message}", Toast.LENGTH_LONG).show()
            infoView.text = "Объектив #$id не открылся. Нажми «Объектив», чтобы попробовать другой."
        }
    }

    private fun toggleTorch() {
        val c = cam ?: return
        try {
            val p = c.parameters
            val flashModes = p.supportedFlashModes ?: emptyList<String>()
            if (!flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                Toast.makeText(this, "У этого объектива нет фонарика", Toast.LENGTH_SHORT).show()
                return
            }
            torch = !torch
            p.flashMode = if (torch) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
            btnTorch.text = if (torch) "Фонарик: вкл" else "Фонарик: выкл"
        } catch (e: Exception) {
            Toast.makeText(this, "Фонарик не переключился: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- серия из 3 кадров, остаётся самый резкий ----

    private fun shoot() {
        if (shooting || cam == null) return
        shooting = true
        btnShot.isEnabled = false
        btnSwitch.visibility = View.INVISIBLE
        takeOne(3, mutableListOf())
    }

    private fun takeOne(left: Int, raws: MutableList<File>) {
        val c = cam
        if (c == null || left == 0) {
            process(raws)
            return
        }
        try {
            c.takePicture(null, null, Camera.PictureCallback { data, camera ->
                val f = File(cacheDir, "raw_${System.nanoTime()}.jpg")
                try {
                    f.writeBytes(data)
                    raws.add(f)
                } catch (e: Exception) {
                    // пропускаем кадр
                }
                try {
                    camera.startPreview()
                } catch (e: Exception) {
                    // ничего
                }
                infoView.text = "Снимок ${raws.size} из 3…"
                handler.postDelayed({ takeOne(left - 1, raws) }, 350)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка съёмки: ${e.message}", Toast.LENGTH_LONG).show()
            process(raws)
        }
    }

    private fun process(raws: List<File>) {
        if (raws.isEmpty()) {
            shooting = false
            btnShot.isEnabled = true
            btnSwitch.visibility = View.VISIBLE
            return
        }
        infoView.text = "Выбираю самый резкий кадр…"
        val fr = FrameOverlay.fractions(overlay.width.toFloat(), overlay.height.toFloat())
        Thread {
            var best: File? = null
            var bestScore = -1.0
            for (raw in raws) {
                val out = Images.cropToFraction(raw, fr, cacheDir)
                raw.delete()
                if (out == null) continue
                val score = Images.sharpness(out)
                if (score > bestScore) {
                    best?.delete()
                    best = out
                    bestScore = score
                } else {
                    out.delete()
                }
            }
            runOnUiThread {
                if (best == null) {
                    Toast.makeText(this, "Не удалось обработать снимок", Toast.LENGTH_LONG).show()
                    shooting = false
                    btnShot.isEnabled = true
                    btnSwitch.visibility = View.VISIBLE
                } else {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EditActivity.EXTRA_PHOTO, best!!.absolutePath))
                    finish()
                }
            }
        }.start()
    }
}
