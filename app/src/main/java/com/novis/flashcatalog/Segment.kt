package com.novis.flashcatalog

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Отделение предмета (флешки) от однотонного фона без «дорисовки»:
 * маркеры (точно фон / точно предмет) + водораздел по градиенту цвета + восстановление выпуклой формы.
 * Работает по знаку разницы яркости, поэтому годится и для тёмных, и для светлых, и для серых предметов.
 */
object Segment {

    private class Lab(val l: FloatArray, val a: FloatArray, val b: FloatArray)

    /** px — ARGB, размер w*h. Возвращает маску предмета или null. */
    fun run(px: IntArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val lab = toLab(px, total)
        gauss(lab.l, w, h); gauss(lab.a, w, h); gauss(lab.b, w, h)

        // цвет фона — медиана по полосе вдоль краёв кадра
        val bw = max(3, w / 25)
        val bh = max(3, h / 25)
        val bl = ArrayList<Float>(); val ba = ArrayList<Float>(); val bb = ArrayList<Float>()
        for (y in 0 until h) for (x in 0 until w) {
            if (y < bh || y >= h - bh || x < bw || x >= w - bw) {
                val i = y * w + x
                bl.add(lab.l[i]); ba.add(lab.a[i]); bb.add(lab.b[i])
            }
        }
        val mL = median(bl); val mA = median(ba); val mB = median(bb)
        val dL = FloatArray(total)
        val far = FloatArray(total)
        for (i in 0 until total) {
            val dl = lab.l[i] - mL
            val da = lab.a[i] - mA
            val db = lab.b[i] - mB
            dL[i] = dl
            far[i] = sqrt(dl * dl + da * da + db * db)
        }
        val edge = BooleanArray(total)
        for (y in 0 until h) for (x in 0 until w) {
            if (y < 3 || y >= h - 3 || x < 3 || x >= w - 3) edge[y * w + x] = true
        }
        val bgm = BooleanArray(total) { edge[it] && far[it] <= 24f }

        val t = max(28f, otsu(far))
        val cand = BooleanArray(total) { far[it] >= t }
        val candO = open(cand, w, h, 5)
        val comp = largest(candO, w, h) ?: return null
        var compN = 0
        for (v in comp) if (v) compN++
        if (compN < 0.015 * total) return null

        // знак контраста предмета относительно фона
        val dls = ArrayList<Float>()
        for (i in 0 until total) if (comp[i]) dls.add(dL[i])
        val sign = if (median(dls) < 0f) -1f else 1f
        val sv = FloatArray(total) { sign * dL[it] }
        val ss = ArrayList<Float>()
        for (i in 0 until total) if (comp[i]) ss.add(sv[i])
        val ts = max(14f, 0.35f * median(ss))
        val c2 = open(BooleanArray(total) { sv[it] >= ts }, w, h, 5)

        // компоненты c2, которые касаются ядра, присоединяем (серые/светлые части предмета)
        val lbl = IntArray(total)
        val n = label(c2, w, h, lbl)
        val take = BooleanArray(n + 1)
        for (i in 0 until total) if (comp[i] && lbl[i] > 0) take[lbl[i]] = true
        val fgs = BooleanArray(total)
        for (i in 0 until total) fgs[i] = comp[i] || (lbl[i] > 0 && take[lbl[i]] && !edge[i])

        val r = max(3, (0.03 * min(h, w)).toInt())
        var fg = erode(fgs, w, h, 2 * r + 1)
        var fgN = 0
        for (v in fg) if (v) fgN++
        if (fgN < 30) fg = erode(comp, w, h, 3)

        val grad = gradient(lab, w, h)
        val markers = ByteArray(total)
        for (i in 0 until total) {
            if (bgm[i]) markers[i] = 1
            if (fg[i]) markers[i] = 2
        }
        val ws = flood(grad, markers, w, h)
        val mask = BooleanArray(total) { ws[it].toInt() == 2 }
        val res = finish(mask, far, w, h) ?: return null
        return if (confident(res, grad, w, h)) res else null
    }

    /**
     * Проверка уверенности: у настоящей флешки контур плотный (заполняет свой прямоугольник) и почти весь
     * лежит на резком перепаде цвета. Если нет (белое на белом, блик, слабый контраст) — лучше честно отказаться.
     */
    private fun confident(m: BooleanArray, grad: IntArray, w: Int, h: Int): Boolean {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var area = 0
        for (i in m.indices) {
            if (!m[i]) continue
            area++
            val x = i % w; val y = i / w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < 0) return false
        val sides = (if (minX <= 1) 1 else 0) + (if (maxX >= w - 2) 1 else 0) + (if (minY <= 1) 1 else 0) + (if (maxY >= h - 2) 1 else 0)
        if (sides >= 2) return false // флешка занимает весь кадр: фон по краям неизвестен
        val frac = area.toDouble() / (w * h)
        if (frac < 0.04 || frac > 0.92) return false
        val fill = area.toDouble() / ((maxX - minX + 1).toLong() * (maxY - minY + 1))
        if (fill < 0.8) return false
        // доля граничных пикселей, рядом с которыми есть заметный градиент
        var bd = 0; var ok = 0
        for (y in 3 until h - 3) for (x in 3 until w - 3) {
            val i = y * w + x
            if (!m[i]) continue
            if (m[i - 1] && m[i + 1] && m[i - w] && m[i + w]) continue
            bd++
            var strong = false
            loop@ for (dy in -2..2) for (dx in -2..2) {
                if (grad[i + dy * w + dx] >= 40) { strong = true; break@loop }
            }
            if (strong) ok++
        }
        return bd > 0 && ok.toDouble() / bd >= 0.5
    }

    // ---------- цвет ----------

    private fun toLab(px: IntArray, total: Int): Lab {
        val lut = FloatArray(256) {
            val v = it / 255.0
            (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
        }
        fun f(t: Double): Double = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        val l = FloatArray(total); val a = FloatArray(total); val b = FloatArray(total)
        for (i in 0 until total) {
            val p = px[i]
            val rr = lut[(p shr 16) and 0xFF]
            val gg = lut[(p shr 8) and 0xFF]
            val bl = lut[p and 0xFF]
            val x = (0.4124 * rr + 0.3576 * gg + 0.1805 * bl) / 0.95047
            val y = 0.2126 * rr + 0.7152 * gg + 0.0722 * bl
            val z = (0.0193 * rr + 0.1192 * gg + 0.9505 * bl) / 1.08883
            val fx = f(x); val fy = f(y); val fz = f(z)
            l[i] = (116.0 * fy - 16.0).toFloat()
            a[i] = (500.0 * (fx - fy)).toFloat()
            b[i] = (200.0 * (fy - fz)).toFloat()
        }
        return Lab(l, a, b)
    }

    private fun gauss(a: FloatArray, w: Int, h: Int) {
        val k = floatArrayOf(0.0225f, 0.1062f, 0.2415f, 0.2596f, 0.2415f, 0.1062f, 0.0225f) // ~σ1.2 (7 точек)
        var s = 0f
        for (v in k) s += v
        for (i in k.indices) k[i] /= s
        val tmp = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (j in -3..3) acc += a[y * w + (x + j).coerceIn(0, w - 1)] * k[j + 3]
            tmp[y * w + x] = acc
        }
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f
            for (j in -3..3) acc += tmp[(y + j).coerceIn(0, h - 1) * w + x] * k[j + 3]
            a[y * w + x] = acc
        }
    }

    private fun median(l: List<Float>): Float {
        if (l.isEmpty()) return 0f
        val s = l.sorted()
        return s[s.size / 2]
    }

    private fun otsu(v: FloatArray): Float {
        val bins = 200
        val hist = IntArray(bins)
        for (x in v) {
            val b = (x / 100f * bins).toInt()
            if (b in 0 until bins) hist[b]++
        }
        var tot = 0L
        var sumAll = 0.0
        for (i in 0 until bins) { tot += hist[i]; sumAll += i.toDouble() * hist[i] }
        var best = -1.0; var th = 0
        var wb = 0L; var sb = 0.0
        for (t in 0 until bins) {
            wb += hist[t]
            if (wb == 0L) continue
            val wf = tot - wb
            if (wf == 0L) break
            sb += t.toDouble() * hist[t]
            val mb = sb / wb
            val mf = (sumAll - sb) / wf
            val vv = wb.toDouble() * wf * (mb - mf) * (mb - mf)
            if (vv > best) { best = vv; th = t }
        }
        return 100f * th / bins
    }

    // ---------- морфология (границы кадра игнорируются, как в OpenCV) ----------

    private fun boxPass(a: BooleanArray, w: Int, h: Int, rad: Int, erodeMode: Boolean): BooleanArray {
        val tmp = BooleanArray(a.size)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + if (a[y * w + x]) 1 else 0
            for (x in 0 until w) {
                val lo = max(0, x - rad); val hi = min(w - 1, x + rad)
                val c = pre[hi + 1] - pre[lo]
                tmp[y * w + x] = if (erodeMode) c == hi - lo + 1 else c > 0
            }
        }
        val out = BooleanArray(a.size)
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + if (tmp[y * w + x]) 1 else 0
            for (y in 0 until h) {
                val lo = max(0, y - rad); val hi = min(h - 1, y + rad)
                val c = pre[hi + 1] - pre[lo]
                out[y * w + x] = if (erodeMode) c == hi - lo + 1 else c > 0
            }
        }
        return out
    }

    private fun erode(a: BooleanArray, w: Int, h: Int, k: Int) = boxPass(a, w, h, k / 2, true)
    private fun dilate(a: BooleanArray, w: Int, h: Int, k: Int) = boxPass(a, w, h, k / 2, false)
    private fun open(a: BooleanArray, w: Int, h: Int, k: Int) = dilate(erode(a, w, h, k), w, h, k)

    /** Дилатация кругом радиуса r. */
    private fun dilateDisk(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inf = 1 shl 20
        val hd = IntArray(a.size) // расстояние по строке до ближайшего true
        for (y in 0 until h) {
            var d = inf
            for (x in 0 until w) { d = if (a[y * w + x]) 0 else if (d >= inf) inf else d + 1; hd[y * w + x] = d }
            d = inf
            for (x in w - 1 downTo 0) {
                d = if (a[y * w + x]) 0 else if (d >= inf) inf else d + 1
                if (d < hd[y * w + x]) hd[y * w + x] = d
            }
        }
        val out = BooleanArray(a.size)
        for (dy in -r..r) {
            val hw = sqrt((r * r - dy * dy).toDouble()).toInt()
            for (y in 0 until h) {
                val sy = y + dy
                if (sy < 0 || sy >= h) continue
                for (x in 0 until w) if (hd[sy * w + x] <= hw) out[y * w + x] = true
            }
        }
        return out
    }

    private fun closeDisk(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val pw = w + 2 * r
        val ph = h + 2 * r
        val p = BooleanArray(pw * ph)
        for (y in 0 until h) for (x in 0 until w) p[(y + r) * pw + x + r] = a[y * w + x]
        val d = dilateDisk(p, pw, ph, r)
        val inv = BooleanArray(d.size) { !d[it] }
        val e = dilateDisk(inv, pw, ph, r)
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = !e[(y + r) * pw + x + r]
        return out
    }

    // ---------- компоненты ----------

    private fun label(a: BooleanArray, w: Int, h: Int, lbl: IntArray): Int {
        java.util.Arrays.fill(lbl, 0)
        val q = IntArray(a.size)
        var n = 0
        for (s in a.indices) {
            if (!a[s] || lbl[s] != 0) continue
            n++
            var qh = 0; var qt = 0
            q[qt++] = s; lbl[s] = n
            while (qh < qt) {
                val i = q[qh++]
                val x = i % w; val y = i / w
                if (x > 0 && a[i - 1] && lbl[i - 1] == 0) { lbl[i - 1] = n; q[qt++] = i - 1 }
                if (x < w - 1 && a[i + 1] && lbl[i + 1] == 0) { lbl[i + 1] = n; q[qt++] = i + 1 }
                if (y > 0 && a[i - w] && lbl[i - w] == 0) { lbl[i - w] = n; q[qt++] = i - w }
                if (y < h - 1 && a[i + w] && lbl[i + w] == 0) { lbl[i + w] = n; q[qt++] = i + w }
            }
        }
        return n
    }

    private fun largest(a: BooleanArray, w: Int, h: Int): BooleanArray? {
        val lbl = IntArray(a.size)
        val n = label(a, w, h, lbl)
        if (n < 1) return null
        val cnt = IntArray(n + 1)
        for (v in lbl) if (v > 0) cnt[v]++
        var best = 1
        for (k in 2..n) if (cnt[k] > cnt[best]) best = k
        return BooleanArray(a.size) { lbl[it] == best }
    }

    // ---------- водораздел ----------

    private fun gradient(lab: Lab, w: Int, h: Int): IntArray {
        val g = IntArray(w * h)
        val chans = arrayOf(lab.l, lab.a, lab.b)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var s = 0f
            for (c in chans) {
                val i = y * w + x
                val gx = (c[i - w + 1] + 2 * c[i + 1] + c[i + w + 1]) - (c[i - w - 1] + 2 * c[i - 1] + c[i + w - 1])
                val gy = (c[i + w - 1] + 2 * c[i + w] + c[i + w + 1]) - (c[i - w - 1] + 2 * c[i - w] + c[i - w + 1])
                s += gx * gx + gy * gy
            }
            g[y * w + x] = (sqrt(s) * 2f).toInt().coerceIn(0, 255)
        }
        return g
    }

    private class IntList {
        var a = IntArray(64)
        var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun pop(): Int = a[--n]
    }

    /** Заливка с приоритетом (корзины по уровню градиента). markers: 1 = фон, 2 = предмет. */
    private fun flood(grad: IntArray, markers: ByteArray, w: Int, h: Int): ByteArray {
        val lab = markers.copyOf()
        val queued = BooleanArray(lab.size) { lab[it].toInt() != 0 }
        val buckets = Array(256) { IntList() }
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)
        for (i in lab.indices) {
            if (lab[i].toInt() == 0) continue
            val x = i % w; val y = i / w
            for (d in 0 until 4) {
                val nx = x + dx[d]; val ny = y + dy[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val ni = ny * w + nx
                if (queued[ni]) continue
                queued[ni] = true
                buckets[grad[ni]].add(ni * 4 + lab[i])
            }
        }
        var lvl = 0
        while (lvl < 256) {
            val b = buckets[lvl]
            if (b.n == 0) { lvl++; continue }
            val e = b.pop()
            val i = e shr 2
            val l = e and 3
            if (lab[i].toInt() != 0) continue
            lab[i] = l.toByte()
            val x = i % w; val y = i / w
            for (d in 0 until 4) {
                val nx = x + dx[d]; val ny = y + dy[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val ni = ny * w + nx
                if (queued[ni]) continue
                queued[ni] = true
                buckets[max(lvl, grad[ni])].add(ni * 4 + l)
            }
        }
        return lab
    }

    // ---------- финиш: выпуклая форма без «съеденных» краёв ----------

    private fun finish(mask: BooleanArray, far: FloatArray, w: Int, h: Int): BooleanArray? {
        val m = largest(open(mask, w, h, 7), w, h) ?: return null
        // выпуклая оболочка по крайним точкам строк
        val pts = ArrayList<IntArray>()
        for (y in 0 until h) {
            var lo = -1; var hi = -1
            for (x in 0 until w) if (m[y * w + x]) { if (lo < 0) lo = x; hi = x }
            if (lo >= 0) { pts.add(intArrayOf(lo, y)); if (hi != lo) pts.add(intArrayOf(hi, y)) }
        }
        val hull = convexHull(pts)
        if (hull.size < 3) return null
        val hm = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var inside = true
            for (k in hull.indices) {
                val p = hull[k]; val q = hull[(k + 1) % hull.size]
                val cr = (q[0] - p[0]).toLong() * (y - p[1]) - (q[1] - p[1]).toLong() * (x - p[0])
                if (cr < 0) { inside = false; break }
            }
            if (inside) hm[y * w + x] = true
        }
        // «плотный» фон: заливка от краёв по пикселям, почти неотличимым от фона
        val tight = BooleanArray(w * h)
        val q = IntArray(w * h)
        var qh = 0; var qt = 0
        fun seed(i: Int) { if (!tight[i] && far[i] <= 14f) { tight[i] = true; q[qt++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (qh < qt) {
            val i = q[qh++]
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        var out = BooleanArray(w * h) { hm[it] && !tight[it] }
        val r = max(5, (0.07 * min(h, w)).toInt())
        out = closeDisk(out, w, h, r)
        for (i in out.indices) out[i] = out[i] && hm[i]
        out = open(out, w, h, 5)
        return largest(out, w, h)
    }

    /** Выпуклая оболочка (Эндрю), обход против часовой стрелки в системе «y вниз» даёт cr >= 0 внутри. */
    private fun convexHull(pts: List<IntArray>): List<IntArray> {
        val p = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (p.size < 3) return p
        fun cross(o: IntArray, a: IntArray, b: IntArray): Long =
            (a[0] - o[0]).toLong() * (b[1] - o[1]) - (a[1] - o[1]).toLong() * (b[0] - o[0])
        val lower = ArrayList<IntArray>()
        for (pt in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0) lower.removeAt(lower.size - 1)
            lower.add(pt)
        }
        val upper = ArrayList<IntArray>()
        for (pt in p.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0) upper.removeAt(upper.size - 1)
            upper.add(pt)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        val res = ArrayList<IntArray>(lower)
        res.addAll(upper)
        // ориентация: проверяем знак внутри-теста для центра масс
        var cx = 0.0; var cy = 0.0
        for (v in res) { cx += v[0]; cy += v[1] }
        cx /= res.size; cy /= res.size
        val v0 = res[0]; val v1 = res[1]
        val cr = (v1[0] - v0[0]) * (cy - v0[1]) - (v1[1] - v0[1]) * (cx - v0[0])
        if (cr < 0) res.reverse()
        return res
    }

}
