package com.novis.flashcatalog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import androidx.camera.core.FocusMeteringAction
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** Сканер QR: камера + ZXing, без сервисов Google. */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val RESULT_TEXT = "text"
    }

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var finished = false
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "Нужен доступ к камере, чтобы сканировать QR", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            perm.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { image ->
                    try {
                        if (!finished) {
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val data = ByteArray(buf.remaining())
                            buf.get(data)
                            val src = PlanarYUVLuminanceSource(
                                data, plane.rowStride, image.height, 0, 0, image.width, image.height, false
                            )
                            val text = try {
                                reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
                            } catch (e: Exception) {
                                null
                            } finally {
                                reader.reset()
                            }
                            val ours = text?.let { Pack.unwrapQr(it) }
                            if (text != null && ours == null) {
                                runOnUiThread { findViewById<android.widget.TextView>(R.id.scanHint).text = "Вижу QR, но он не от Flash Catalog" }
                            }
                            if (ours != null) {
                                finished = true
                                runOnUiThread {
                                    buzz()
                                    findViewById<android.widget.TextView>(R.id.scanHint).text = "QR считан"
                                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_TEXT, ours))
                                    finish()
                                }
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                val pv = findViewById<PreviewView>(R.id.previewView)
                pv.setOnTouchListener { v, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) {
                        val pt = pv.meteringPointFactory.createPoint(ev.x, ev.y)
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                        v.performClick()
                    }
                    true
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Камера не запустилась: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Тройная вибрация: QR считан. */
    private fun buzz() {
        try {
            val pattern = longArrayOf(0, 90, 90, 90, 90, 90)
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            }
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        } catch (e: Exception) { /* без вибрации */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
    }
}
