package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
    private var manualCutPath: String? = null
    private var manualMaskPath: String? = null
    private var dirty = false
    private var originalSrc: File? = null        // копия первого снимка (для новой карточки)
    private var photoEdited = false              // фото менялось: нужен скрытый оригинал
    private var existingCutUri: Uri? = null      // уже сохранённый photo_nobg.png
    private var existingCutBmp: Bitmap? = null
    private var keepExistingCut = true
    private var hasOrigOnCard = false
    private var hasMaskOnCard = false

    private fun copyToCache(src: File, prefix: String): File? = try {
        val f = File(cacheDir, "${prefix}_${System.nanoTime()}.jpg")
        src.copyTo(f, true)
        f
    } catch (e: Exception) { null }
    private lateinit var dirtyBanner: TextView

    private fun cleanupTemp() {
        manualCutPath?.let { File(it).delete() }
        manualMaskPath?.let { File(it).delete() }
        pendingPhoto?.let { File(it).delete() }
        originalSrc?.delete()
    }

    private fun markDirty() {
        dirty = true
        if (::dirtyBanner.isInitialized) dirtyBanner.visibility = View.VISIBLE
    }

    /** Сбрасывает ручную правку фона (контур и маску). */
    private fun clearManual() {
        manualCutPath?.let { File(it).delete() }
        manualMaskPath?.let { File(it).delete() }
        manualCutPath = null
        manualMaskPath = null
    }

    /** Если есть ручная правка, которую сотрёт следующее действие, спрашиваем. */
    private fun confirmDiscardManual(what: String, go: () -> Unit) {
        if (manualCutPath == null) { go(); return }
        AlertDialog.Builder(this)
            .setTitle("Сбросить ручную правку фона?")
            .setMessage("$what изменит фото, и ручная правка кистью/ИИ пропадёт (её придётся сделать заново).")
            .setPositiveButton("Продолжить") { _, _ -> clearManual(); go() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- страницы: главное фото + дополнительные ----
    private lateinit var pager: HorizontalScrollView
    private lateinit var pagerRow: LinearLayout
    private lateinit var pageHint: TextView
    private var extras: List<Extra> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var touching = false
    private var pendingExtraName: String? = null
    private var extraOp = "add"

    private val retake = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(EXTRA_PHOTO)
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            pendingPhoto?.let { File(it).delete() }
            pendingPhoto = p
            originalSrc?.delete()
            originalSrc = copyToCache(File(p), "orig")
            photoEdited = false
            keepExistingCut = false
            clearManual()
            markDirty()
            showFile(p)
        }
    }

    private val cropper = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(CropActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            pendingPhoto?.let { File(it).delete() }
            pendingPhoto = p
            photoEdited = true
            keepExistingCut = false
            clearManual()
            markDirty()
            showFile(p)
        }
    }

    private val eraser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(EraseActivity.RESULT_PHOTO)
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            pendingPhoto?.let { File(it).delete() }
            manualCutPath?.let { File(it).delete() }
            manualMaskPath?.let { File(it).delete() }
            pendingPhoto = p
            manualCutPath = r.data?.getStringExtra(EraseActivity.RESULT_CUT)
            manualMaskPath = r.data?.getStringExtra(EraseActivity.RESULT_MASK)
            photoEdited = true
            keepExistingCut = false
            markDirty()
            showFile(p)
        }
    }

    /** Ручная правка фона и яркости: отдаём текущее фото с учётом поворота и резкости. */
    private fun startEraser(autoAi: Boolean = false) {
        if (baseBitmap == null) return
        val rot = rotation
        val sharp = sharpenOn
        var prevMask = manualMaskPath
        val canUseCardMask = prevMask == null && originalName != null && hasMaskOnCard && pendingPhoto == null && cutOn
        val btn = findViewById<Button>(R.id.btnEraser)
        btn.isEnabled = false
        Thread {
            val src: Bitmap? = pendingPhoto?.let { Images.decodeFile(it, 3200) }
                ?: existingPhoto?.let { Images.decode(this, it, 3200) }
            if (canUseCardMask) {
                Storage.readFile(this, originalName!!, Storage.MASK)?.let {
                    val mf = File(cacheDir, "prevmask_${System.nanoTime()}.png")
                    mf.writeBytes(it)
                    prevMask = mf.absolutePath
                }
            }
            var tmp: File? = null
            if (src != null) {
                var res: Bitmap = src
                if (sharp) res = Images.unsharp(res, SHARPEN_AMOUNT)
                if (rot != 0) res = Images.rotate(res, rot)
                val out = File(cacheDir, "erase_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { res.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                tmp = out
            }
            runOnUiThread {
                btn.isEnabled = true
                if (tmp == null) {
                    Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_LONG).show()
                } else {
                    val it = Intent(this, EraseActivity::class.java)
                        .putExtra(EraseActivity.EXTRA_PATH, tmp.absolutePath)
                        .putExtra(EraseActivity.EXTRA_AUTO_AI, autoAi && prevMask == null)
                    val pm = prevMask
                    if (pm != null) it.putExtra(EraseActivity.EXTRA_PREV_MASK, pm).putExtra(EraseActivity.EXTRA_PREV_ROT, rot)
                    eraser.launch(it)
                }
            }
        }.start()
    }

    /** Открывает ручную обрезку: берём текущее фото с учётом поворота (резкость и фон потом включаются заново). */
    private fun startCrop() {
        if (baseBitmap == null) return
        val rot = rotation
        val btn = findViewById<Button>(R.id.btnCrop)
        btn.isEnabled = false
        Thread {
            val src: Bitmap? = pendingPhoto?.let { Images.decodeFile(it, 3200) }
                ?: existingPhoto?.let { Images.decode(this, it, 3200) }
            var tmp: File? = null
            if (src != null) {
                val res = if (rot != 0) Images.rotate(src, rot) else src
                val out = File(cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { res.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                tmp = out
            }
            runOnUiThread {
                btn.isEnabled = true
                if (tmp == null) {
                    Toast.makeText(this, "Не удалось открыть фото для обрезки", Toast.LENGTH_LONG).show()
                } else {
                    cropper.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, tmp.absolutePath))
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        photoView = findViewById(R.id.photo)
        pager = findViewById(R.id.pager)
        pagerRow = findViewById(R.id.pagerRow)
        pageHint = findViewById(R.id.pageHint)
        setupPager()
        findViewById<Button>(R.id.btnAddExtra).setOnClickListener { showAddExtra() }
        nameEt = findViewById(R.id.nameEt)
        noteEt = findViewById(R.id.noteEt)
        btnSave = findViewById(R.id.btnSave)
        dirtyBanner = findViewById(R.id.dirtyBanner)
        val tw = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (nameEt.hasFocus() || noteEt.hasFocus()) markDirty() }
        }
        nameEt.addTextChangedListener(tw)
        noteEt.addTextChangedListener(tw)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) { finish(); return }
                AlertDialog.Builder(this@EditActivity)
                    .setTitle("Есть несохранённые изменения")
                    .setMessage("Сохранить карточку в каталог?")
                    .setPositiveButton("Сохранить") { _, _ -> save() }
                    .setNegativeButton("Не сохранять") { _, _ -> cleanupTemp(); finish() }
                    .setNeutralButton("Остаться", null)
                    .show()
            }
        })
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
        findViewById<Button>(R.id.btnCrop).setOnClickListener { confirmDiscardManual("Обрезка") { startCrop() } }
        findViewById<Button>(R.id.btnEraser).setOnClickListener { startEraser() }
        btnSharpen.setOnClickListener { confirmDiscardManual("Изменение резкости") { toggleSharpen() } }
        btnSave.setOnClickListener { save() }
        btnDelete.setOnClickListener { confirmDelete() }

        val orig = originalName
        if (orig == null) {
            title.text = "Новая флешка"
            nameEt.setText(Storage.defaultName())
            btnDelete.visibility = View.GONE
            findViewById<View>(R.id.extraNote).visibility = View.VISIBLE
            pendingPhoto?.let {
                originalSrc = copyToCache(File(it), "orig")
                showFile(it)
            }
        } else {
            title.text = "Редактирование"
            nameEt.setText(orig)
            findViewById<View>(R.id.btnAddExtra).visibility = View.VISIBLE
            findViewById<View>(R.id.btnShareQr).visibility = View.VISIBLE
            findViewById<View>(R.id.btnWebLink).visibility = View.VISIBLE
            findViewById<Button>(R.id.btnWebLink).setOnClickListener { WebShareUi.start(this, listOf(orig)) }
            findViewById<Button>(R.id.btnShareQr).setOnClickListener {
                startActivity(
                    Intent(this, ExchangeActivity::class.java)
                        .putExtra(ExchangeActivity.EXTRA_FOLDERS, arrayOf(orig))
                )
            }
            reloadExtras()
            val btnRestore = findViewById<Button>(R.id.btnRestoreOrig)
            btnRestore.setOnClickListener { restoreOriginal() }
            Thread {
                val entry = Storage.find(this, orig)
                val bmp: Bitmap? = entry?.photo?.let { Images.decode(this, it, 1200) }
                val cutB: Bitmap? = entry?.cutout?.let { Images.decode(this, it, 1200) }
                val hasO = Storage.hasFile(this, orig, Storage.ORIG)
                val hasM = Storage.hasFile(this, orig, Storage.MASK)
                runOnUiThread {
                    existingPhoto = entry?.photo
                    existingCutUri = if (cutB != null) entry?.cutout else null
                    existingCutBmp = cutB
                    hasOrigOnCard = hasO
                    hasMaskOnCard = hasM
                    if (hasO) btnRestore.visibility = View.VISIBLE
                    if (entry != null) noteEt.setText(entry.note)
                    if (bmp != null && pendingPhoto == null) showBase(bmp)
                }
            }.start()
        }
    }


    // ================= дополнительные фото =================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pageWidth(): Int = pager.width

    private fun applyPageSizes() {
        val w = pageWidth()
        if (w <= 0) {
            pager.post { applyPageSizes() }
            return
        }
        for (i in 0 until pagerRow.childCount) {
            pagerRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        updateHint()
    }

    private fun currentPage(): Int {
        val w = pageWidth()
        return if (w <= 0) 0 else Math.round(pager.scrollX / w.toFloat())
    }

    private fun updateHint() {
        val n = extras.size
        if (n == 0) {
            pageHint.visibility = View.GONE
            return
        }
        pageHint.visibility = View.VISIBLE
        val i = currentPage().coerceIn(0, n)
        pageHint.text = when {
            i == 0 -> "ещё $n фото  ›"
            i == n -> "‹  ${i + 1} / ${n + 1}"
            else -> "‹  ${i + 1} / ${n + 1}  ›"
        }
    }

    private val snapRunnable = Runnable {
        if (touching) return@Runnable
        val w = pageWidth()
        if (w <= 0) return@Runnable
        val page = currentPage().coerceIn(0, pagerRow.childCount - 1)
        val target = page * w
        if (kotlin.math.abs(pager.scrollX - target) > 2) pager.smoothScrollTo(target, 0)
    }

    private fun setupPager() {
        pager.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    uiHandler.removeCallbacks(snapRunnable)
                    uiHandler.postDelayed(snapRunnable, 120)
                }
            }
            false
        }
        pager.setOnScrollChangeListener { _, _, _, _, _ ->
            updateHint()
            uiHandler.removeCallbacks(snapRunnable)
            uiHandler.postDelayed(snapRunnable, 100)
        }
        pager.post { applyPageSizes() }
    }

    private fun reloadExtras() {
        val folder = originalName ?: return
        Thread {
            val list = Storage.listExtras(this, folder)
            val bmps = list.map { Images.decode(this, it.uri, 1200) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                while (pagerRow.childCount > 1) pagerRow.removeViewAt(pagerRow.childCount - 1)
                extras = list
                for (i in list.indices) {
                    val iv = ImageView(this)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.setBackgroundColor(0xFFDDDDDD.toInt())
                    iv.setImageBitmap(bmps[i])
                    val ex = list[i]
                    iv.setOnClickListener { showExtraActions(ex) }
                    pagerRow.addView(iv)
                }
                applyPageSizes()
            }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private val extraCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(EXTRA_PHOTO)
        if (r.resultCode == Activity.RESULT_OK && p != null) storeExtraFile(File(p), null)
    }

    private val extraGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Thread {
                val f = File(cacheDir, "imp_${System.nanoTime()}.jpg")
                try {
                    contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    runOnUiThread { openExtraCrop(f, null) }
                } catch (e: Exception) {
                    runOnUiThread { toast("Не удалось открыть фото: ${e.message}") }
                }
            }.start()
        }
    }

    private val extraCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(CropActivity.EXTRA_PATH)
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            storeExtraFile(File(p), if (extraOp == "replace") pendingExtraName else null)
        }
    }

    private val extraBright = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = r.data?.getStringExtra(EraseActivity.RESULT_PHOTO)
        if (r.resultCode == Activity.RESULT_OK && p != null) storeExtraFile(File(p), pendingExtraName)
    }

    private fun openExtraCrop(f: File, replaceName: String?) {
        extraOp = if (replaceName != null) "replace" else "add"
        pendingExtraName = replaceName
        extraCrop.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, f.absolutePath))
    }

    /** Записывает готовый JPEG в карточку: новым номером или поверх существующего файла. */
    private fun storeExtraFile(f: File, replaceName: String?) {
        val folder = originalName ?: return
        Thread {
            val bytes = f.readBytes()
            f.delete()
            val err = if (replaceName != null) Storage.replaceExtra(this, folder, replaceName, bytes)
            else Storage.addExtra(this, folder, bytes)
            runOnUiThread {
                if (err != null) toast(err) else {
                    reloadExtras()
                    pager.postDelayed({ pager.smoothScrollTo(pageWidth() * (if (replaceName == null) extras.size else currentPage()), 0) }, 400)
                }
            }
        }.start()
    }

    private fun showAddExtra() {
        AlertDialog.Builder(this)
            .setTitle("Добавить фото в карточку")
            .setItems(arrayOf("Снять камерой", "Выбрать из галереи")) { _, which ->
                if (which == 0) {
                    extraCamera.launch(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_RETURN, true))
                } else {
                    extraGallery.launch("image/*")
                }
            }
            .show()
    }

    private fun copyExtraToCache(ex: Extra, then: (File) -> Unit) {
        Thread {
            try {
                val f = File(cacheDir, "ex_${System.nanoTime()}.jpg")
                contentResolver.openInputStream(ex.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                runOnUiThread { then(f) }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось открыть фото: ${e.message}") }
            }
        }.start()
    }

    private fun showExtraActions(ex: Extra) {
        AlertDialog.Builder(this)
            .setTitle(ex.name)
            .setItems(arrayOf("Обрезать", "Яркость", "Повернуть вправо", "Удалить")) { _, which ->
                when (which) {
                    0 -> copyExtraToCache(ex) { openExtraCrop(it, ex.name) }
                    1 -> copyExtraToCache(ex) { f ->
                        pendingExtraName = ex.name
                        extraBright.launch(
                            Intent(this, EraseActivity::class.java)
                                .putExtra(EraseActivity.EXTRA_PATH, f.absolutePath)
                                .putExtra(EraseActivity.EXTRA_NO_CUT, true)
                        )
                    }
                    2 -> copyExtraToCache(ex) { f ->
                        Thread {
                            val b = Images.loadUpright(f.path, 3200)
                            f.delete()
                            if (b != null) {
                                val out = File(cacheDir, "rot_${System.nanoTime()}.jpg")
                                FileOutputStream(out).use { Images.rotate(b, 90).compress(Bitmap.CompressFormat.JPEG, 94, it) }
                                runOnUiThread { storeExtraFile(out, ex.name) }
                            }
                        }.start()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Удалить ${ex.name}?")
                        .setPositiveButton("Удалить") { _, _ ->
                            val folder = originalName ?: return@setPositiveButton
                            Thread {
                                Storage.deleteExtra(this, folder, ex.name)
                                runOnUiThread { reloadExtras() }
                            }.start()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .show()
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
        val mc = manualCutPath
        if (mc != null) {
            val cb = Images.decodeFile(mc, 1200)
            if (cb != null) {
                cutBitmap = cb
                cutOn = true
                btnCutout.text = "Убрать фон (PNG): вкл"
                photoView.setBackgroundColor(0xFFB0BEC5.toInt())
                refreshPreview()
                return
            }
            manualCutPath = null
        }
        val ecb = existingCutBmp
        if (originalName != null && ecb != null && keepExistingCut && pendingPhoto == null) {
            cutBitmap = ecb
            cutOn = true
            btnCutout.text = "Убрать фон (PNG): вкл"
            photoView.setBackgroundColor(0xFFB0BEC5.toInt())
            refreshPreview()
            return
        }
        if (originalName == null && getSharedPreferences("fc", MODE_PRIVATE).getBoolean("cut", false)) {
            computeCutout()
        }
    }

    private fun refreshPreview() {
        val b = (if (cutOn) cutBitmap else null) ?: (if (sharpenOn) sharpBitmap else null) ?: baseBitmap ?: return
        photoView.setImageBitmap(if (rotation == 0) b else Images.rotate(b, rotation))
    }

    private fun rotate(delta: Int) {
        if (baseBitmap == null) return
        rotation = ((rotation + delta) % 360 + 360) % 360
        markDirty()
        photoEdited = true
        refreshPreview()
    }

    private fun toggleCutout() {
        if (baseBitmap == null) return
        val prefs = getSharedPreferences("fc", MODE_PRIVATE)
        if (cutOn) {
            cutOn = false
            clearManual()
            markDirty()
            prefs.edit().putBoolean("cut", false).apply()
            btnCutout.text = "Убрать фон (PNG): выкл"
            photoView.setBackgroundColor(0xFFDDDDDD.toInt())
            refreshPreview()
            return
        }
        prefs.edit().putBoolean("cut", true).apply()
        markDirty()
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
                    AlertDialog.Builder(this)
                        .setTitle("Не вижу границ флешки")
                        .setMessage("Автоматика не справилась (светлая на светлом, блики или флешка занимает весь кадр).\n\nМожно попробовать ИИ-сервис онлайн (бесплатно, без регистрации), стереть фон вручную или переснять на контрастном фоне.")
                        .setPositiveButton("ИИ онлайн") { _, _ -> startEraser(true) }
                        .setNeutralButton("Вручную") { _, _ -> startEraser(false) }
                        .setNegativeButton("Отмена", null)
                        .show()
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
        markDirty()
        photoEdited = true
        keepExistingCut = false
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

    /** Вернуть скрытый оригинал карточки и начать правку с нуля. */
    private fun restoreOriginal() {
        val orig = originalName ?: return
        AlertDialog.Builder(this)
            .setTitle("Начать правку заново?")
            .setMessage("Текущее фото и фон будут заменены оригиналом. Это применится после «Сохранить в каталог».")
            .setPositiveButton("Вернуть оригинал") { _, _ ->
                Thread {
                    val bytes = Storage.readFile(this, orig, Storage.ORIG)
                    val f = if (bytes != null) File(cacheDir, "restored_${System.nanoTime()}.jpg").also { it.writeBytes(bytes) } else null
                    runOnUiThread {
                        if (f == null) { toast("Не удалось прочитать оригинал"); return@runOnUiThread }
                        pendingPhoto?.let { File(it).delete() }
                        pendingPhoto = f.absolutePath
                        clearManual()
                        keepExistingCut = false
                        photoEdited = false
                        markDirty()
                        showFile(f.absolutePath)
                    }
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun save() {
        val name = nameEt.text.toString()
        val note = noteEt.text.toString()
        val orig = originalName
        val rot = rotation
        val sharp = sharpenOn
        val cut = cutOn
        val manualCut = manualCutPath
        val manualMask = manualMaskPath
        val edited = photoEdited || rot != 0 || sharp
        val keepCut = keepExistingCut && existingCutUri != null
        val oldCutUri = existingCutUri
        val origSrcFile = originalSrc
        btnSave.isEnabled = false
        Thread {
            var photo: File? = pendingPhoto?.let { File(it) }
            val photoChanged = photo != null || rot != 0 || sharp
            // скрытый оригинал: берём до записи, пока старое фото ещё на месте
            var origBytes: ByteArray? = null
            if (edited) {
                if (orig == null) {
                    origBytes = origSrcFile?.takeIf { it.exists() }?.readBytes()
                } else if (!Storage.hasFile(this, orig, Storage.ORIG)) {
                    origBytes = Storage.readFile(this, orig, Storage.PHOTO)
                }
            }
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
            val folder = Storage.sanitize(name)
            var cutErr: String? = null
            if (err == null) {
                if (origBytes != null) {
                    val e = Storage.saveExtra(this, folder, Storage.ORIG, "image/jpeg", origBytes)
                    if (e != null) cutErr = "Оригинал не сохранился: $e"
                }
                // маска ручной правки (в ориентации сохранённого фото)
                if (manualMask != null) {
                    var mk: Bitmap? = Images.decodeFile(manualMask, 2000)
                    if (mk != null && rot != 0) mk = Images.rotate(mk, rot)
                    if (mk != null) {
                        val bos = java.io.ByteArrayOutputStream()
                        mk.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        Storage.saveExtra(this, folder, Storage.MASK, "image/png", bos.toByteArray())
                    }
                } else if (photoChanged && orig != null) {
                    Storage.deleteExtra(this, folder, Storage.MASK)
                }
                if (cut) {
                    if (manualCut != null) {
                        var mb: Bitmap? = Images.decodeFile(manualCut, 4000)
                        if (mb != null && rot != 0) mb = Images.rotate(mb, rot)
                        if (mb == null) {
                            cutErr = "Прозрачный PNG не получился, фото сохранено без него"
                        } else {
                            val bos = java.io.ByteArrayOutputStream()
                            mb.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            cutErr = Storage.saveExtra(this, folder, Storage.CUT, "image/png", bos.toByteArray())
                        }
                        File(manualCut).delete()
                    } else if (keepCut) {
                        // прежний вырез остаётся; при повороте крутим и его
                        if (rot != 0 && oldCutUri != null) {
                            val old = Images.decode(this, oldCutUri, 4000)
                            if (old != null) {
                                val bos = java.io.ByteArrayOutputStream()
                                Images.rotate(old, rot).compress(Bitmap.CompressFormat.PNG, 100, bos)
                                cutErr = Storage.saveExtra(this, folder, Storage.CUT, "image/png", bos.toByteArray())
                            }
                        }
                    } else {
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
                            cutErr = Storage.saveExtra(this, folder, Storage.CUT, "image/png", bos.toByteArray())
                        }
                    }
                } else if (oldCutUri != null) {
                    Storage.deleteExtra(this, folder, Storage.CUT)
                }
            }
            runOnUiThread {
                if (cutErr != null) Toast.makeText(this, cutErr, Toast.LENGTH_LONG).show()
                if (err == null) {
                    photo?.delete()
                    manualMaskPath?.let { File(it).delete() }
                    origSrcFile?.delete()
                    dirty = false
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
