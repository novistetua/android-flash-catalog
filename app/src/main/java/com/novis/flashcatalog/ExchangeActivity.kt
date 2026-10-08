package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
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
        const val EXTRA_PAYLOAD = "payload"
    }

    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var cbNet: CheckBox
    private lateinit var cbBrowser: CheckBox
    private lateinit var qrView: ImageView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    private var server: LanServer? = null
    private var blob: File? = null
    private var key: ByteArray = ByteArray(0)
    private var lanUrl: String? = null
    private var relayUrl: String? = null
    private var relayNote: String = ""
    private var webUrl: String? = null
    private var lastNames: List<String>? = null
    private var cardCount = 0
    private var busy = false
    private lateinit var btnCrocSend: Button
    private lateinit var btnCrocRecv: Button
    private lateinit var btnCrocCancel: Button
    private lateinit var btnCrocShare: Button
    private val croc by lazy { CrocRunner(this) }

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringExtra(ScanActivity.RESULT_TEXT)
        if (r.resultCode == Activity.RESULT_OK && t != null) handleScanned(t)
    }

    /** Текст из QR или из ссылки браузера: croc-код или обычный обмен. */
    private fun handleScanned(t: String) {
        if (Storage.getRoot(this) == null) { toast("Сначала выбери папку каталога в «Настройках»"); return }
        val cc = CrocRunner.parseQr(t)
        if (cc != null) { crocReceive(cc); return }
        val p = Pack.Payload.parse(t)
        if (p == null) toast("Это не QR от Flash Catalog") else receive(p)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exchange)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        btnSend = findViewById(R.id.btnSend)
        findViewById<TextView>(R.id.status).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { recvText?.text = e?.toString() }
        })
        btnReceive = findViewById(R.id.btnReceive)
        cbNet = findViewById(R.id.cbNet)
        cbBrowser = findViewById(R.id.cbBrowser)
        qrView = findViewById(R.id.qrView)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        btnSend.setOnClickListener { chooseWhat() }
        btnReceive.setOnClickListener {
            if (Storage.getRoot(this) == null) toast("Сначала выбери папку каталога") else
                scanner.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<Button>(R.id.btnWeb).setOnClickListener { chooseWeb() }
        btnCrocSend = findViewById(R.id.btnCrocSend)
        btnCrocRecv = findViewById(R.id.btnCrocRecv)
        btnCrocCancel = findViewById(R.id.btnCrocCancel)
        btnCrocSend.setOnClickListener { chooseWhat { names -> crocSend(names) } }
        btnCrocRecv.setOnClickListener {
            if (Storage.getRoot(this) == null) toast("Сначала выбери папку каталога") else scanner.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<Button>(R.id.btnCrocCode).setOnClickListener { crocAskCode() }
        btnCrocShare = findViewById(R.id.btnCrocShare)
        btnCrocShare.setOnClickListener {
            val c = CrocSendService.state?.code ?: return@setOnClickListener
            val t = "Код для получения карточек Flash Catalog через croc: $c\n(в приложении: «QR» → «croc: ввести код вручную»; на компьютере: croc $c)"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Отправить код"))
        }
        btnCrocCancel.setOnClickListener {
            if (CrocSendService.active) CrocSendService.stop(this) else croc.cancel()
        }
        findViewById<Button>(R.id.btnWebStop).setOnClickListener {
            ShareService.stop(this)
            status.text = "Веб-сервер остановлен."
        }
        intent.getStringExtra(EXTRA_PAYLOAD)?.let { p -> window.decorView.post { handleScanned(p) } }
        val folders = intent.getStringArrayExtra(EXTRA_FOLDERS)
        if (folders != null && folders.isNotEmpty()) startSend(folders.toList())
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        blob?.delete()
        croc.cancel()
    }

    @Volatile private var crocActive = false

    // ================= croc =================

    private fun crocSend(names: List<String>?) {
        if (CrocSendService.active) { toast("Передача через croc уже идёт"); return }
        if (croc.binary() == null) { toast("croc недоступен в этой сборке"); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4712)
        }
        CrocSendService.state = null
        shownCode = null
        val i = Intent(this, CrocSendService::class.java)
        if (names != null) i.putExtra(CrocSendService.EXTRA_NAMES, names.toTypedArray())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        status.text = "Готовлю карточки…"
    }

    private var shownCode: String? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshServices()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(poll)
    }

    /** Показывает состояние фоновых служб: веб-сервер и отправка через croc. */
    private fun refreshServices() {
        val webOn = ShareService.server != null
        findViewById<Button>(R.id.btnWebStop).visibility = if (webOn) View.VISIBLE else View.GONE
        val st = CrocSendService.state
        val sendActive = st != null && !st.done
        btnCrocCancel.visibility = if (sendActive || (busy && crocActive)) View.VISIBLE else View.GONE
        btnCrocSend.isEnabled = !busy && !sendActive
        if (st == null) {
            if (shownCode != null) { shownCode = null; btnCrocShare.visibility = View.GONE }
            return
        }
        if (busy) return
        if (!st.done) {
            if (shownCode != st.code) {
                qrView.setImageBitmap(makeQr(CrocRunner.qrText(st.code), 720))
                shownCode = st.code
            }
            qrView.visibility = View.VISIBLE
            btnCrocShare.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            if (st.pct in 0..100) { progress.isIndeterminate = false; progress.max = 100; progress.progress = st.pct } else progress.isIndeterminate = true
            status.text = "Код: ${st.code} (карточек: ${st.cards})\n${st.phase}\n" +
                "Получатель нажимает «croc: получить» и наводит камеру на QR, код вводить не нужно. Можно выйти из этого экрана: отправка продолжится в фоне, статус виден сверху.\n\n" +
                st.log.takeLast(3).joinToString("\n")
        } else {
            qrView.visibility = View.GONE
            btnCrocShare.visibility = View.GONE
            progress.visibility = View.GONE
            status.text = st.phase + (if (!st.ok && !st.stopped) "\n" + st.log.takeLast(6).joinToString("\n") else "")
            CrocSendService.state = null
            shownCode = null
        }
    }

    private fun crocLine(line: String, log: ArrayList<String>) {
        if (line.isBlank()) return
        log.add(line.take(400))
        val pct = CrocRunner.percent(line)
        runOnUiThread {
            if (pct != null) {
                progress.isIndeterminate = false
                progress.max = 100
                progress.progress = pct
            }
            val base = status.text.toString().substringBefore("\n\n")
            status.text = base + "\n\n" + log.takeLast(4).joinToString("\n")
        }
    }

    private fun crocAskCode() {
        if (Storage.getRoot(this) == null) { toast("Сначала выбери папку каталога"); return }
        val et = android.widget.EditText(this)
        et.hint = "например 4821-river-tiger-honey"
        et.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Код croc")
            .setView(et)
            .setPositiveButton("Получить") { _, _ ->
                val c = et.text.toString().trim()
                if (c.length < 6) toast("Код слишком короткий") else crocReceive(c)
            }
                        .setNegativeButton("Отмена", null)
            .show()
    }

    private fun crocReceive(code: String) {
        if (busy) return
        if (croc.binary() == null) { toast("croc недоступен в этой сборке"); return }
        if (Storage.getRoot(this) == null) { toast("Сначала выбери папку каталога"); return }
        qrView.visibility = View.GONE
        crocActive = true
        setBusy(true)
        status.text = "Подключаюсь через croc…\n\n"
        beginDialog("Получаю через croc", true)
        Thread {
            val dir = File(cacheDir, "croc_recv")
            val stage = File(cacheDir, "fcat_stage")
            try {
                dir.deleteRecursively(); dir.mkdirs()
                stage.deleteRecursively(); stage.mkdirs()
                val log = ArrayList<String>()
                val rc = croc.run(listOf("--yes", "--overwrite", "--out", dir.absolutePath), code, dir) { line -> crocLine(line, log) }
                if (rc != 0) throw java.io.IOException("croc завершился с кодом $rc\n" + log.takeLast(6).joinToString("\n"))
                // zip из Flash Catalog и/или папки с карточками (например, отправленные с компьютера)
                for (f in dir.walkTopDown()) {
                    if (f.isFile && f.name.endsWith(".zip")) f.inputStream().buffered().use { Pack.unzipTo(it, stage) }
                }
                for (d in dir.listFiles() ?: emptyArray()) {
                    if (d.isDirectory && !d.name.startsWith(".")) d.copyRecursively(File(stage, d.name), true)
                }
                val cards = (stage.listFiles() ?: emptyArray()).filter { it.isDirectory }.map { it.name }.sorted()
                if (cards.isEmpty()) throw java.io.IOException("в полученном нет карточек")
                val existing = Storage.list(this).map { it.name }.toSet()
                val clash = cards.filter { it in existing }
                runOnUiThread { crocActive = false; askImport(cards, clash, stage) }
            } catch (e: Exception) {
                stage.deleteRecursively()
                runOnUiThread { setBusy(false); crocActive = false; status.text = "croc: ${e.message}"; endDialog(false, "croc: ${e.message}") }
            } finally {
                dir.deleteRecursively()
            }
        }.start()
    }

    // ---- окно приёма: чтобы получатель сразу видел, что идёт, и чем всё кончилось ----
    private var recvDlg: AlertDialog? = null
    private var recvText: TextView? = null

    private fun beginDialog(title: String, cancellable: Boolean) {
        recvDlg?.dismiss()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        val tv = TextView(this)
        tv.text = status.text
        tv.textSize = 14f
        box.addView(tv)
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.isIndeterminate = true
        box.addView(pb)
        recvText = tv
        val b = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false)
        if (cancellable) b.setNegativeButton("Отмена") { _, _ -> croc.cancel() }
        recvDlg = b.show()
    }

    private fun endDialog(ok: Boolean, msg: String) {
        recvDlg?.dismiss()
        recvDlg = null
        recvText = null
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(if (ok) "Получено" else "Не получилось").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun setBusy(b: Boolean) {
        busy = b
        btnSend.isEnabled = !b
        btnReceive.isEnabled = !b
        btnCrocSend.isEnabled = !b
        btnCrocRecv.isEnabled = !b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        if (b) progress.isIndeterminate = true
    }

    // ================= отправка =================

    private fun chooseWhat(cb: (List<String>?) -> Unit = { startSend(it) }) {
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
                        if (w == 0) cb(null) else pickCards(names, cb)
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

    private fun pickCards(names: List<String>, cb: (List<String>?) -> Unit) {
        val checked = BooleanArray(names.size)
        AlertDialog.Builder(this)
            .setTitle("Какие карточки?")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Отправить") { _, _ ->
                val sel = names.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) toast("Ничего не выбрано") else cb(sel)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startSend(names: List<String>?) {
        if (busy) return
        server?.stop(); server = null
        blob?.delete()
        qrView.visibility = View.GONE
        lanUrl = null; relayUrl = null; relayNote = ""; webUrl = null
        lastNames = names
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
        if (cbBrowser.isChecked) {
            WebShareUi.ensure(this, lastNames) { url ->
                if (url != null && !isDestroyed) { webUrl = url; if (lanUrl != null || relayUrl != null) showQr() }
            }
        }
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
        val payload = Pack.Payload(key, lanUrl, relayUrl, cardCount, b.length()).encode()
        // если включена веб-раздача, QR это ссылка на её страницу, данные для приложения лежат после «#»
        val text = if (webUrl != null) webUrl + "#" + payload else payload
        val bmp = makeQr(text, 720)
        qrView.setImageBitmap(bmp)
        qrView.visibility = View.VISIBLE
        updateStatus()
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
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
        beginDialog("Получаю карточки", false)
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
                    endDialog(false, "${e.message}")
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
        recvDlg?.dismiss(); recvDlg = null; recvText = null
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
        beginDialog("Сохраняю в каталог", false)
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
                endDialog(err == null, status.text.toString())
            }
        }.start()
    }
}
