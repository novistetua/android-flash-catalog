package com.novis.flashcatalog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN = "return_result"
        private const val BURST = 3
        private const val HINT_BASE = "Положи флешку в рамку. Тап по экрану — фокус, щипок — зум."
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameOverlay
    private lateinit var btnShot: Button
    private lateinit var btnTorch: Button
    private lateinit var btnMacro: Button
    private lateinit var hintView: TextView
    private lateinit var sharpView: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var macroOn = false

    // живой индикатор резкости
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var peak = 1.0
    private var lastUi = 0L

    // съёмка серии с ожиданием неподвижности
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var waiting = false
    private var steadySince = 0L
    private var gyro: Sensor? = null
    private val timeoutRunnable = Runnable { fireBurst() }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!waiting) return
            val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            val now = SystemClock.elapsedRealtime()
            if (mag < 0.07f) {
                if (steadySince == 0L) steadySince = now
                if (now - steadySince >= 250) fireBurst()
            } else {
                steadySince = 0L
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            startCamera()
        } else {
            Toast.makeText(this, "Без доступа к камере фото не сделать", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private var pendingSrc: File? = null

    private val systemCam = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingSrc
        if (ok && f != null) openCrop(f) else f?.delete()
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Thread {
                val f = File(cacheDir, "imp_${System.nanoTime()}.jpg")
                try {
                    contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    runOnUiThread { openCrop(f) }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Не удалось открыть файл: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private val cropper = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(CropActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && p != null) finishWith(File(p))
    }

    private fun openCrop(f: File) {
        cropper.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun launchSystemCamera() {
        try {
            val f = File(cacheDir, "sys_${System.nanoTime()}.jpg")
            pendingSrc = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            systemCam.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Системная камера не запустилась: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun describeCamera(cm: CameraManager, id: String): String {
        return try {
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "фронт"
                CameraCharacteristics.LENS_FACING_BACK -> "тыл"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "внешняя"
                else -> "?"
            }
            val minF = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val minTxt = if (minF != null && minF > 0f) "${(100f / minF).toInt()} см" else "фикс/нет данных"
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "%.1f".format(it) } ?: "?"
            val pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString() ?: "?"
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val logical = caps != null && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val phys = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.joinToString() else "-"
            "id $id: $facing, мин. фокус $minTxt, f=$focal, сенсор $pix, логическая=${if (logical) "да" else "нет"}, физические=[$phys]\n"
        } catch (e: Exception) {
            "id $id: недоступна (${e.javaClass.simpleName})\n"
        }
    }

    private fun showCameraReport() {
        val sb = StringBuilder()
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            sb.append("Система показывает приложениям камеры: ").append(cm.cameraIdList.joinToString()).append("\n\n")
            val phys = LinkedHashSet<String>()
            for (id in cm.cameraIdList) {
                sb.append(describeCamera(cm, id))
                if (Build.VERSION.SDK_INT >= 28) phys.addAll(cm.getCameraCharacteristics(id).physicalCameraIds)
            }
            for (id in phys) sb.append("(физическая) ").append(describeCamera(cm, id))
        } catch (e: Exception) {
            sb.append("Ошибка: ").append(e.message).append("\n")
        }
        val infos = provider?.availableCameraInfos
        sb.append("\nCameraX видит: ")
        sb.append(infos?.joinToString { Camera2CameraInfo.from(it).cameraId } ?: "ещё не запущен")
        sb.append("\nAndroid ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        AlertDialog.Builder(this)
            .setTitle("Камеры устройства")
            .setMessage(sb.toString())
            .setPositiveButton("Закрыть", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        btnShot = findViewById(R.id.btnShot)
        btnTorch = findViewById(R.id.btnTorch)
        btnMacro = findViewById(R.id.btnMacro)
        hintView = findViewById(R.id.hint)
        sharpView = findViewById(R.id.sharp)

        btnShot.setOnClickListener { takePhoto() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnMacro.setOnClickListener { toggleMacro() }
        findViewById<Button>(R.id.btnSystem).setOnClickListener { launchSystemCamera() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { gallery.launch("image/*") }
        findViewById<Button>(R.id.btnInfo).setOnClickListener { showCameraReport() }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                val target = (z.zoomRatio * detector.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(ev)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyro?.let { sm.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(gyroListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeoutRunnable)
        analysisExecutor.shutdown()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun macroSelector(): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { infos: List<CameraInfo> ->
                // берём камеру, которая умеет фокусироваться ближе всех
                val best = infos.maxByOrNull {
                    Camera2CameraInfo.from(it)
                        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                }
                if (best != null) listOf(best) else infos
            })
            .build()
    }

    private fun <T> chars(info: CameraInfo?, key: CameraCharacteristics.Key<T>): T? {
        return try {
            if (info == null) null else Camera2CameraInfo.from(info).getCameraCharacteristic(key)
        } catch (e: Exception) {
            null
        }
    }

    private fun bind() {
        val p = provider ?: return
        previewView.post {
            val selector = if (macroOn) macroSelector() else CameraSelector.DEFAULT_BACK_CAMERA
            val info: CameraInfo? = try {
                selector.filter(p.availableCameraInfos).firstOrNull()
            } catch (e: Exception) {
                null
            }

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capBuilder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Улучшаем чёткость, если камера это поддерживает (универсально, по характеристикам)
            try {
                val ext = Camera2Interop.Extender(capBuilder)
                val ois = chars(info, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                if (ois != null && ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                    ext.setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }
                val edge = chars(info, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                if (edge != null && edge.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                    ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                }
                val nr = chars(info, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                if (nr != null && nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                    ext.setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    )
                }
            } catch (e: Exception) {
                // без интеропа тоже работаем
            }
            val capture = capBuilder.build()
            imageCapture = capture

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi >= 200 && !busy) {
                        lastUi = now
                        val v = Images.lumaSharpness(image)
                        peak = max(peak * 0.9997, v)
                        val ratio = if (peak > 0) (v / peak).coerceIn(0.0, 1.0) else 0.0
                        runOnUiThread { showSharpness(ratio) }
                    }
                } finally {
                    image.close()
                }
            }

            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addUseCase(analysis)
            val vp = previewView.viewPort
            if (vp != null) group.setViewPort(vp)

            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(this, selector, group.build())
                camera = cam
                if (torchOn && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, "Камера не запустилась: ${e.message}", Toast.LENGTH_LONG).show()
            }

            val minFocus = chars(info, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            hintView.text = if (minFocus != null && minFocus > 0f) {
                HINT_BASE + "\nБлиже ~" + (100f / minFocus).toInt() + " см эта камера не сфокусируется."
            } else {
                HINT_BASE
            }
        }
    }

    private fun showSharpness(ratio: Double) {
        if (busy) return
        val blocks = (ratio * 10).toInt().coerceIn(0, 10)
        val bar = "█".repeat(blocks) + "░".repeat(10 - blocks)
        sharpView.text = "Резкость $bar ${(ratio * 100).toInt()}%"
        sharpView.setTextColor(if (ratio >= 0.9) Color.GREEN else Color.WHITE)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(8, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        overlay.showFocus(x, y)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "У этой камеры нет фонарика", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnTorch.text = if (torchOn) "Фонарик: вкл" else "Фонарик: выкл"
    }

    private fun toggleMacro() {
        macroOn = !macroOn
        btnMacro.text = if (macroOn) "Макро: вкл" else "Макро: выкл"
        if (macroOn) {
            val backCount = provider?.availableCameraInfos?.count {
                it.lensFacing == CameraSelector.LENS_FACING_BACK
            } ?: 0
            if (backCount <= 1) {
                Toast.makeText(
                    this,
                    "Система не отдаёт приложениям макро-камеру. Нажми «Системная камера»: там макро работает, потом обрежь фото рамкой",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        peak = 1.0
        bind()
    }

    // ---- съёмка: ждём неподвижности, делаем серию, оставляем самый резкий кадр ----

    private fun takePhoto() {
        if (busy || imageCapture == null) return
        busy = true
        btnShot.isEnabled = false
        sharpView.setTextColor(Color.WHITE)
        sharpView.text = "Держи неподвижно…"
        steadySince = 0L
        waiting = true
        handler.postDelayed(timeoutRunnable, if (gyro == null) 300L else 2000L)
    }

    private fun fireBurst() {
        if (!waiting) return
        waiting = false
        handler.removeCallbacks(timeoutRunnable)
        shootBurst(BURST, mutableListOf())
    }

    private fun shootBurst(left: Int, raws: MutableList<File>) {
        val capture = imageCapture
        if (left == 0 || capture == null) {
            processBurst(raws)
            return
        }
        val raw = File(cacheDir, "raw_${System.nanoTime()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    raws.add(raw)
                    sharpView.text = "Снимок ${raws.size} из $BURST…"
                    shootBurst(left - 1, raws)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (raws.isEmpty()) {
                        Toast.makeText(this@CameraActivity, "Ошибка съёмки: ${exception.message}", Toast.LENGTH_LONG).show()
                        busy = false
                        btnShot.isEnabled = true
                    } else {
                        processBurst(raws)
                    }
                }
            }
        )
    }

    private fun processBurst(raws: List<File>) {
        sharpView.text = "Выбираю самый резкий кадр…"
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
            runOnUiThread { finishWith(best) }
        }.start()
    }

    private fun finishWith(out: File?) {
        if (out == null) {
            Toast.makeText(this, "Не удалось обработать снимок", Toast.LENGTH_LONG).show()
            busy = false
            btnShot.isEnabled = true
            return
        }
        if (intent.getBooleanExtra(EXTRA_RETURN, false)) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EditActivity.EXTRA_PHOTO, out.absolutePath))
        } else {
            startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_PHOTO, out.absolutePath))
        }
        finish()
    }
}
