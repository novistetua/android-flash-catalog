package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.security.SecureRandom

/**
 * Обмен карточками между устройствами. Отправка: пакуем и шифруем, отдаём по Wi‑Fi напрямую и (по желанию)
 * через интернет-обменник, всё это в одном QR. Получение: сканируем, пробуем сеть, затем интернет.
 */
class ExchangeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDERS = "folders"
    }

    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var cbNet: CheckBox
    private lateinit var qrView: ImageView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    private var server: LanServer? = null
    private var blob: File? = null
    private var key: ByteArray = ByteArray(0)
    private var lanUrl: String? = null
    private var relayUrl: String? = null
    private var relayNote: String = ""
    private var cardCount = 0
    private var busy = false

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringExtra(ScanActivity.RESULT_TEXT)
        if (r.resultCode == Activity.RESULT_OK && t != null) {
            val p = Pack.Payload.parse(t)
            if (p == null) toast("Это не QR от Flash Catalog") else receive(p)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exchange)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        cbNet = findViewById(R.id.cbNet)
        qrView = findViewById(R.id.qrView)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        btnSend.setOnClickListener { chooseWhat() }
        btnReceive.setOnClickListener {
            if (Storage.getRoot(this) == null) toast("Сначала выбери папку каталога") else
                scanner.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<Button>(R.id.btnWeb).setOnClickListener { chooseWeb() }
        val folders = intent.getStringArrayExtra(EXTRA_FOLDERS)
        if (folders != null && folders.isNotEmpty()) startSend(folders.toList())
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        blob?.delete()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun setBusy(b: Boolean) {
        busy = b
        btnSend.isEnabled = !b
        btnReceive.isEnabled = !b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        if (b) progress.isIndeterminate = true
    }

    // ================= отправка =================

    private fun chooseWhat() {
        if (Storage.getRoot(this) == null) {
            toast("Сначала выбери папку каталога")
            return
        }
        Thread {
            val names = Storage.list(this).map { it.name }
            runOnUiThread {
                if (names.isEmpty()) {
                    toast("В каталоге пока нет карточек")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Что отправить?")
                    .setItems(arrayOf("Весь каталог (${names.size})", "Выбрать карточки…")) { _, w ->
                        if (w == 0) startSend(null) else pickCards(names)
                    }
                    .show()
            }
        }.start()
    }

    /** Ссылка для браузера: весь каталог или выбранные карточки. */
    private fun chooseWeb() {
        if (Storage.getRoot(this) == null) {
            toast("Сначала выбери папку каталога")
            return
        }
        Thread {
            val names = Storage.list(this).map { it.name }
            runOnUiThread {
                if (names.isEmpty()) {
                    toast("В каталоге пока нет карточек")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Что показать по ссылке?")
                    .setItems(arrayOf("Весь каталог (${names.size})", "Выбрать карточки…")) { _, w ->
                        if (w == 0) WebShareUi.start(this, null) else {
                            val checked = BooleanArray(names.size)
                            AlertDialog.Builder(this)
                                .setTitle("Какие карточки?")
                                .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                                .setPositiveButton("Дальше") { _, _ ->
                                    val sel = names.filterIndexed { i, _ -> checked[i] }
                                    if (sel.isEmpty()) toast("Ничего не выбрано") else WebShareUi.start(this, sel)
                                }
                                .setNegativeButton("Отмена", null)
                                .show()
                        }
                    }
                    .show()
            }
        }.start()
    }

    private fun pickCards(names: List<String>) {
        val checked = BooleanArray(names.size)
        AlertDialog.Builder(this)
            .setTitle("Какие карточки?")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Отправить") { _, _ ->
                val sel = names.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) toast("Ничего не выбрано") else startSend(sel)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startSend(names: List<String>?) {
        if (busy) return
        server?.stop(); server = null
        blob?.delete()
        qrView.visibility = View.GONE
        lanUrl = null; relayUrl = null; relayNote = ""
        setBusy(true)
        status.text = "Упаковываю и шифрую…"
        Thread {
            try {
                val files = Storage.cardFiles(this, names)
                if (files.isEmpty()) throw IllegalStateException("нет файлов для отправки")
                val items = files.map { (card, f) ->
                    Pack.Item(card + "/" + f.name) {
                        contentResolver.openInputStream(f.uri) ?: throw java.io.IOException("не открыть ${f.name}")
                    }
                }
                val k = Pack.newKey()
                val out = File(cacheDir, "fcat_out.bin")
                Pack.writeZip(Pack.EncryptStream(out.outputStream().buffered(), k), items) { i, n ->
                    runOnUiThread { status.text = "Упаковываю файл $i из $n…" }
                }
                val cards = files.map { it.first }.distinct().size
                runOnUiThread { packed(out, k, cards) }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "Не получилось подготовить: ${e.message}"
                }
            }
        }.start()
    }

    private fun packed(out: File, k: ByteArray, cards: Int) {
        blob = out
        key = k
        cardCount = cards
        val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.let { Pack.hex(it) }
        val ip = LanServer.localIp()
        if (ip != null) {
            try {
                val s = LanServer(out, token)
                s.start()
                server = s
                lanUrl = "http://$ip:${s.port}/$token"
            } catch (e: Exception) {
                lanUrl = null
            }
        }
        val net = cbNet.isChecked
        if (lanUrl == null && !net) {
            setBusy(false)
            status.text = "Нет Wi‑Fi. Подключись к сети или включи галочку «через интернет»."
            return
        }
        if (lanUrl != null) showQr()
        if (net) {
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = 0
            if (lanUrl == null) status.text = "Нет локальной сети. Загружаю в интернет, QR появится после загрузки…"
            Thread {
                try {
                    val url = Relay.upload(out) { sent, total ->
                        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                        runOnUiThread { progress.progress = pct; relayNote = "Загрузка в интернет: $pct%"; if (lanUrl != null) updateStatus() }
                    }
                    runOnUiThread { relayUrl = url; relayNote = "через интернет: готово"; setBusy(false); showQr() }
                } catch (e: Exception) {
                    runOnUiThread {
                        relayNote = "через интернет не вышло (${e.message?.take(80)})"
                        setBusy(false)
                        if (lanUrl != null) updateStatus() else status.text = "Не удалось загрузить в интернет: ${e.message}"
                    }
                }
            }.start()
        } else {
            setBusy(false)
        }
    }

    private fun updateStatus() {
        val lan = if (lanUrl != null) "по Wi‑Fi: готово" else "по Wi‑Fi: нет сети"
        status.text = "На втором устройстве нажми «Получить» и наведи камеру.\n• $lan\n• ${relayNote.ifEmpty { "через интернет: выключено" }}\n" +
            "Карточек: $cardCount. Не закрывай этот экран, пока идёт передача."
    }

    private fun showQr() {
        val b = blob ?: return
        val text = Pack.Payload(key, lanUrl, relayUrl, cardCount, b.length()).encode()
        val bmp = makeQr(text, 720)
        qrView.setImageBitmap(bmp)
        qrView.visibility = View.VISIBLE
        updateStatus()
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ================= получение =================

    private fun receive(p: Pack.Payload) {
        if (busy) return
        server?.stop(); server = null
        qrView.visibility = View.GONE
        setBusy(true)
        status.text = "Подключаюсь к отправителю…"
        Thread {
            val tmp = File(cacheDir, "fcat_in.bin")
            val stage = File(cacheDir, "fcat_stage")
            try {
                stage.deleteRecursively(); stage.mkdirs()
                var via = ""
                var err = ""
                var ok = false
                if (p.lan != null) {
                    try {
                        runOnUiThread { status.text = "Пробую напрямую по Wi‑Fi…" }
                        Relay.download(p.lan, tmp, 2500) { got, total -> progressUi(got, total) }
                        ok = true; via = "по Wi‑Fi"
                    } catch (e: Exception) {
                        err = "Wi‑Fi: ${e.message}"
                    }
                }
                if (!ok && p.relay != null) {
                    try {
                        runOnUiThread { status.text = "Скачиваю через интернет…" }
                        Relay.download(p.relay, tmp, 20000) { got, total -> progressUi(got, total) }
                        ok = true; via = "через интернет"
                    } catch (e: Exception) {
                        err += (if (err.isEmpty()) "" else "; ") + "интернет: ${e.message}"
                    }
                }
                if (!ok) throw java.io.IOException(err.ifEmpty { "нет способа подключиться" })
                runOnUiThread { status.text = "Расшифровываю ($via)…"; progress.isIndeterminate = true }
                val cards = tmp.inputStream().buffered().use { Pack.unzipTo(Pack.DecryptStream(it, p.key), stage) }
                tmp.delete()
                if (cards.isEmpty()) throw java.io.IOException("в передаче нет карточек")
                val existing = Storage.list(this).map { it.name }.toSet()
                val clash = cards.filter { it in existing }
                runOnUiThread { askImport(cards, clash, stage) }
            } catch (e: Exception) {
                tmp.delete(); stage.deleteRecursively()
                runOnUiThread {
                    setBusy(false)
                    status.text = "Не получилось: ${e.message}"
                }
            }
        }.start()
    }

    private fun progressUi(got: Long, total: Long) {
        runOnUiThread {
            if (total > 0) {
                progress.isIndeterminate = false
                progress.max = 100
                progress.progress = (got * 100 / total).toInt()
            }
            status.text = "Скачано ${got / 1024 / 1024} МБ" + if (total > 0) " из ${total / 1024 / 1024}" else ""
        }
    }

    private fun askImport(cards: List<String>, clash: List<String>, stage: File) {
        if (clash.isEmpty()) {
            doImport(cards, 2, stage)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Карточки с такими именами уже есть (${clash.size})")
            .setMessage(clash.take(8).joinToString("\n") + if (clash.size > 8) "\n…" else "")
            .setCancelable(false)
            .setPositiveButton("Оставить обе") { _, _ -> doImport(cards, 2, stage) }
            .setNeutralButton("Заменить") { _, _ -> doImport(cards, 1, stage) }
            .setNegativeButton("Пропустить") { _, _ -> doImport(cards, 0, stage) }
            .show()
    }

    private fun doImport(cards: List<String>, mode: Int, stage: File) {
        status.text = "Сохраняю в каталог…"
        Thread {
            var added = 0
            var err: String? = null
            for (c in cards) {
                val dir = File(stage, c)
                val existed = Storage.find(this, Storage.sanitize(c)) != null
                val e = Storage.importCard(this, dir, c, mode)
                if (e != null) { err = e; break }
                if (!(existed && mode == 0)) added++
            }
            stage.deleteRecursively()
            runOnUiThread {
                setBusy(false)
                status.text = if (err != null) "Остановились на ошибке: $err" else "Готово: добавлено карточек: $added из ${cards.size}."
            }
        }.start()
    }
}
