package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Просмотр карточки: фото (с листанием), название и описание (можно скопировать), кнопки передачи и правки. */
class CardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER = "folder"
    }

    private lateinit var pager: HorizontalScrollView
    private lateinit var pagerRow: LinearLayout
    private lateinit var pageHint: TextView
    private lateinit var nameTv: TextView
    private lateinit var noteTv: TextView
    private var folder = ""
    private var pages = 0
    private var touching = false
    private val ui = Handler(Looper.getMainLooper())

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // карточку могли переименовать: EditActivity возвращает новое имя папки
        r.data?.getStringExtra(EditActivity.EXTRA_FOLDER)?.let { folder = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card)
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: run { finish(); return }
        pager = findViewById(R.id.pager)
        pagerRow = findViewById(R.id.pagerRow)
        pageHint = findViewById(R.id.pageHint)
        nameTv = findViewById(R.id.cardName)
        noteTv = findViewById(R.id.cardNote)
        setupPager()
        findViewById<Button>(R.id.btnShareQr).setOnClickListener {
            startActivity(Intent(this, ExchangeActivity::class.java).putExtra(ExchangeActivity.EXTRA_FOLDERS, arrayOf(folder)))
        }
        findViewById<Button>(R.id.btnWebLink).setOnClickListener { WebShareUi.start(this, listOf(folder)) }
        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            editor.launch(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_FOLDER, folder))
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val name = folder
        Thread {
            val entry = Storage.find(this, name)
            val extras = Storage.listExtras(this, name)
            val first = (entry?.cutout ?: entry?.photo)?.let { Images.decode(this, it, 1600) }
            val bmps = extras.map { Images.decode(this, it.uri, 1600) }
            val cut = entry?.cutout != null
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (entry == null) { finish(); return@runOnUiThread }
                nameTv.text = entry.name
                noteTv.text = entry.note.ifBlank { "(без аннотации)" }
                pagerRow.removeAllViews()
                fun add(b: Bitmap?, gray: Int) {
                    val iv = ImageView(this)
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    iv.setBackgroundColor(gray)
                    if (b != null) iv.setImageBitmap(b)
                    pagerRow.addView(iv)
                }
                add(first, if (cut) 0xFFB0BEC5.toInt() else 0xFFDDDDDD.toInt())
                for (b in bmps) add(b, 0xFFDDDDDD.toInt())
                pages = pagerRow.childCount
                applySizes()
            }
        }.start()
    }

    // ---- листание страниц ----
    private fun pageW(): Int = pager.width

    private fun applySizes() {
        val w = pageW()
        if (w <= 0) { pager.post { applySizes() }; return }
        for (i in 0 until pagerRow.childCount) pagerRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.MATCH_PARENT)
        updateHint()
    }

    private fun current(): Int { val w = pageW(); return if (w <= 0) 0 else Math.round(pager.scrollX / w.toFloat()) }

    private fun updateHint() {
        val n = pages - 1
        if (n <= 0) { pageHint.visibility = View.GONE; return }
        pageHint.visibility = View.VISIBLE
        val i = current().coerceIn(0, n)
        pageHint.text = when {
            i == 0 -> "ещё $n фото  ›"
            i == n -> "‹  ${i + 1} / ${n + 1}"
            else -> "‹  ${i + 1} / ${n + 1}  ›"
        }
    }

    private val snap = Runnable {
        if (touching) return@Runnable
        val w = pageW()
        if (w <= 0 || pagerRow.childCount == 0) return@Runnable
        val target = current().coerceIn(0, pagerRow.childCount - 1) * w
        if (kotlin.math.abs(pager.scrollX - target) > 2) pager.smoothScrollTo(target, 0)
    }

    private fun setupPager() {
        pager.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    ui.removeCallbacks(snap)
                    ui.postDelayed(snap, 120)
                }
            }
            false
        }
        pager.setOnScrollChangeListener { _, _, _, _, _ ->
            updateHint()
            ui.removeCallbacks(snap)
            ui.postDelayed(snap, 100)
        }
    }

    private fun confirmDelete() {
        val name = folder
        AlertDialog.Builder(this)
            .setTitle("Удалить «$name»?")
            .setMessage("Папка с фото и аннотацией будет удалена (и на других устройствах после синхронизации).")
            .setPositiveButton("Удалить") { _, _ ->
                Thread {
                    val ok = Storage.delete(this, name)
                    runOnUiThread {
                        if (ok) finish() else Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
