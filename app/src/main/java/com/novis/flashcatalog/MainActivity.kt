package com.novis.flashcatalog

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newFixedThreadPool(3)
    private val cache = LruCache<String, Bitmap>(60)
    private val adapter = EntryAdapter()
    private var all: List<Entry> = emptyList()
    private var hasRoot = false
    private var moved = false

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var searchEt: EditText
    private lateinit var serviceBar: TextView
    private val barHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val barPoll = object : Runnable {
        override fun run() {
            updateBar()
            barHandler.postDelayed(this, 1000)
        }
    }

    /** Полоса вверху: что сейчас раздаётся (веб-ссылка, croc). */
    private fun updateBar() {
        val web = ShareService.server
        val cr = CrocSendService.state?.takeIf { !it.done }
        if (web == null && cr == null) {
            serviceBar.visibility = View.GONE
            return
        }
        val parts = ArrayList<String>()
        if (web != null) parts.add("веб-ссылка (порт ${web.port})")
        if (cr != null) parts.add("интернет-обмен: ${cr.phase.lowercase()}")
        serviceBar.text = "● Работает: " + parts.joinToString(" · ") + ". Нажми, чтобы посмотреть или остановить."
        serviceBar.visibility = View.VISIBLE
    }

    private fun showServiceDialog() {
        val web = ShareService.server
        val cr = CrocSendService.state?.takeIf { !it.done }
        if (web == null && cr == null) return
        val sb = StringBuilder()
        if (web != null) {
            sb.append("Веб-ссылка для браузера:\n")
            for (a in WebServer.addresses()) sb.append("• ${a.kind}: http://${a.ip}:${web.port}${web.basePath()}\n")
        }
        if (cr != null) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Интернет-обмен: ${cr.phase}\nКод: ${cr.code} (карточек: ${cr.cards})")
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Что сейчас работает")
            .setMessage(sb.toString())
            .setPositiveButton("Открыть обмен") { _, _ -> startActivity(Intent(this, ExchangeActivity::class.java)) }
        if (web != null) b.setNeutralButton("Остановить веб") { _, _ -> ShareService.stop(this); barHandler.postDelayed({ updateBar() }, 300) }
        if (cr != null) b.setNegativeButton("Остановить обмен") { _, _ -> CrocSendService.stop(this); barHandler.postDelayed({ updateBar() }, 300) }
        else b.setNegativeButton("Закрыть", null)
        b.show()
    }

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
        serviceBar = findViewById(R.id.serviceBar)
        serviceBar.setOnClickListener { showServiceDialog() }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        listView.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled(): Boolean = searchEt.text.isNullOrBlank()
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val a = vh.bindingAdapterPosition
                val b = target.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                val l = adapter.items.toMutableList()
                l.add(b, l.removeAt(a))
                adapter.items = l
                adapter.notifyItemMoved(a, b)
                moved = true
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.7f
            }
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.alpha = 1f
                if (moved) {
                    moved = false
                    all = adapter.items
                    Storage.saveManualOrder(this@MainActivity, all.map { it.name })
                    Toast.makeText(this@MainActivity, "Порядок сохранён: включён ручной режим", Toast.LENGTH_SHORT).show()
                }
            }
        }).attachToRecyclerView(listView)

        findViewById<Button>(R.id.btnFolder).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            if (Storage.getRoot(this) == null) {
                showFolderDialog()
            } else {
                startActivity(Intent(this, CameraActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnQr).setOnClickListener { startActivity(Intent(this, ExchangeActivity::class.java)) }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            if (Storage.getRoot(this) == null) {
                showFolderDialog()
            } else {
                startActivity(Intent(this, ImportActivity::class.java).putExtra(ImportActivity.EXTRA_PICK, true))
            }
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
        barHandler.post(barPoll)
    }

    override fun onPause() {
        super.onPause()
        barHandler.removeCallbacks(barPoll)
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
            EditorPage.refresh(this, items)
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
                !hasRoot -> "Сначала выбери папку каталога — «Настройки» → папка"
                all.isEmpty() -> "Каталог пуст. Нажми «Добавить флешку»"
                else -> "Ничего не найдено"
            }
        } else {
            emptyView.visibility = View.GONE
        }
    }

    inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.VH>() {
        var items: List<Entry> = emptyList()

        inner class VH(val v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_entry, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val v = h.v
            val e = items[position]
            v.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) startActivity(Intent(this@MainActivity, CardActivity::class.java).putExtra(CardActivity.EXTRA_FOLDER, items[p].name))
            }
            v.findViewById<TextView>(R.id.name).text = e.name
            v.findViewById<TextView>(R.id.note).text = e.note.ifBlank { "(без аннотации)" }
            val img = v.findViewById<ImageView>(R.id.thumb)
            val thumbUri = e.cutout ?: e.photo
            val key = thumbUri?.toString()
            img.tag = key
            if (key == null || thumbUri == null) {
                img.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                val cached = cache.get(key)
                if (cached != null) {
                    img.setImageBitmap(cached)
                } else {
                    img.setImageResource(android.R.drawable.ic_menu_gallery)
                    io.execute {
                        val b = Images.decode(this@MainActivity, thumbUri, 300)
                        if (b != null) {
                            cache.put(key, b)
                            runOnUiThread { if (img.tag == key) img.setImageBitmap(b) }
                        }
                    }
                }
            }
        }
    }
}
