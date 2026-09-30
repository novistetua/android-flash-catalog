package com.novis.flashcatalog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN = "return_result"
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FrameOverlay
    private lateinit var btnShot: Button
    private var imageCapture: ImageCapture? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            startCamera()
        } else {
            Toast.makeText(this, "Без доступа к камере фото не сделать", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        btnShot = findViewById(R.id.btnShot)
        btnShot.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
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
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                } catch (e: Exception) {
                    Toast.makeText(this, "Камера не запустилась: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
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
