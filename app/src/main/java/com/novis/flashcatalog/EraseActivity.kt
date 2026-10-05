package com.novis.flashcatalog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
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
        const val RESULT_PHOTO = "photo"
        const val RESULT_CUT = "cut"
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
    private var th = 0.5f
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

        btnOk.isEnabled = false
        Thread {
            val full = Images.decodeFile(path, WORK)
            if (full == null) {
                runOnUiThread { Toast.makeText(this, "Не удалось открыть фото", Toast.LENGTH_LONG).show(); finish() }
                return@Thread
            }
            val sc = min(1f, WORK.toFloat() / max(full.width, full.height))
            val work = if (sc < 1f) Bitmap.createScaledBitmap(full, (full.width * sc).toInt(), (full.height * sc).toInt(), true) else full
            val seg = Images.segmentObject(work)
            runOnUiThread { setup(work, seg) }
        }.start()

        btnErase.setOnClickListener { eraseMode = true; updateModeButtons() }
        btnRestore.setOnClickListener { eraseMode = false; updateModeButtons() }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { undoLast() }
        findViewById<Button>(R.id.btnFit).setOnClickListener { view.resetView() }
        btnTransp.setOnClickListener {
            transp = !transp
            btnTransp.text = "Прозрачный фон: " + if (transp) "вкл" else "выкл"
            renderAll()
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            File(path).delete()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        btnOk.setOnClickListener { finishWork() }

        findViewById<SeekBar>(R.id.seekBrush).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                view.brushRadius = 4f + p * 0.8f
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        view.brushRadius = 4f + 25 * 0.8f
        findViewById<SeekBar>(R.id.seekEdge).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                // вправо — больше захватываем (меньше фона съедаем), влево — меньше (убираем тень)
                th = 0.92f - 0.84f * p / 100f
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
        if (seg != null) {
            soft = buildSoft(seg)
            hint.text = "Фон найден. Зумь щипком, стирай остатки тени кистью «Стереть», ошибки исправляй «Вернуть»."
        } else {
            soft = null
            hint.text = "Фон сам не нашёлся — стирай его вручную кистью «Стереть» (зумь щипком для точности)."
        }
        ready = true
        btnOk.isEnabled = true
        renderAll()
        view.setBitmap(dispBmp)
    }

    /** Автомаска → мягкая карта 0..1 на рабочем разрешении (для ползунка «Край фона»). */
    private fun buildSoft(seg: Images.Seg): FloatArray {
        val sw = seg.sw
        val sh = seg.sh
        val a = FloatArray(sw * sh) { if (seg.keep[it]) 1f else 0f }
        val tmp = FloatArray(sw * sh)
        repeat(2) {
            Images.boxBlurH(a, tmp, sw, sh, 3)
            Images.boxBlurV(tmp, a, sw, sh, 3)
        }
        val out = FloatArray(w * h)
        val fx = sw.toFloat() / w
        val fy = sh.toFloat() / h
        for (y in 0 until h) {
            val gy = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = gy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = gy - y0
            for (x in 0 until w) {
                val gx = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = gx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = gx - x0
                val top = a[y0 * sw + x0] * (1 - tx) + a[y0 * sw + x1] * tx
                val bot = a[y1 * sw + x0] * (1 - tx) + a[y1 * sw + x1] * tx
                out[y * w + x] = top * (1 - ty) + bot * ty
            }
        }
        return out
    }

    private fun alphaAt(i: Int): Int {
        if (!transp) return 255
        val m = manual[i].toInt()
        if (m == 1) return 0
        if (m == 2) return 255
        val s = soft ?: return 255
        return (((s[i] - th) / 0.12f + 0.5f).coerceIn(0f, 1f) * 255f).toInt()
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

    // ---- результат в полном разрешении ----

    private fun finishWork() {
        btnOk.isEnabled = false
        hint.text = "Сохраняю в полном размере…"
        val wantCut = transp
        Thread {
            var photoOut: File? = null
            var cutOut: File? = null
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
                    setResult(Activity.RESULT_OK, res)
                    finish()
                }
            }
        }.start()
    }
}
