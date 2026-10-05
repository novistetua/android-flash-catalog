package com.novis.flashcatalog

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * Приём фото: из галереи (кнопка «Из галереи») и из других приложений (кнопка «Поделиться»).
 * Фото копируется во временный файл, затем обрезка, затем новая карточка или дополнительное фото в существующую.
 */
class ImportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_TARGET = "target_folder"
    }

    private var target: String? = null
    private var tmp: File? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) finish() else handleUri(uri)
    }

    private val cropper = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(CropActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && p != null) done(File(p)) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Storage.getRoot(this) == null) {
            Toast.makeText(this, "Сначала выбери папку каталога в приложении", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        target = intent.getStringExtra(EXTRA_TARGET)
        when {
            intent.action == Intent.ACTION_SEND -> {
                val uri = sharedUri()
                if (uri == null) finish() else handleUri(uri)
            }
            intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                val first = list?.firstOrNull()
                if (first == null) finish() else {
                    Toast.makeText(this, "Взято первое из ${list.size} фото. Остальные отправь по очереди.", Toast.LENGTH_LONG).show()
                    handleUri(first)
                }
            }
            intent.getBooleanExtra(EXTRA_PICK, false) -> picker.launch("image/*")
            else -> finish()
        }
    }

    private fun sharedUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun handleUri(uri: Uri) {
        Thread {
            val f = File(cacheDir, "imp_${System.nanoTime()}.jpg")
            try {
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    ?: throw IllegalStateException("файл не открылся")
                runOnUiThread { tmp = f; chooseDestination() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Не удалось открыть фото: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun chooseDestination() {
        if (target != null) {
            openCrop()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Что сделать с фото?")
            .setItems(arrayOf("Новая карточка флешки", "Добавить в существующую карточку")) { _, which ->
                if (which == 0) openCrop() else chooseCard()
            }
            .setOnCancelListener { tmp?.delete(); finish() }
            .show()
    }

    private fun chooseCard() {
        Thread {
            val names = Storage.list(this).map { it.name }
            runOnUiThread {
                if (names.isEmpty()) {
                    Toast.makeText(this, "В каталоге пока нет карточек", Toast.LENGTH_LONG).show()
                    openCrop()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("В какую карточку?")
                    .setItems(names.toTypedArray()) { _, i -> target = names[i]; openCrop() }
                    .setOnCancelListener { tmp?.delete(); finish() }
                    .show()
            }
        }.start()
    }

    private fun openCrop() {
        val f = tmp ?: run { finish(); return }
        cropper.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun done(f: File) {
        val t = target
        if (t == null) {
            startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_PHOTO, f.absolutePath))
            finish()
            return
        }
        Thread {
            val err = Storage.addExtra(this, t, f.readBytes())
            f.delete()
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Фото добавлено в «$t»", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_FOLDER, t))
                }
                finish()
            }
        }.start()
    }
}
