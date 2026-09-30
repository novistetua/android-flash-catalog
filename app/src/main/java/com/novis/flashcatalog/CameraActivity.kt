package com.novis.flashcatalog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.TimeUnit

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN = "return_result"
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameOverlay
    private lateinit var btnShot: Button
    private lateinit var btnTorch: Button
    private lateinit var btnMacro: Button
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var macroOn = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            startCamera()
        } else {
            Toast.makeText(this, "Без доступа к камере фото не сделать", Toast.LENGTH_LONG).show()
            finish()
        }
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

        btnShot.setOnClickListener { takePhoto() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnMacro.setOnClickListener { toggleMacro() }

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

    private fun bind() {
        val p = provider ?: return
        previewView.post {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = capture
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
            val vp = previewView.viewPort
            if (vp != null) group.setViewPort(vp)
            val selector = if (macroOn) macroSelector() else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(this, selector, group.build())
                camera = cam
                if (torchOn && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, "Камера не запустилась: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
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
                    "Отдельной макро-камеры система не показала. Отодвинь телефон и используй зум",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        bind()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        btnShot.isEnabled = false
        val raw = File(cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val fr = FrameOverlay.fractions(overlay.width.toFloat(), overlay.height.toFloat())
                    Thread {
                        val out = Images.cropToFraction(raw, fr, cacheDir)
                        raw.delete()
                        runOnUiThread { finishWith(out) }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@CameraActivity, "Ошибка съёмки: ${exception.message}", Toast.LENGTH_LONG).show()
                    btnShot.isEnabled = true
                }
            }
        )
    }

    private fun finishWith(out: File?) {
        if (out == null) {
            Toast.makeText(this, "Не удалось обработать снимок", Toast.LENGTH_LONG).show()
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
