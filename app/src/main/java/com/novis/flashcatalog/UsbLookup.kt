package com.novis.flashcatalog

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Параметры USB-устройства: разбор VID:PID, поиск по базе usb.ids и в интернете, слияние текста без потерь. */
object UsbLookup {
    /** Вариант ответа поиска: что показать пользователю и какие строки добавить в текст устройства. */
    class Candidate(val title: String, val detail: String, val lines: List<String>)

    private val PAIR = Regex("([0-9a-fA-F]{4})\\s*[:_&/ \\-]*\\s*(?:PID_?|pid_?)?\\s*([0-9a-fA-F]{4})")

    /** Понимает «0951:1666», «VID_0951&PID_1666», «0951 1666». Возвращает пару в нижнем регистре или null. */
    fun parseId(text: String): Pair<String, String>? {
        val t = text.replace(Regex("(?i)vid_?"), "").trim()
        val m = PAIR.find(t) ?: return null
        return m.groupValues[1].lowercase() to m.groupValues[2].lowercase()
    }

    /** Ищет в тексте уже записанный VID:PID (строка «VID:PID: 0951:1666»). */
    fun idFromText(text: String): Pair<String, String>? {
        val line = text.lines().firstOrNull { it.trim().startsWith("VID:PID", true) } ?: return null
        return parseId(line.substringAfter(':').let { line.substringAfter("VID:PID").trimStart(':', ' ') })
    }

    /** Добавляет только те строки, которых ещё нет (без учёта регистра и пробелов по краям). Ничего не удаляет и не заменяет. */
    fun merge(existing: String, add: List<String>): String {
        val have = existing.lines().map { it.trim().lowercase() }.toMutableSet()
        val sb = StringBuilder(existing.trimEnd())
        for (l in add) {
            val t = l.trim()
            if (t.isEmpty() || t.lowercase() in have) continue
            // строка VID:PID одна на карточку
            if (t.startsWith("VID:PID", true) && have.any { it.startsWith("vid:pid") }) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
            have.add(t.lowercase())
        }
        return sb.toString()
    }

    // ---------- usb.ids ----------

    private const val IDS_URL = "https://raw.githubusercontent.com/usbids/usbids/master/usb.ids"

    private fun idsFile(ctx: Context) = File(ctx.filesDir, "usb.ids")

    private fun ensureIds(ctx: Context) {
        val f = idsFile(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 60L * 24 * 3600 * 1000) return
        val tmp = File(ctx.filesDir, "usb.ids.tmp")
        try {
            val c = URL(IDS_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "FlashCatalog")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() > 100_000) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            tmp.delete()
            if (!f.exists()) throw e
        }
    }

    /** Производитель и модель по базе usb.ids (может быть только производитель или ничего). */
    fun localIds(ctx: Context, vid: String, pid: String): Pair<String?, String?> {
        ensureIds(ctx)
        var vendor: String? = null
        var product: String? = null
        var inVendor = false
        idsFile(ctx).bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                if (line.isEmpty() || line.startsWith("#")) continue
                if (!line.startsWith("\t")) {
                    if (inVendor) return@useLines
                    if (line.length > 6 && line.startsWith(vid) && line[4] == ' ') { vendor = line.substring(4).trim(); inVendor = true }
                    else if (line.startsWith("C ")) { /* дальше только классы */ if (vendor == null) return@useLines }
                } else if (inVendor && !line.startsWith("\t\t")) {
                    val t = line.trimStart('\t')
                    if (t.length > 5 && t.startsWith(pid) && t[4] == ' ') { product = t.substring(4).trim(); return@useLines }
                }
            }
        }
        return vendor to product
    }

    // ---------- интернет ----------

    fun searchUrl(vid: String, pid: String, name: String?): String =
        "https://duckduckgo.com/?q=" + URLEncoder.encode(query(vid, pid, name), "UTF-8")

    private fun query(vid: String, pid: String, name: String?): String =
        "usb $vid:$pid" + (name?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")

    private fun clean(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    /**
     * Варианты ответа: база usb.ids и результаты веб-поиска. Ошибки источников не прерывают друг друга;
     * в notes попадают пояснения («поиск недоступен» и т.п.).
     */
    fun find(ctx: Context, vid: String, pid: String, name: String?, notes: MutableList<String>): List<Candidate> {
        val out = ArrayList<Candidate>()
        try {
            val (v, p) = localIds(ctx, vid, pid)
            if (v != null || p != null) {
                val lines = ArrayList<String>()
                lines.add("VID:PID: $vid:$pid")
                if (v != null) lines.add("Производитель (usb.ids): $v")
                if (p != null) lines.add("Модель (usb.ids): $p")
                out.add(Candidate("База usb.ids: " + listOfNotNull(v, p).joinToString(" / "),
                    if (p == null) "Известен только производитель, модели в базе нет" else "Официальная база идентификаторов USB", lines))
            } else notes.add("В базе usb.ids такой пары нет (обычно так бывает у безымянных китайских устройств).")
        } catch (e: Exception) {
            notes.add("База usb.ids не загрузилась: ${e.message}")
        }
        try {
            val q = URLEncoder.encode(query(vid, pid, name), "UTF-8")
            val c = URL("https://html.duckduckgo.com/html/?q=$q").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
            val code = c.responseCode
            val html = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            val snippets = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            var n = 0
            for ((i, m) in links.withIndex()) {
                if (n >= 8) break
                var href = m.groupValues[1]
                Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                if (href.startsWith("//")) href = "https:$href"
                if (!href.startsWith("http")) continue
                val title = clean(m.groupValues[2]).take(120)
                val snip = snippets.getOrNull(i)?.let { clean(it.groupValues[1]) }.orEmpty().take(220)
                if (title.isEmpty()) continue
                out.add(Candidate("Сайт: $title", (if (snip.isNotEmpty()) snip + "\n" else "") + href,
                    listOf("VID:PID: $vid:$pid", "Источник: $title", "Ссылка: $href") + (if (snip.isNotEmpty()) listOf("Описание: $snip") else emptyList())))
                n++
            }
            if (n == 0) notes.add(if (code == 202 || html.contains("anomaly", true)) "Поисковик отказал приложению (защита от роботов)." else "Поиск в интернете ничего не вернул.")
        } catch (e: Exception) {
            notes.add("Поиск в интернете недоступен: ${e.message}")
        }
        return out
    }

    // ---------- чтение с подключённого по OTG устройства ----------

    fun className(c: Int): String = when (c) {
        1 -> "Аудио"; 2, 10 -> "Связь (CDC, модем/COM)"; 3 -> "HID (клавиатура, мышь, ключ)"; 6 -> "Камера/изображения"
        7 -> "Принтер"; 8 -> "Накопитель (Mass Storage)"; 9 -> "Хаб"; 11 -> "Смарт-карта"; 14 -> "Видео"
        224 -> "Беспроводной (Bluetooth/Wi‑Fi)"; 255 -> "Специфичный для производителя (часто Wi‑Fi/COM-донглы)"; else -> "Класс $c"
    }

    fun describe(d: android.hardware.usb.UsbDevice, withNames: Boolean): List<String> {
        val l = ArrayList<String>()
        l.add("VID:PID: %04x:%04x".format(d.vendorId, d.productId))
        if (withNames) {
            try { d.manufacturerName?.takeIf { it.isNotBlank() }?.let { l.add("Производитель (устройство): $it") } } catch (e: SecurityException) { }
            try { d.productName?.takeIf { it.isNotBlank() }?.let { l.add("Название (устройство): $it") } } catch (e: SecurityException) { }
        }
        val classes = LinkedHashSet<Int>()
        for (i in 0 until d.interfaceCount) classes.add(d.getInterface(i).interfaceClass)
        if (classes.isEmpty()) classes.add(d.deviceClass)
        l.add("Тип: " + classes.joinToString(", ") { className(it) })
        try { if (d.configurationCount > 0) l.add("Потребление: до ${d.getConfiguration(0).maxPower} мА") } catch (e: Exception) { }
        return l
    }
}
