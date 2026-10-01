package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var cropView: CropView
    private lateinit var path: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        cropView = findViewById(R.id.cropView)
        val p = intent.getStringExtra(EXTRA_PATH)
        if (p == null) {
            finish()
            return
        }
        path = p

        Thread {
            val bmp = Images.loadUpright(path, 1800)
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    cropView.setBitmap(bmp)
                }
            }
        }.start()

        findViewById<Button>(R.id.btnOk).setOnClickListener { done(cropView.fractions()) }
        findViewById<Button>(R.id.btnFull).setOnClickListener { done(RectF(0f, 0f, 1f, 1f)) }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            File(path).delete()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun done(f: RectF) {
        findViewById<Button>(R.id.btnOk).isEnabled = false
        findViewById<Button>(R.id.btnFull).isEnabled = false
        Thread {
            val out = Images.cropToFraction(File(path), f, cacheDir)
            File(path).delete()
            runOnUiThread {
                if (out == null) {
                    Toast.makeText(this, "Не удалось обработать фото", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PATH, out.absolutePath))
                    finish()
                }
            }
        }.start()
    }
}
