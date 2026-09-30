package com.novis.flashcatalog

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newFixedThreadPool(3)
    private val cache = LruCache<String, Bitmap>(60)
    private val adapter = EntryAdapter()
    private var all: List<Entry> = emptyList()
    private var hasRoot = false

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var searchEt: EditText

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                Storage.setRoot(this, uri)
                reload()
            } catch (e: Exception) {
                Toast.makeText(this, "Эту папку использовать не получилось: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        searchEt = findViewById(R.id.search)
        listView.adapter = adapter

        findViewById<Button>(R.id.btnFolder).setOnClickListener { showFolderDialog() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            if (Storage.getRoot(this) == null) {
                showFolderDialog()
            } else {
                startActivity(Intent(this, CameraActivity::class.java))
            }
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            startActivity(
                Intent(this, EditActivity::class.java)
                    .putExtra(EditActivity.EXTRA_FOLDER, adapter.items[pos].name)
            )
        }
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun showFolderDialog() {
        AlertDialog.Builder(this)
            .setTitle("Папка каталога")
            .setMessage(
                "Сейчас: ${Storage.describeRoot(this)}\n\n" +
                    "Выбери папку, которую синхронизирует Syncthing (можно создать новую, например FlashCatalog, " +
                    "во внутренней памяти или на microSD).\n\n" +
                    "Если меняешь папку, старые записи сами не переносятся: скопируй их в новую папку " +
                    "или просто подключи к ней Syncthing."
            )
            .setPositiveButton("Выбрать") { _, _ -> pickFolder.launch(null) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reload() {
        cache.evictAll()
        hasRoot = Storage.getRoot(this) != null
        if (!hasRoot) {
            all = emptyList()
            applyFilter()
            return
        }
        io.execute {
            val items = Storage.list(this)
            runOnUiThread {
                if (!isDestroyed) {
                    all = items
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        val q = searchEt.text.toString().trim().lowercase()
        val shown = if (q.isEmpty()) all else all.filter {
            it.name.lowercase().contains(q) || it.note.lowercase().contains(q)
        }
        adapter.items = shown
        adapter.notifyDataSetChanged()
        if (shown.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = when {
                !hasRoot -> "Сначала выбери папку каталога — кнопка «Папка» сверху"
                all.isEmpty() -> "Каталог пуст. Нажми «Добавить флешку»"
                else -> "Ничего не найдено"
            }
        } else {
            emptyView.visibility = View.GONE
        }
    }

    inner class EntryAdapter : BaseAdapter() {
        var items: List<Entry> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
            val e = items[position]
            v.findViewById<TextView>(R.id.name).text = e.name
            v.findViewById<TextView>(R.id.note).text = e.note.ifBlank { "(без аннотации)" }
            val img = v.findViewById<ImageView>(R.id.thumb)
            val key = e.photo?.toString()
            img.tag = key
            if (key == null || e.photo == null) {
                img.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                val cached = cache.get(key)
                if (cached != null) {
                    img.setImageBitmap(cached)
                } else {
                    img.setImageResource(android.R.drawable.ic_menu_gallery)
                    io.execute {
                        val b = Images.decode(this@MainActivity, e.photo, 300)
                        if (b != null) {
                            cache.put(key, b)
                            runOnUiThread { if (img.tag == key) img.setImageBitmap(b) }
                        }
                    }
                }
            }
            return v
        }
    }
}
