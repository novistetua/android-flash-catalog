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
    }

    private lateinit var photoView: ImageView
    private lateinit var nameEt: EditText
    private lateinit var noteEt: EditText
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private var originalName: String? = null
    private var pendingPhoto: String? = null
    private var existingPhoto: Uri? = null
    private var baseBitmap: Bitmap? = null
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
        val title = findViewById<TextView>(R.id.title)

        originalName = intent.getStringExtra(EXTRA_FOLDER)
        pendingPhoto = intent.getStringExtra(EXTRA_PHOTO)

        findViewById<Button>(R.id.btnRetake).setOnClickListener {
            retake.launch(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_RETURN, true))
        }
        findViewById<Button>(R.id.btnRotL).setOnClickListener { rotate(-90) }
        findViewById<Button>(R.id.btnRotR).setOnClickListener { rotate(90) }
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
        rotation = 0
        refreshPreview()
    }

    private fun refreshPreview() {
        val b = baseBitmap ?: return
        photoView.setImageBitmap(if (rotation == 0) b else Images.rotate(b, rotation))
    }

    private fun rotate(delta: Int) {
        if (baseBitmap == null) return
        rotation = ((rotation + delta) % 360 + 360) % 360
        refreshPreview()
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
        btnSave.isEnabled = false
        Thread {
            var photo: File? = pendingPhoto?.let { File(it) }
            if (rot != 0) {
                val src: Bitmap? = if (photo != null) {
                    Images.decodeFile(photo.path, 4000)
                } else {
                    existingPhoto?.let { Images.decode(this, it, 4000) }
                }
                if (src != null) {
                    val rotated = Images.rotate(src, rot)
                    val out = File(cacheDir, "rot_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(out).use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    photo?.delete()
                    photo = out
                }
            }
            val err = if (orig == null) {
                Storage.saveNew(this, name, note, photo)
            } else {
                Storage.update(this, orig, name, note, photo)
            }
            runOnUiThread {
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
