package com.novis.flashcatalog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.ScaleGestureDetector
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
 * Съёмка напрямую через Camera2 с выбором объектива по номеру: «3» на многих Xiaomi — макро,
 * «2» — сверхширокий. Приложениям эти номера не показываются в списке, но характеристики
 * по ним читаются, поэтому пробуем открыть их напрямую.
 */
class MacroActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var overlay: FrameOverlay
    private lateinit var infoView: TextView
    private lateinit var btnShot: Button
    private lateinit var btnTorch: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnMore: Button
    private lateinit var btnMain: Button
    private lateinit var btnAutoCrop: Button
    private lateinit var panel: View
    private lateinit var zoomText: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private var zoom = 1f
    private var maxZoom = 4f
    private var activeArray: Rect? = null
    private var useZoomRatio = false
    private var autoCrop = true

    private val cm by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private var ids: List<String> = emptyList()
    private var pos = 0

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private val ui = Handler(Looper.getMainLooper())

    private var surfaceReady = false
    private var permOk = false
    private var opening = false
    private var torch = false
    private var sensorOrientation = 90
    private var shooting = false
    private var shotsLeft = 0
    private val raws = ArrayList<File>()

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
        btnMore = findViewById(R.id.btnMore)
        btnMain = findViewById(R.id.btnMain)
        btnAutoCrop = findViewById(R.id.btnAutoCrop)
        panel = findViewById(R.id.panel)
        zoomText = findViewById(R.id.zoomText)

        val prefs = getSharedPreferences("fc", MODE_PRIVATE)
        autoCrop = prefs.getBoolean("auto", true)
        zoom = prefs.getFloat("zoom_macro", 1f)
        zoomText.text = "%.1f×".format(zoom)
        btnAutoCrop.text = if (autoCrop) "Автокадр: вкл" else "Автокадр: выкл"

        ids = findIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, "Макро-объектив недоступен, включаю основную камеру", Toast.LENGTH_LONG).show()
            prefs.edit().putString("mode", "main").apply()
            setResult(Activity.RESULT_FIRST_USER)
            finish()
            return
        }
        pos = 0

        btnMore.setOnClickListener {
            val open = panel.visibility != View.VISIBLE
            panel.visibility = if (open) View.VISIBLE else View.GONE
            btnMore.text = if (open) "Меню ▴" else "Меню ▾"
        }
        btnMain.setOnClickListener {
            prefs.edit().putString("mode", "main").apply()
            setResult(Activity.RESULT_FIRST_USER)
            finish()
        }
        btnAutoCrop.setOnClickListener {
            autoCrop = !autoCrop
            prefs.edit().putBoolean("auto", autoCrop).apply()
            btnAutoCrop.text = if (autoCrop) "Автокадр: вкл" else "Автокадр: выкл"
        }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { setZoom(zoom + 0.5f) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { setZoom(zoom - 0.5f) }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                return true
            }
        })
        @Suppress("ClickableViewAccessibility")
        container.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            true
        }

        surface.holder.addCallback(this)
        btnShot.setOnClickListener { shoot() }
        btnTorch.setOnClickListener {
            torch = !torch
            btnTorch.text = if (torch) "Фонарик: вкл" else "Фонарик: выкл"
            updatePreview()
        }
        btnSwitch.setOnClickListener {
            pos = (pos + 1) % ids.size
            openCamera()
        }
        btnSwitch.text = "Объектив: " + ids[0]

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permOk = true
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun findIds(): List<String> {
        val candidates = LinkedHashSet<String>()
        candidates.addAll(listOf("3", "2"))
        try {
            candidates.addAll(cm.cameraIdList)
        } catch (e: Exception) {
            // ничего
        }
        val out = ArrayList<String>()
        for (id in candidates) {
            try {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) out.add(id)
            } catch (e: Exception) {
                // номер недоступен
            }
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        val t = HandlerThread("cam2")
        t.start()
        bgThread = t
        bg = Handler(t.looper)
        if (surfaceReady) openCamera()
    }

    override fun onPause() {
        super.onPause()
        closeCamera()
        bgThread?.quitSafely()
        bgThread = null
        bg = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        closeCamera()
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (e: Exception) {
            // ничего
        }
        session = null
        try {
            device?.close()
        } catch (e: Exception) {
            // ничего
        }
        device = null
        try {
            reader?.close()
        } catch (e: Exception) {
            // ничего
        }
        reader = null
        opening = false
    }

    private fun pick(sizes: Array<Size>?, maxW: Int, maxH: Int): Size {
        if (sizes == null || sizes.isEmpty()) throw IllegalStateException("нет доступных размеров")
        val inBounds = sizes.filter { it.width <= maxW && it.height <= maxH }
        val ratio = inBounds.filter { abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.02 }
        return ratio.maxByOrNull { it.width * it.height }
            ?: inBounds.maxByOrNull { it.width * it.height }
            ?: sizes.first()
    }

    private var previewAspect = 0f

    /** Подгоняет высоту видоискателя под пропорции кадра; если экран ещё не измерен — повторяет после измерения. */
    private fun applyAspect() {
        if (previewAspect <= 0f) return
        val parent = container.parent as? View ?: return
        val w = parent.width
        if (w <= 0) {
            parent.post { applyAspect() }
            return
        }
        val hh = (w * previewAspect).toInt()
        val lp = container.layoutParams
        if (lp.height != hh) {
            lp.height = hh
            container.layoutParams = lp
        }
    }

    private fun openCamera() {
        val handler = bg ?: return
        if (!permOk || !surfaceReady || ids.isEmpty() || opening) return
        closeCamera()
        val id = ids[pos]
        try {
            val chars = cm.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val zr = if (Build.VERSION.SDK_INT >= 30) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            useZoomRatio = zr != null
            maxZoom = if (zr != null) zr.upper else (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4f)
            zoom = zoom.coerceIn(1f, maxZoom)
            runOnUiThread {
                zoomText.text = "%.1f×".format(zoom)
                btnSwitch.text = "Объектив: " + id + " (" + (pos + 1) + "/" + ids.size + ")"
            }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("нет списка режимов")
            val previewSize = pick(map.getOutputSizes(SurfaceHolder::class.java), 1280, 960)
            val jpegSize = pick(map.getOutputSizes(ImageFormat.JPEG), 4200, 4200)

            surface.holder.setFixedSize(previewSize.width, previewSize.height)
            previewAspect = previewSize.width.toFloat() / previewSize.height
            runOnUiThread { applyAspect() }

            val r = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 3)
            r.setOnImageAvailableListener(imageListener, handler)
            reader = r

            opening = true
            infoView.text = "Открываю объектив $id…"
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    device = d
                    createSession(d, id, jpegSize)
                }

                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    d.close()
                    device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    opening = false
                    d.close()
                    device = null
                    val why = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "камера занята другим приложением"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "открыто слишком много камер"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "система запретила доступ к этому объективу"
                        else -> "ошибка $error"
                    }
                    runOnUiThread { showError(id, why) }
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            showError(id, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun showError(id: String, why: String) {
        infoView.text = "Объектив $id не открылся: $why. В меню можно выбрать другой объектив или основную камеру."
    }

    private fun createSession(d: CameraDevice, id: String, jpegSize: Size) {
        val r = reader ?: return
        val s = surface.holder.surface
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(s, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    session = sess
                    updatePreview()
                    runOnUiThread {
                        infoView.text = "Макро-линза. Держи около 4 см от флешки (фокус фиксированный). " +
                            "Снимок ${jpegSize.width}x${jpegSize.height}."
                    }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    runOnUiThread { showError(id, "не удалось запустить поток") }
                }
            }, bg)
        } catch (e: Exception) {
            runOnUiThread { showError(id, e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (zoom <= 1.01f) return
        if (useZoomRatio && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val a = activeArray ?: return
            val w = (a.width() / zoom).toInt()
            val h = (a.height() / zoom).toInt()
            val l = a.left + (a.width() - w) / 2
            val t = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }
    }

    private fun setZoom(v: Float) {
        zoom = v.coerceIn(1f, maxZoom)
        zoomText.text = "%.1f×".format(zoom)
        getSharedPreferences("fc", MODE_PRIVATE).edit().putFloat("zoom_macro", zoom).apply()
        updatePreview()
    }

    private fun updatePreview() {
        val d = device ?: return
        val sess = session ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface.holder.surface)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            applyZoom(b)
            b.set(
                CaptureRequest.FLASH_MODE,
                if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
            )
            sess.setRepeatingRequest(b.build(), null, bg)
        } catch (e: Exception) {
            // ничего
        }
    }

    // ---- серия из 3 кадров, остаётся самый резкий ----

    private val imageListener = ImageReader.OnImageAvailableListener { rd ->
        try {
            val img = rd.acquireNextImage() ?: return@OnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val f = File(cacheDir, "raw_${System.nanoTime()}.jpg")
                f.writeBytes(bytes)
                raws.add(f)
            } finally {
                img.close()
            }
        } catch (e: Exception) {
            // пропускаем кадр
        }
        shotsLeft--
        runOnUiThread { infoView.text = "Снимок ${raws.size} из 3…" }
        if (shotsLeft > 0) {
            ui.postDelayed({ captureNext() }, 350)
        } else {
            runOnUiThread { finishShots() }
        }
    }

    private fun shoot() {
        if (shooting || session == null) return
        shooting = true
        btnShot.isEnabled = false
        btnSwitch.visibility = View.INVISIBLE
        raws.clear()
        shotsLeft = 3
        captureNext()
    }

    private fun captureNext() {
        val d = device
        val sess = session
        val r = reader
        if (d == null || sess == null || r == null) {
            runOnUiThread { finishShots() }
            return
        }
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            applyZoom(b)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            b.set(
                CaptureRequest.FLASH_MODE,
                if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
            )
            sess.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    runOnUiThread { finishShots() }
                }

                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                }
            }, bg)
        } catch (e: Exception) {
            runOnUiThread { finishShots() }
        }
    }

    private fun finishShots() {
        if (!shooting) return
        shooting = false
        ui.removeCallbacksAndMessages(null)
        if (raws.isEmpty()) {
            Toast.makeText(this, "Снимок не получился", Toast.LENGTH_LONG).show()
            btnShot.isEnabled = true
            btnSwitch.visibility = View.VISIBLE
            return
        }
        infoView.text = "Ищу флешку и выбираю самый резкий кадр…"
        val files = ArrayList(raws)
        val fr = FrameOverlay.fractions(overlay.width.toFloat(), overlay.height.toFloat())
        val auto = autoCrop
        Thread {
            var best: Images.Shot? = null
            for (raw in files) {
                val shot = Images.processShot(raw, fr, cacheDir, auto)
                raw.delete()
                if (shot == null) continue
                val cur = best
                if (cur == null || shot.score > cur.score) {
                    cur?.file?.delete()
                    best = shot
                } else {
                    shot.file.delete()
                }
            }
            val b = best
            runOnUiThread {
                if (b == null) {
                    Toast.makeText(this, "Не удалось обработать снимок", Toast.LENGTH_LONG).show()
                    btnShot.isEnabled = true
                    btnSwitch.visibility = View.VISIBLE
                } else {
                    getSharedPreferences("fc", MODE_PRIVATE).edit().putString("mode", "macro").apply()
                    if (auto && !b.detected) Toast.makeText(this, "Не вижу границ флешки — кадр обрезан по рамке. Добавь света или положи её на контрастный фон (светлую — на тёмный, тёмную — на светлый)", Toast.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK, Intent().putExtra(EditActivity.EXTRA_PHOTO, b.file.absolutePath))
                    finish()
                }
            }
        }.start()
    }
}
