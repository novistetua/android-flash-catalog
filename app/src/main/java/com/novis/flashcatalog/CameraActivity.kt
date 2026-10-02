package com.novis.flashcatalog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
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
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN = "return_result"
        private const val AUTO_BURST = 3
        private const val MANUAL_BURST = 5
        private val OFFSETS = floatArrayOf(0f, -0.3f, 0.3f, -0.6f, 0.6f)
        private const val HINT_BASE = "Положи флешку в рамку. Тап по экрану — фокус, щипок — зум."
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameOverlay
    private lateinit var btnShot: Button
    private lateinit var btnTorch: Button
    private lateinit var btnMacro: Button
    private lateinit var btnAuto: Button
    private lateinit var btnSweep: Button
    private lateinit var focusSeek: SeekBar
    private lateinit var hintView: TextView
    private lateinit var sharpView: TextView
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var macroOn = false

    // ручной фокус
    private var minFocusD = 0f      // диоптрии: чем больше, тем ближе можно сфокусироваться
    private var manualOk = false
    private var manualFocus = false
    private var focusD = 0f

    // автоподбор фокуса (перебор + замер резкости)
    @Volatile private var sweeping = false
    private var sweepPlan: List<Float> = emptyList()
    private var sweepIdx = 0
    private var sweepPhase = 0
    private var sweepLo = 0f
    private var coarseContrast = 0.0
    private val sweepResults = ArrayList<Pair<Float, Double>>()
    private var sweepStepStart = 0L
    private var sweepAcc = 0.0
    private var sweepN = 0

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

    // внешняя (полная) камера телефона
    private var awaitingExternal = false
    private var externalSince = 0L
    private var permAsked = false

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

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        launchFullCamera()
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyAndCrop(uri)
    }

    private val macroLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(EditActivity.EXTRA_PHOTO)
        if (r.resultCode == Activity.RESULT_OK && p != null) finishWith(File(p))
    }

    private val cropper = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(CropActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && p != null) finishWith(File(p))
    }

    // ---------------- внешняя камера / галерея ----------------

    private fun mediaPermName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermName()) == PackageManager.PERMISSION_GRANTED

    private fun onFullCameraClick() {
        if (hasMediaPermission() || permAsked) {
            launchFullCamera()
        } else {
            permAsked = true
            mediaPermission.launch(mediaPermName())
        }
    }

    private fun launchFullCamera() {
        try {
            externalSince = System.currentTimeMillis() / 1000
            awaitingExternal = true
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: Exception) {
            awaitingExternal = false
            Toast.makeText(this, "Не удалось открыть камеру телефона: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun latestPhotoSince(sec: Long): Uri? {
        return try {
            val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            contentResolver.query(
                base,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf((sec - 2).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(base, c.getLong(0)) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun importLatest() {
        if (!hasMediaPermission()) {
            gallery.launch("image/*")
            return
        }
        Thread {
            val uri = latestPhotoSince(externalSince)
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, "Новое фото не нашлось, выбери его вручную", Toast.LENGTH_LONG).show()
                    gallery.launch("image/*")
                } else {
                    copyAndCrop(uri)
                }
            }
        }.start()
    }

    private fun copyAndCrop(uri: Uri) {
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

    private fun openCrop(f: File) {
        cropper.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, f.absolutePath))
    }

    // ---------------- диагностика камер ----------------

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
        try {
            val cm2 = getSystemService(CAMERA_SERVICE) as CameraManager
            sb.append("\nПроба Camera2 по номерам: ")
            for (id in listOf("2", "3", "4", "5")) {
                val ok = try {
                    cm2.getCameraCharacteristics(id)
                    "доступна"
                } catch (e: Exception) {
                    "нет (" + e.javaClass.simpleName + ")"
                }
                sb.append(id).append("=").append(ok).append("; ")
            }
        } catch (e: Exception) {
            sb.append("\nПроба Camera2 не удалась")
        }
        try {
            @Suppress("DEPRECATION")
            val n = android.hardware.Camera.getNumberOfCameras()
            sb.append("\nСтарый API (API1) видит камер: ").append(n).append(" -> ")
            for (i in 0 until n) {
                @Suppress("DEPRECATION")
                val ci = android.hardware.Camera.CameraInfo()
                @Suppress("DEPRECATION")
                android.hardware.Camera.getCameraInfo(i, ci)
                sb.append("#").append(i).append(if (ci.facing == 0) " тыл" else " фронт").append("; ")
            }
        } catch (e: Exception) {
            sb.append("\nСтарый API: ошибка ").append(e.message)
        }
        val infos = provider?.availableCameraInfos
        sb.append("\n\nCameraX видит: ")
        sb.append(infos?.joinToString { Camera2CameraInfo.from(it).cameraId } ?: "ещё не запущен")
        sb.append("\nAndroid ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        AlertDialog.Builder(this)
            .setTitle("Камеры устройства")
            .setMessage(sb.toString())
            .setPositiveButton("Закрыть", null)
            .show()
    }

    // ---------------- жизненный цикл ----------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        btnShot = findViewById(R.id.btnShot)
        btnTorch = findViewById(R.id.btnTorch)
        btnMacro = findViewById(R.id.btnMacro)
        btnAuto = findViewById(R.id.btnAuto)
        btnSweep = findViewById(R.id.btnSweep)
        focusSeek = findViewById(R.id.focusSeek)
        hintView = findViewById(R.id.hint)
        sharpView = findViewById(R.id.sharp)

        btnShot.setOnClickListener { takePhoto() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnMacro.setOnClickListener { toggleMacro() }
        btnAuto.setOnClickListener { setAutoFocus() }
        btnSweep.setOnClickListener { startSweep() }
        findViewById<Button>(R.id.btnSystem).setOnClickListener { onFullCameraClick() }
        findViewById<Button>(R.id.btnMacroLens).setOnClickListener {
            macroLauncher.launch(Intent(this, MacroActivity::class.java))
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { gallery.launch("image/*") }
        findViewById<Button>(R.id.btnInfo).setOnClickListener { showCameraReport() }

        focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || !manualOk || sweeping) return
                manualFocus = true
                focusD = minFocusD * progress / 100f
                applyManualFocus(focusD)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

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
        if (awaitingExternal) {
            awaitingExternal = false
            handler.postDelayed({ importLatest() }, 700)
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(gyroListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
    }

    // ---------------- камера ----------------

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
                    val sw = sweeping
                    if (sw || (now - lastUi >= 200 && !busy)) {
                        lastUi = now
                        val v = Images.lumaSharpness(image)
                        if (sw) {
                            runOnUiThread { onSweepSample(v) }
                        } else {
                            peak = max(peak * 0.9997, v)
                            val ratio = if (peak > 0) (v / peak).coerceIn(0.0, 1.0) else 0.0
                            runOnUiThread { showSharpness(ratio) }
                        }
                    }
                } finally {
                    image.close()
                }
            }

            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addUseCase(analysis)
            val vp = previewView.viewPort
            if (vp != null) group.setViewPort(vp)

            manualFocus = false
            sweeping = false
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(this, selector, group.build())
                camera = cam
                if (torchOn && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, "Камера не запустилась: ${e.message}", Toast.LENGTH_LONG).show()
            }

            val minFocus = chars(info, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            minFocusD = if (minFocus != null && minFocus > 0f) minFocus else 0f
            val afModes = chars(info, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            manualOk = minFocusD > 0f && afModes != null && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)
            focusSeek.isEnabled = manualOk
            btnSweep.isEnabled = manualOk
            btnAuto.isEnabled = manualOk
            focusSeek.progress = 0

            hintView.text = if (minFocusD > 0f) {
                HINT_BASE + "\nБлиже ~" + (100f / minFocusD).toInt() + " см эта камера не сфокусируется."
            } else {
                HINT_BASE
            }
        }
    }

    private fun focusText(): String {
        if (!manualFocus) return "Фокус: авто"
        val cm = if (focusD < 0.05f) "∞" else "~" + (100f / focusD).toInt() + " см"
        return "Фокус: ручной $cm"
    }

    private fun showSharpness(ratio: Double) {
        if (busy || sweeping) return
        val blocks = (ratio * 10).toInt().coerceIn(0, 10)
        val bar = "█".repeat(blocks) + "░".repeat(10 - blocks)
        sharpView.text = "Резкость $bar ${(ratio * 100).toInt()}%\n" + focusText()
        sharpView.setTextColor(if (ratio >= 0.9) Color.GREEN else Color.WHITE)
    }

    // ---------------- фокус ----------------

    private fun applyManualFocus(d: Float) {
        val cam = camera ?: return
        val v = d.coerceIn(0f, minFocusD)
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, v)
                    .build()
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Ручной фокус не поддерживается: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setAutoFocus() {
        if (sweeping) return
        manualFocus = false
        val cam = camera ?: return
        try {
            Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        } catch (e: Exception) {
            // ничего
        }
        focusSeek.progress = 0
    }

    private fun focusAt(x: Float, y: Float) {
        if (manualFocus || sweeping) return
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(8, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        overlay.showFocus(x, y)
    }

    private fun startSweep() {
        if (!manualOk) {
            Toast.makeText(this, "Эта камера не даёт ручной фокус", Toast.LENGTH_SHORT).show()
            return
        }
        if (sweeping || busy) return
        manualFocus = true
        sweepLo = minOf(2f, minFocusD * 0.25f)
        sweepPlan = (0 until 12).map { sweepLo + (minFocusD - sweepLo) * it / 11f }
        sweepPhase = 0
        sweepIdx = 0
        sweepResults.clear()
        btnShot.isEnabled = false
        sweeping = true
        applySweepStep()
    }

    private fun applySweepStep() {
        applyManualFocus(sweepPlan[sweepIdx])
        sweepStepStart = SystemClock.elapsedRealtime()
        sweepAcc = 0.0
        sweepN = 0
        sharpView.setTextColor(Color.WHITE)
        sharpView.text = "Подбираю фокус… держи телефон над флешкой ${sweepIdx + 1}/${sweepPlan.size}"
    }

    private fun onSweepSample(v: Double) {
        if (!sweeping) return
        if (SystemClock.elapsedRealtime() - sweepStepStart < 250) return
        sweepAcc += v
        sweepN++
        if (sweepN < 3) return
        sweepResults.add(Pair(sweepPlan[sweepIdx], sweepAcc / sweepN))
        sweepIdx++
        if (sweepIdx < sweepPlan.size) {
            applySweepStep()
            return
        }
        val best = sweepResults.maxByOrNull { it.second } ?: run {
            sweeping = false
            btnShot.isEnabled = true
            return
        }
        if (sweepPhase == 0) {
            val sorted = sweepResults.map { it.second }.sorted()
            val median = sorted[sorted.size / 2]
            coarseContrast = if (median > 0) best.second / median else 0.0
            val step = (minFocusD - sweepLo) / 11f
            val a = (best.first - step).coerceAtLeast(0f)
            val b = (best.first + step).coerceAtMost(minFocusD)
            sweepPlan = (0 until 8).map { a + (b - a) * it / 7f }
            sweepPhase = 1
            sweepIdx = 0
            sweepResults.clear()
            applySweepStep()
        } else {
            sweeping = false
            focusD = best.first
            applyManualFocus(focusD)
            focusSeek.progress = if (minFocusD > 0f) (focusD / minFocusD * 100f).toInt() else 0
            btnShot.isEnabled = true
            peak = 1.0
            val cm = if (focusD < 0.05f) "∞" else "~" + (100f / focusD).toInt() + " см"
            val atEdge = focusD >= minFocusD * 0.97f
            if (atEdge || coarseContrast < 1.5) {
                Toast.makeText(
                    this,
                    "Чёткого положения не нашлось. Скорее всего, телефон ближе " +
                        (100f / minFocusD).toInt() + " см к флешке: объектив там не фокусируется. " +
                        "Отодвинь на 12–20 см и повтори, либо жми «Макро-линза»",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Фокус подобран: $cm", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- фонарик, макро ----------------

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
                    "Система не отдаёт приложениям макро-камеру. Используй «Камера телефона (макро)»",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        peak = 1.0
        bind()
    }

    // ---------------- съёмка серии ----------------

    private fun takePhoto() {
        if (busy || sweeping || imageCapture == null) return
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
        val total = if (manualFocus) MANUAL_BURST else AUTO_BURST
        shootBurst(total, 0, mutableListOf())
    }

    private fun shootBurst(total: Int, idx: Int, raws: MutableList<File>) {
        if (idx >= total || imageCapture == null) {
            processBurst(raws)
            return
        }
        if (manualFocus) {
            // ручной фокус: слегка «качаем» фокус вокруг выбранного, потом оставим самый резкий кадр
            applyManualFocus(focusD + OFFSETS[idx])
            handler.postDelayed({ takeOne(total, idx, raws) }, 280)
        } else {
            takeOne(total, idx, raws)
        }
    }

    private fun takeOne(total: Int, idx: Int, raws: MutableList<File>) {
        val capture = imageCapture
        if (capture == null) {
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
                    sharpView.text = "Снимок ${raws.size} из $total…"
                    shootBurst(total, idx + 1, raws)
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
