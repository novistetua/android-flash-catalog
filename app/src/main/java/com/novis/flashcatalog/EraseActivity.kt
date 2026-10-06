package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Ручная правка прозрачного фона и яркости.
 * Автомаска показывается сразу; «Стереть» делает область прозрачной, «Вернуть» возвращает флешку.
 */
class EraseActivity : AppCompatActivity(), EraseView.Listener {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NO_CUT = "no_cut"
        const val EXTRA_AUTO_AI = "auto_ai"
        const val RESULT_PHOTO = "photo"
        const val RESULT_CUT = "cut"
        const val EXTRA_PREV_MASK = "prev_mask"
        const val EXTRA_PREV_ROT = "prev_rot"
        const val RESULT_MASK = "mask"
        private const val WORK = 1200
        private const val MAX_UNDO = 25
    }

    private lateinit var view: EraseView
    private lateinit var btnErase: Button
    private lateinit var btnRestore: Button
    private lateinit var btnTransp: Button
    private lateinit var btnOk: Button
    private lateinit var hint: TextView

    private var path = ""
    private var w = 0
    private var h = 0
    private lateinit var src: IntArray        // исходные пиксели рабочей копии
    private lateinit var disp: IntArray       // то, что показано
    private lateinit var dispBmp: Bitmap
    private lateinit var manual: ByteArray    // 0 — авто, 1 — стёрто, 2 — возвращено
    private var soft: FloatArray? = null      // сглаженная автомаска 0..1
    private val undo = ArrayList<ByteArray>()

    private var eraseMode = true
    private var transp = true
    private var edgeOff = 0f   // смещение границы автофона в пикселях рабочей копии: + наружу, − внутрь
    private var gamma = 1.0
    private var lut = IntArray(256) { it }
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_erase)
        view = findViewById(R.id.eraseView)
        btnErase = findViewById(R.id.btnErase)
        btnRestore = findViewById(R.id.btnRestore)
        btnTransp = findViewById(R.id.btnTransp)
        btnOk = findViewById(R.id.btnOk)
        hint = findViewById(R.id.hint)
        view.listener = this
        path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val noCut = intent.getBooleanExtra(EXTRA_NO_CUT, false)
        if (noCut) {
            transp = false
            view.brushEnabled = false
            for (id in intArrayOf(R.id.btnErase, R.id.btnRestore, R.id.btnUndo, R.id.btnTransp, R.id.btnOffset, R.id.btnAi)) findViewById<View>(id).visibility = View.GONE
            (findViewById<View>(R.id.seekEdge).parent as View).visibility = View.GONE
            (findViewById<View>(R.id.seekBrush).parent as View).visibility = View.GONE
            hint.text = "Щипок — зум, двумя пальцами — сдвиг. Ползунок — яркость всего фото."
        }

        btnOk.isEnabled = false
        Thread {
            val full = Images.decodeFile(path, WORK)
            if (full == null) {
                runOnUiThread { Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_LONG).show(); finish() }
                return@Thread
            }
            val sc = min(1f, WORK.toFloat() / max(full.width, full.height))
            val work = if (sc < 1f) Bitmap.createScaledBitmap(full, (full.width * sc).toInt(), (full.height * sc).toInt(), true) else full
            var seg: Images.Seg? = null
            if (!noCut) {
                val pm = intent.getStringExtra(EXTRA_PREV_MASK)
                if (pm != null && File(pm).exists()) {
                    // продолжаем прошлую правку: берём сохранённую маску вместо новой сегментации
                    var mb = Images.decodeFile(pm, WORK)
                    val pr = intent.getIntExtra(EXTRA_PREV_ROT, 0)
                    if (mb != null && pr != 0) mb = Images.rotate(mb, pr)
                    if (mb != null) {
                        val ms = if (mb.width == work.width && mb.height == work.height) mb
                        else Bitmap.createScaledBitmap(mb, work.width, work.height, true)
                        val mp = IntArray(work.width * work.height)
                        ms.getPixels(mp, 0, work.width, 0, 0, work.width, work.height)
                        val keep = BooleanArray(mp.size) { ((mp[it] shr 16) and 0xFF) >= 128 }
                        if (keep.any { it }) seg = Images.Seg(keep, work.width, work.height)
                    }
                }
                if (seg == null) seg = Images.segmentObject(work)
            }
            runOnUiThread { setup(work, seg) }
        }.start()

        btnErase.setOnClickListener { eraseMode = true; updateModeButtons() }
        btnRestore.setOnClickListener { eraseMode = false; updateModeButtons() }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { undoLast() }
        findViewById<Button>(R.id.btnFit).setOnClickListener { view.resetView() }
        btnTransp.setOnClickListener {
            transp = !transp
            btnTransp.text = "Авто-фон: " + if (transp) "вкл" else "выкл"
            renderAll()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            File(path).delete()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        btnOk.setOnClickListener { finishWork() }
        findViewById<Button>(R.id.btnAi).setOnClickListener { runAi() }
        findViewById<Button>(R.id.btnAi).setOnLongClickListener { showAiSettings(); true }

        findViewById<SeekBar>(R.id.seekBrush).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                view.brushRadius = 3f + p * 1.0f
                findViewById<TextView>(R.id.labelBrush).text = "Диаметр ластика: " + Math.round(view.brushRadius * 2)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        view.brushRadius = 3f + 18 * 1.0f
        findViewById<TextView>(R.id.labelBrush).text = "Диаметр ластика: " + Math.round(view.brushRadius * 2)
        val prefs = getSharedPreferences("fc", MODE_PRIVATE)
        view.offsetDir = prefs.getInt("erase_offset", 1)
        val btnOffset = findViewById<Button>(R.id.btnOffset)
        fun offsetText() {
            btnOffset.text = when (view.offsetDir) {
                1 -> "Кисть выше пальца"
                -1 -> "Кисть ниже пальца"
                else -> "Кисть под пальцем"
            }
        }
        offsetText()
        btnOffset.setOnClickListener {
            view.offsetDir = when (view.offsetDir) { 1 -> -1; -1 -> 0; else -> 1 }
            prefs.edit().putInt("erase_offset", view.offsetDir).apply()
            offsetText()
        }
        findViewById<SeekBar>(R.id.seekEdge).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                // вправо — больше захватываем (меньше фона съедаем), влево — меньше (убираем тень)
                edgeOff = (p - 50) * 0.8f
                val sign = if (p > 50) "+" else ""
                findViewById<TextView>(R.id.labelEdge).text = "Край фона: " + sign + Math.round(edgeOff)
                if (user) renderAll()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        findViewById<SeekBar>(R.id.seekBright).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                setBrightness(p)
                if (user) renderAll()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        updateModeButtons()
    }

    private fun updateModeButtons() {
        btnErase.alpha = if (eraseMode) 1f else 0.5f
        btnRestore.alpha = if (eraseMode) 0.5f else 1f
    }

    /** 50 — без изменений; влево темнее, вправо светлее (гамма, чтобы не «выжигать» белое и чёрное). */
    private fun setBrightness(p: Int) {
        gamma = 2.0.pow(-(p - 50) / 30.0)
        lut = IntArray(256) { (255.0 * (it / 255.0).pow(gamma)).toInt().coerceIn(0, 255) }
    }

    private fun setup(work: Bitmap, seg: Images.Seg?) {
        w = work.width
        h = work.height
        src = IntArray(w * h)
        work.getPixels(src, 0, w, 0, 0, w, h)
        disp = IntArray(w * h)
        dispBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        manual = ByteArray(w * h)
        if (intent.getBooleanExtra(EXTRA_NO_CUT, false)) {
            soft = null
        } else if (seg != null) {
            soft = buildSoft(seg)
            hint.text = "Фон найден. Зумь щипком. Палец ведёт пунктирный кружок, настоящий ластик — сплошной круг рядом с ним."
        } else {
            soft = null
            hint.text = "Фон сам не нашёлся — стирай его вручную кистью «Стереть» (зумь щипком для точности)."
        }
        btnTransp.isEnabled = soft != null
        if (soft == null) findViewById<View>(R.id.seekEdge).isEnabled = false
        ready = true
        btnOk.isEnabled = true
        renderAll()
        view.setBitmap(dispBmp)
        if (intent.getBooleanExtra(EXTRA_AUTO_AI, false)) runAi()
    }

    /** Расстояние до границы (метод цепочек): >0 внутри флешки, <0 снаружи; в пикселях сегментации. */
    private fun chamfer(target: BooleanArray, sw: Int, sh: Int): FloatArray {
        val inf = 1e6f
        val d = FloatArray(sw * sh) { if (target[it]) 0f else inf }
        val dg = 1.4142f
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = y * sw + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) {
                v = min(v, d[i - sw] + 1f)
                if (x > 0) v = min(v, d[i - sw - 1] + dg)
                if (x < sw - 1) v = min(v, d[i - sw + 1] + dg)
            }
            d[i] = v
        }
        for (y in sh - 1 downTo 0) for (x in sw - 1 downTo 0) {
            val i = y * sw + x
            var v = d[i]
            if (x < sw - 1) v = min(v, d[i + 1] + 1f)
            if (y < sh - 1) {
                v = min(v, d[i + sw] + 1f)
                if (x < sw - 1) v = min(v, d[i + sw + 1] + dg)
                if (x > 0) v = min(v, d[i + sw - 1] + dg)
            }
            d[i] = v
        }
        return d
    }

    /** Автомаска → карта «расстояние до края» на рабочем разрешении, в рабочих пикселях (для ползунка «Край фона»). */
    private fun buildSoft(seg: Images.Seg): FloatArray {
        val sw = seg.sw
        val sh = seg.sh
        val outside = BooleanArray(sw * sh) { !seg.keep[it] }
        val dIn = chamfer(outside, sw, sh)        // для пикселей флешки: расстояние до фона
        val dOut = chamfer(seg.keep, sw, sh)      // для пикселей фона: расстояние до флешки
        val a = FloatArray(sw * sh) {
            if (seg.keep[it]) min(dIn[it], 5000f) - 0.5f else -(min(dOut[it], 5000f) - 0.5f)
        }
        val out = FloatArray(w * h)
        val fx = sw.toFloat() / w
        val fy = sh.toFloat() / h
        val toWork = w.toFloat() / sw
        for (y in 0 until h) {
            val gy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = gy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = gy - y0
            for (x in 0 until w) {
                val gx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = gx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = gx - x0
                val top = a[y0 * sw + x0] * (1 - tx) + a[y0 * sw + x1] * tx
                val bot = a[y1 * sw + x0] * (1 - tx) + a[y1 * sw + x1] * tx
                out[y * w + x] = (top * (1 - ty) + bot * ty) * toWork
            }
        }
        return out
    }

    private fun alphaAt(i: Int): Int {
        val m = manual[i].toInt()
        if (m == 1) return 0
        if (m == 2) return 255
        if (!transp) return 255
        val sd = soft ?: return 255
        return (((sd[i] + edgeOff) / 1.5f + 0.5f).coerceIn(0f, 1f) * 255f).toInt()
    }

    private fun pixel(i: Int): Int {
        val p = src[i]
        val r = lut[(p shr 16) and 0xFF]
        val g = lut[(p shr 8) and 0xFF]
        val b = lut[p and 0xFF]
        return (alphaAt(i) shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun renderAll() {
        if (!ready) return
        for (i in 0 until w * h) disp[i] = pixel(i)
        dispBmp.setPixels(disp, 0, w, 0, 0, w, h)
        view.invalidate()
        if (view.width > 0) view.setBitmap(dispBmp)
    }

    private fun renderRect(l: Int, t: Int, r: Int, b: Int) {
        val rw = r - l
        val rh = b - t
        if (rw <= 0 || rh <= 0) return
        for (y in t until b) for (x in l until r) disp[y * w + x] = pixel(y * w + x)
        dispBmp.setPixels(disp, t * w + l, w, l, t, rw, rh)
        view.invalidate()
    }

    // ---- кисть ----

    override fun onStrokeStart() {
        if (!ready) return
        undo.add(manual.copyOf())
        if (undo.size > MAX_UNDO) undo.removeAt(0)
        lastX = Float.NaN
    }

    private var lastX = Float.NaN
    private var lastY = 0f

    override fun onStroke(x: Float, y: Float) {
        if (!ready) return
        if (!lastX.isNaN()) {
            // заполняем промежуток между точками, чтобы линия была сплошной
            val dx = x - lastX
            val dy = y - lastY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val step = max(1f, view.brushRadius * 0.4f)
            var d = step
            while (d < dist) {
                dab(lastX + dx * d / dist, lastY + dy * d / dist)
                d += step
            }
        }
        dab(x, y)
        lastX = x
        lastY = y
    }

    private fun dab(cx: Float, cy: Float) {
        val r = view.brushRadius
        val l = max(0, (cx - r).toInt())
        val t = max(0, (cy - r).toInt())
        val rr = min(w, (cx + r).toInt() + 2)
        val bb = min(h, (cy + r).toInt() + 2)
        val v: Byte = if (eraseMode) 1 else 2
        val r2 = r * r
        for (y in t until bb) for (x in l until rr) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r2) manual[y * w + x] = v
        }
        renderRect(l, t, rr, bb)
    }

    override fun onStrokeEnd() {}

    private fun undoLast() {
        if (undo.isEmpty()) {
            Toast.makeText(this, "Отменять нечего", Toast.LENGTH_SHORT).show()
            return
        }
        val prev = undo.removeAt(undo.size - 1)
        System.arraycopy(prev, 0, manual, 0, manual.size)
        renderAll()
    }

    // ---- ИИ-маска онлайн ----

    private fun runAi() {
        if (!ready) return
        val prefs = getSharedPreferences("fc", MODE_PRIVATE)
        if (prefs.getBoolean("ai_ok", false)) {
            doAi()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("ИИ-вырезание онлайн")
            .setMessage(
                "Фото (уменьшенное) уйдёт на бесплатный публичный сервис Hugging Face (модели BiRefNet / RMBG).\n\n" +
                    "Сервис вернёт только контур. Фон вырезаю я сам, на телефоне, из твоего оригинала, поэтому нарисовать или " +
                    "изменить что-то на флешке он не может.\n\nНе отправляй личные снимки."
            )
            .setPositiveButton("Отправить") { _, _ ->
                prefs.edit().putBoolean("ai_ok", true).apply()
                doAi()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Настройки") { _, _ -> showAiSettings() }
            .show()
    }

    /** Личные настройки ИИ: токен Hugging Face, свой Space, ключ remove.bg. */
    private fun showAiSettings() {
        val prefs = getSharedPreferences("fc", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        fun field(label: String, hintText: String, key: String): android.widget.EditText {
            val t = TextView(this)
            t.text = label
            t.setPadding(0, pad / 2, 0, 0)
            box.addView(t)
            val e = android.widget.EditText(this)
            e.setSingleLine(true)
            e.hint = hintText
            e.setText(prefs.getString(key, ""))
            box.addView(e)
            return e
        }
        val tok = field("Токен Hugging Face (бесплатный аккаунт даёт 5 минут GPU в день вместо 2)", "hf_…", "hf_token")
        val own = field("Свой Space (копия сервиса на твоём аккаунте, без лимитов)", "https://имя-пространство.hf.space", "own_space")
        val rb = field("Ключ remove.bg (50 бесплатных обработок в месяц)", "ключ API", "removebg_key")
        AlertDialog.Builder(this)
            .setTitle("Настройки ИИ-вырезания")
            .setView(android.widget.ScrollView(this).also { it.addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit()
                    .putString("hf_token", tok.text.toString().trim())
                    .putString("own_space", own.text.toString().trim())
                    .putString("removebg_key", rb.text.toString().trim())
                    .apply()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doAi() {
        val sp = getSharedPreferences("fc", MODE_PRIVATE)
        AiMask.hfToken = sp.getString("hf_token", "") ?: ""
        AiMask.ownSpace = sp.getString("own_space", "") ?: ""
        AiMask.removeBgKey = sp.getString("removebg_key", "") ?: ""
        val btn = findViewById<Button>(R.id.btnAi)
        btn.isEnabled = false
        btnOk.isEnabled = false
        hint.text = "ИИ: готовлю фото…"
        Thread {
            var err: String? = null
            var keep: BooleanArray? = null
            try {
                val bmp = Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                val png = AiMask.requestCutout(bos.toByteArray()) { m -> runOnUiThread { hint.text = "ИИ: $m" } }
                val res = BitmapFactory.decodeByteArray(png, 0, png.size) ?: throw java.io.IOException("не удалось прочитать ответ сервиса")
                val ra = res.width.toFloat() / res.height
                val oa = w.toFloat() / h
                if (kotlin.math.abs(ra / oa - 1f) > 0.04f) throw java.io.IOException("сервис вернул картинку другого размера")
                val scaled = if (res.width == w && res.height == h) res else Bitmap.createScaledBitmap(res, w, h, true)
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                val k = BooleanArray(w * h) { (px[it] ushr 24) > 127 }
                val frac = k.count { it }.toDouble() / k.size
                if (frac < 0.02 || frac > 0.97) throw java.io.IOException("контур от сервиса выглядит неправдоподобно")
                keep = k
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val kf = keep
            val ef = err
            runOnUiThread {
                btn.isEnabled = true
                btnOk.isEnabled = true
                if (kf == null) {
                    hint.text = "ИИ не помог."
                    AlertDialog.Builder(this)
                        .setTitle("ИИ-вырезание не получилось")
                        .setMessage("$ef\n\nМожно повторить позже или стереть фон вручную кистью.")
                        .setPositiveButton("Понятно", null)
                        .setNeutralButton("Настройки ИИ") { _, _ -> showAiSettings() }
                        .show()
                } else {
                    soft = buildSoft(Images.Seg(kf, w, h))
                    transp = true
                    btnTransp.text = "Авто-фон: вкл"
                    btnTransp.isEnabled = true
                    findViewById<View>(R.id.seekEdge).isEnabled = true
                    hint.text = "ИИ-контур применён. Подправь края кистью «Стереть» / «Вернуть» и ползунком «Край фона»."
                    renderAll()
                }
            }
        }.start()
    }

    // ---- результат в полном разрешении ----

    private fun finishWork() {
        btnOk.isEnabled = false
        hint.text = "Сохраняю в полном размере…"
        val wantCut = (transp && soft != null) || manual.any { it.toInt() == 1 }
        Thread {
            var photoOut: File? = null
            var cutOut: File? = null
            var maskOut: File? = null
            var err: String? = null
            try {
                val full = Images.decodeFile(path, 3000) ?: throw IllegalStateException("нет фото")
                val fw = full.width
                val fh = full.height
                val px = IntArray(fw * fh)
                full.getPixels(px, 0, fw, 0, 0, fw, fh)
                // карта прозрачности на рабочем разрешении
                val aw = ByteArray(w * h)
                for (i in 0 until w * h) aw[i] = alphaAt(i).toByte()
                val fxs = w.toFloat() / fw
                val fys = h.toFloat() / fh
                var minX = fw; var minY = fh; var maxX = -1; var maxY = -1
                val rgb = IntArray(fw * fh)
                for (y in 0 until fh) {
                    val gy = ((y + 0.5f) * fys - 0.5f).coerceIn(0f, h - 1f)
                    val y0 = gy.toInt(); val y1 = min(y0 + 1, h - 1); val ty = gy - y0
                    for (x in 0 until fw) {
                        val i = y * fw + x
                        val p = px[i]
                        val r = lut[(p shr 16) and 0xFF]
                        val g = lut[(p shr 8) and 0xFF]
                        val b = lut[p and 0xFF]
                        rgb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        if (wantCut) {
                            val gx = ((x + 0.5f) * fxs - 0.5f).coerceIn(0f, w - 1f)
                            val x0 = gx.toInt(); val x1 = min(x0 + 1, w - 1); val tx = gx - x0
                            val a00 = aw[y0 * w + x0].toInt() and 0xFF
                            val a10 = aw[y0 * w + x1].toInt() and 0xFF
                            val a01 = aw[y1 * w + x0].toInt() and 0xFF
                            val a11 = aw[y1 * w + x1].toInt() and 0xFF
                            val a = ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty).toInt().coerceIn(0, 255)
                            px[i] = (a shl 24) or (rgb[i] and 0xFFFFFF)
                            if (a > 20) {
                                if (x < minX) minX = x
                                if (x > maxX) maxX = x
                                if (y < minY) minY = y
                                if (y > maxY) maxY = y
                            }
                        }
                    }
                }
                val jpg = Bitmap.createBitmap(rgb, fw, fh, Bitmap.Config.ARGB_8888)
                val pf = File(cacheDir, "bright_${System.currentTimeMillis()}.jpg")
                FileOutputStream(pf).use { jpg.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                photoOut = pf
                if (wantCut) {
                    // маска рабочего размера: по ней следующая правка продолжится с этого места
                    val mpx = IntArray(w * h) { val a = aw[it].toInt() and 0xFF; (0xFF shl 24) or (a shl 16) or (a shl 8) or a }
                    val mf = File(cacheDir, "mask_${System.currentTimeMillis()}.png")
                    FileOutputStream(mf).use { Bitmap.createBitmap(mpx, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
                    maskOut = mf
                    if (maxX < 0) throw IllegalStateException("всё стёрто")
                    val cutFull = Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888)
                    val pad = max(8, (max(maxX - minX, maxY - minY) * 0.03f).toInt())
                    val cl = max(0, minX - pad)
                    val ct = max(0, minY - pad)
                    val cr = min(fw, maxX + 1 + pad)
                    val cb = min(fh, maxY + 1 + pad)
                    val cropped = Bitmap.createBitmap(cutFull, cl, ct, cr - cl, cb - ct)
                    val cf = File(cacheDir, "cut_${System.currentTimeMillis()}.png")
                    FileOutputStream(cf).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    cutOut = cf
                }
            } catch (e: Throwable) {
                err = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (err != null || photoOut == null) {
                    Toast.makeText(this, "Не получилось: $err", Toast.LENGTH_LONG).show()
                    btnOk.isEnabled = true
                } else {
                    File(path).delete()
                    val res = Intent().putExtra(RESULT_PHOTO, photoOut.absolutePath)
                    if (cutOut != null) res.putExtra(RESULT_CUT, cutOut.absolutePath)
                    if (maskOut != null) res.putExtra(RESULT_MASK, maskOut.absolutePath)
                    setResult(Activity.RESULT_OK, res)
                    finish()
                }
            }
        }.start()
    }
}
