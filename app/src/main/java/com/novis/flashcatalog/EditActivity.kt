package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO = "photo_path"
        const val EXTRA_FOLDER = "folder"
        private const val SHARPEN_AMOUNT = 1.0f
    }

    private lateinit var photoView: ImageView
    private lateinit var nameEt: EditText
    private lateinit var noteEt: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var btnSharpen: Button
    private lateinit var btnCutout: Button
    private var cutBitmap: Bitmap? = null
    private var cutOn = false
    private var originalName: String? = null
    private var pendingPhoto: String? = null
    private var existingPhoto: Uri? = null
    private var baseBitmap: Bitmap? = null
    private var sharpBitmap: Bitmap? = null
    private var sharpenOn = false
    private var rotation = 0

    private val retake = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(EXTRA_PHOTO)
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            pendingPhoto?.let { File(it).delete() }
            pendingPhoto = p
            showFile(p)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        photoView = findViewById(R.id.photo)
        nameEt = findViewById(R.id.nameEt)
        noteEt = findViewById(R.id.noteEt)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)
        btnSharpen = findViewById(R.id.btnSharpen)
        btnCutout = findViewById(R.id.btnCutout)
        btnCutout.setOnClickListener { toggleCutout() }
        val title = findViewById<TextView>(R.id.title)

        originalName = intent.getStringExtra(EXTRA_FOLDER)
        pendingPhoto = intent.getStringExtra(EXTRA_PHOTO)

        findViewById<Button>(R.id.btnRetake).setOnClickListener {
            retake.launch(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_RETURN, true))
        }
        findViewById<Button>(R.id.btnRotL).setOnClickListener { rotate(-90) }
        findViewById<Button>(R.id.btnRotR).setOnClickListener { rotate(90) }
        btnSharpen.setOnClickListener { toggleSharpen() }
        btnSave.setOnClickListener { save() }
        btnDelete.setOnClickListener { confirmDelete() }

        val orig = originalName
        if (orig == null) {
            title.text = "Новая флешка"
            nameEt.setText(Storage.defaultName())
            btnDelete.visibility = View.GONE
            pendingPhoto?.let { showFile(it) }
        } else {
            title.text = "Редактирование"
            nameEt.setText(orig)
            Thread {
                val entry = Storage.find(this, orig)
                val bmp: Bitmap? = entry?.photo?.let { Images.decode(this, it, 1200) }
                runOnUiThread {
                    existingPhoto = entry?.photo
                    if (entry != null) noteEt.setText(entry.note)
                    if (bmp != null && pendingPhoto == null) showBase(bmp)
                }
            }.start()
        }
    }

    private fun showBase(bmp: Bitmap) {
        baseBitmap = bmp
        sharpBitmap = null
        sharpenOn = false
        cutBitmap = null
        cutOn = false
        btnCutout.text = "Убрать фон (PNG): выкл"
        photoView.setBackgroundColor(0xFFDDDDDD.toInt())
        btnSharpen.text = "Повысить резкость: выкл"
        rotation = 0
        refreshPreview()
    }

    private fun refreshPreview() {
        val b = (if (cutOn) cutBitmap else null) ?: (if (sharpenOn) sharpBitmap else null) ?: baseBitmap ?: return
        photoView.setImageBitmap(if (rotation == 0) b else Images.rotate(b, rotation))
    }

    private fun rotate(delta: Int) {
        if (baseBitmap == null) return
        rotation = ((rotation + delta) % 360 + 360) % 360
        refreshPreview()
    }

    private fun toggleCutout() {
        if (baseBitmap == null) return
        if (cutOn) {
            cutOn = false
            btnCutout.text = "Убрать фон (PNG): выкл"
            photoView.setBackgroundColor(0xFFDDDDDD.toInt())
            refreshPreview()
            return
        }
        computeCutout()
    }

    private fun computeCutout() {
        val src = (if (sharpenOn) sharpBitmap else null) ?: baseBitmap ?: return
        btnCutout.isEnabled = false
        Thread {
            val cut = Images.removeBackground(src)
            runOnUiThread {
                btnCutout.isEnabled = true
                if (cut == null) {
                    Toast.makeText(this, "Не получилось отделить фон: нужен однотонный фон вокруг флешки", Toast.LENGTH_LONG).show()
                } else {
                    cutBitmap = cut
                    cutOn = true
                    btnCutout.text = "Убрать фон (PNG): вкл"
                    photoView.setBackgroundColor(0xFFB0BEC5.toInt())
                    refreshPreview()
                }
            }
        }.start()
    }

    private fun toggleSharpen() {
        val base = baseBitmap ?: return
        if (sharpenOn) {
            sharpenOn = false
            btnSharpen.text = "Повысить резкость: выкл"
            if (cutOn) computeCutout() else refreshPreview()
            return
        }
        btnSharpen.isEnabled = false
        Thread {
            val sb = Images.unsharp(base, SHARPEN_AMOUNT)
            runOnUiThread {
                sharpBitmap = sb
                sharpenOn = true
                btnSharpen.text = "Повысить резкость: вкл"
                btnSharpen.isEnabled = true
                if (cutOn) computeCutout() else refreshPreview()
            }
        }.start()
    }

    private fun showFile(path: String) {
        Thread {
            val bmp = Images.decodeFile(path, 1200)
            runOnUiThread { if (bmp != null) showBase(bmp) }
        }.start()
    }

    private fun save() {
        val name = nameEt.text.toString()
        val note = noteEt.text.toString()
        val orig = originalName
        val rot = rotation
        val sharp = sharpenOn
        val cut = cutOn
        btnSave.isEnabled = false
        Thread {
            var photo: File? = pendingPhoto?.let { File(it) }
            if (rot != 0 || sharp) {
                val src: Bitmap? = if (photo != null) {
                    Images.decodeFile(photo.path, 4000)
                } else {
                    existingPhoto?.let { Images.decode(this, it, 4000) }
                }
                if (src != null) {
                    var res: Bitmap = src
                    if (sharp) res = Images.unsharp(res, SHARPEN_AMOUNT)
                    if (rot != 0) res = Images.rotate(res, rot)
                    val out = File(cacheDir, "proc_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(out).use { res.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    photo?.delete()
                    photo = out
                }
            }
            val err = if (orig == null) {
                Storage.saveNew(this, name, note, photo)
            } else {
                Storage.update(this, orig, name, note, photo)
            }
            var cutErr: String? = null
            if (err == null && cut) {
                val srcBmp: Bitmap? = if (photo != null) {
                    Images.decodeFile(photo.path, 3000)
                } else {
                    existingPhoto?.let { Images.decode(this, it, 3000) }
                }
                val res = srcBmp?.let { Images.removeBackground(it) }
                if (res == null) {
                    cutErr = "Прозрачный PNG не получился, фото сохранено без него"
                } else {
                    val bos = java.io.ByteArrayOutputStream()
                    res.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    cutErr = Storage.saveExtra(this, Storage.sanitize(name), "photo_nobg.png", "image/png", bos.toByteArray())
                }
            }
            runOnUiThread {
                if (cutErr != null) Toast.makeText(this, cutErr, Toast.LENGTH_LONG).show()
                if (err == null) {
                    photo?.delete()
                    finish()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    btnSave.isEnabled = true
                }
            }
        }.start()
    }

    private fun confirmDelete() {
        val orig = originalName ?: return
        AlertDialog.Builder(this)
            .setTitle("Удалить «$orig»?")
            .setMessage("Папка с фото и аннотацией будет удалена (и на других устройствах после синхронизации).")
            .setPositiveButton("Удалить") { _, _ ->
                Thread {
                    val ok = Storage.delete(this, orig)
                    runOnUiThread {
                        if (ok) finish() else Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
