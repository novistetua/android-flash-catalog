package com.novis.flashcatalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Entry(val name: String, val photo: Uri?, val note: String, val cutout: Uri? = null, val created: Long = 0L, val modified: Long = 0L)

data class Extra(val name: String, val uri: Uri)

object Storage {
    private const val PREFS = "fc"
    private const val KEY_ROOT = "root"
    const val PHOTO = "photo.jpg"
    const val INFO = "info.txt"
    const val NOMEDIA = ".nomedia"
    const val ORIG = ".photo.jpg"      // скрытый оригинал: с него можно начать правку заново
    const val MASK = ".mask.png"       // скрытая маска фона, чтобы продолжить ручную правку
    const val CUT = "photo_nobg.png"
    private const val FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun defaultName(): String =
        "Storage-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun sanitize(raw: String): String {
        var s = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim()
        s = s.trimStart('.').trim()
        if (s.length > 100) s = s.substring(0, 100).trim()
        return if (s.isEmpty()) defaultName() else s
    }

    fun rootUri(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROOT, null)?.let { Uri.parse(it) }

    fun describeRoot(ctx: Context): String {
        val uri = rootUri(ctx) ?: return "не выбрана"
        return try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            uri.toString()
        }
    }

    fun getRoot(ctx: Context): DocumentFile? {
        val uri = rootUri(ctx) ?: return null
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!granted) return null
        val doc = DocumentFile.fromTreeUri(ctx, uri) ?: return null
        return try {
            if (doc.exists() && doc.canWrite()) doc else null
        } catch (e: Exception) {
            null
        }
    }

    fun setRoot(ctx: Context, uri: Uri) {
        val old = rootUri(ctx)
        ctx.contentResolver.takePersistableUriPermission(uri, FLAGS)
        if (old != null && old != uri) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(old, FLAGS)
            } catch (e: Exception) {
                // не страшно
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROOT, uri.toString()).apply()
        getRoot(ctx)?.let { ensureNomedia(it) }
    }

    fun ensureNomedia(dir: DocumentFile) {
        try {
            if (dir.findFile(NOMEDIA) != null) return
            val f = dir.createFile("application/octet-stream", NOMEDIA) ?: return
            if (f.name != NOMEDIA) f.renameTo(NOMEDIA)
        } catch (e: Exception) {
            // не страшно
        }
    }

    fun readText(ctx: Context, uri: Uri): String =
        try {
            ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        } catch (e: Exception) {
            ""
        }

    // ---- сортировка списка: alpha / created / modified / manual ----
    const val SORT_ALPHA = "alpha"
    const val SORT_CREATED = "created"
    const val SORT_MODIFIED = "modified"
    const val SORT_MANUAL = "manual"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun sortMode(ctx: Context): String = prefs(ctx).getString("sort_mode", SORT_ALPHA) ?: SORT_ALPHA
    fun sortRev(ctx: Context): Boolean = prefs(ctx).getBoolean("sort_rev", false)
    fun setSort(ctx: Context, mode: String, rev: Boolean) { prefs(ctx).edit().putString("sort_mode", mode).putBoolean("sort_rev", rev).apply() }

    /** Ручной порядок хранится отдельно и не теряется при переключении на другие виды сортировки. */
    fun manualOrder(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("manual_order", null) ?: return emptyList()
        return try { val a = org.json.JSONArray(raw); (0 until a.length()).map { a.getString(it) } } catch (e: Exception) { emptyList() }
    }

    /** Пользователь переставил карточки: сохраняем порядок и включаем «Ручной». */
    fun saveManualOrder(ctx: Context, names: List<String>) {
        prefs(ctx).edit().putString("manual_order", org.json.JSONArray(names).toString()).putString("sort_mode", SORT_MANUAL).apply()
    }

    private val NAME_DATE = Regex("^Storage-(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})-(\\d{2})")

    private fun parseNameDate(name: String): Long {
        val m = NAME_DATE.find(name) ?: return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(name.substring(8, 27))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    fun sortEntries(ctx: Context, items: List<Entry>): List<Entry> {
        val rev = sortRev(ctx)
        return when (sortMode(ctx)) {
            SORT_CREATED -> items.sortedWith(compareBy<Entry>({ it.created }, { it.name.lowercase(Locale.ROOT) })).let { if (rev) it.reversed() else it }
            SORT_MODIFIED -> items.sortedWith(compareBy<Entry>({ it.modified.takeIf { m -> m > 0 } ?: it.created }, { it.name.lowercase(Locale.ROOT) })).let { if (rev) it.reversed() else it }
            SORT_MANUAL -> {
                val idx = manualOrder(ctx).withIndex().associate { it.value to it.index }
                val known = items.filter { it.name in idx }.sortedBy { idx[it.name] }
                val fresh = items.filter { it.name !in idx }.sortedByDescending { it.name.lowercase(Locale.ROOT) } // новые карточки сверху
                fresh + known
            }
            else -> items.sortedBy { it.name.lowercase(Locale.ROOT) }.let { if (rev) it.reversed() else it }
        }
    }

    private fun toEntry(ctx: Context, dir: DocumentFile, withDates: Boolean = false): Entry? {
        val name = dir.name ?: return null
        if (!dir.isDirectory || name.startsWith(".")) return null
        val kids = dir.listFiles()
        val photo = kids.firstOrNull { it.name == PHOTO }
        val info = kids.firstOrNull { it.name == INFO }
        val cut = kids.firstOrNull { it.name == "photo_nobg.png" }
        if (photo == null && info == null) return null
        val note = if (info != null) readText(ctx, info.uri) else ""
        var created = 0L
        var modified = 0L
        if (withDates) {
            val times = listOfNotNull(photo, info, cut).map { try { it.lastModified() } catch (e: Exception) { 0L } }.filter { it > 0 }
            created = parseNameDate(name)
            if (created == 0L) created = times.minOrNull() ?: 0L
            modified = times.maxOrNull() ?: 0L
        }
        return Entry(name, photo?.uri, note, cut?.uri, created, modified)
    }

    fun list(ctx: Context): List<Entry> {
        val root = getRoot(ctx) ?: return emptyList()
        val out = ArrayList<Entry>()
        val dates = sortMode(ctx).let { it == SORT_CREATED || it == SORT_MODIFIED }
        for (dir in root.listFiles()) {
            toEntry(ctx, dir, dates)?.let { out.add(it) }
        }
        return sortEntries(ctx, out)
    }

    fun find(ctx: Context, name: String): Entry? {
        val root = getRoot(ctx) ?: return null
        val dir = root.findFile(name) ?: return null
        return toEntry(ctx, dir)
    }

    private fun writeFile(ctx: Context, dir: DocumentFile, name: String, mime: String, data: ByteArray) {
        val f = dir.findFile(name) ?: dir.createFile(mime, name)
            ?: throw IOException("не удалось создать $name")
        val out = ctx.contentResolver.openOutputStream(f.uri, "wt")
            ?: throw IOException("не удалось открыть $name для записи")
        out.use { it.write(data) }
    }

    /** Возвращает текст ошибки или null, если всё хорошо. */
    fun saveNew(ctx: Context, name: String, note: String, photo: File?): String? {
        val root = getRoot(ctx) ?: return "Папка каталога недоступна. Выбери её заново кнопкой «Папка»."
        val clean = sanitize(name)
        if (root.findFile(clean) != null) return "Папка «$clean» уже существует"
        val dir = root.createDirectory(clean) ?: return "Не удалось создать папку"
        try {
            if (photo != null) writeFile(ctx, dir, PHOTO, "image/jpeg", photo.readBytes())
            writeFile(ctx, dir, INFO, "text/plain", note.toByteArray(Charsets.UTF_8))
            ensureNomedia(dir)
            ensureNomedia(root)
        } catch (e: Exception) {
            return "Ошибка записи: ${e.message}"
        }
        return null
    }

    fun update(ctx: Context, oldName: String, newName: String, note: String, newPhoto: File?): String? {
        val root = getRoot(ctx) ?: return "Папка каталога недоступна. Выбери её заново кнопкой «Папка»."
        var dir = root.findFile(oldName) ?: return "Папка «$oldName» не найдена"
        val clean = sanitize(newName)
        try {
            if (clean != oldName) {
                if (root.findFile(clean) != null) return "Папка «$clean» уже существует"
                if (!dir.renameTo(clean)) return "Не удалось переименовать папку"
                dir = root.findFile(clean) ?: return "Папка пропала после переименования"
            }
            if (newPhoto != null) writeFile(ctx, dir, PHOTO, "image/jpeg", newPhoto.readBytes())
            writeFile(ctx, dir, INFO, "text/plain", note.toByteArray(Charsets.UTF_8))
            ensureNomedia(dir)
        } catch (e: Exception) {
            return "Ошибка записи: ${e.message}"
        }
        return null
    }

    /** Дописывает файл в уже существующую папку записи. Возвращает текст ошибки или null. */
    fun saveExtra(ctx: Context, folder: String, fileName: String, mime: String, data: ByteArray): String? {
        val root = getRoot(ctx) ?: return "Папка каталога недоступна"
        val dir = root.findFile(folder) ?: return "Папка «$folder» не найдена"
        return try {
            writeFile(ctx, dir, fileName, mime, data)
            ensureNomedia(dir)
            null
        } catch (e: Exception) {
            "Ошибка записи: ${e.message}"
        }
    }

    fun readFile(ctx: Context, folder: String, fileName: String): ByteArray? {
        val root = getRoot(ctx) ?: return null
        val f = root.findFile(folder)?.findFile(fileName) ?: return null
        return try { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } } catch (e: Exception) { null }
    }

    fun hasFile(ctx: Context, folder: String, fileName: String): Boolean {
        val root = getRoot(ctx) ?: return false
        return root.findFile(folder)?.findFile(fileName) != null
    }

    fun delete(ctx: Context, name: String): Boolean {
        val root = getRoot(ctx) ?: return false
        return root.findFile(name)?.delete() ?: false
    }

    // ---- дополнительные фото карточки: additionally-1.jpg, additionally-2.jpg … ----

    private val EXTRA_RE = Regex("^additionally-(\\d+)\\.jpg$")

    fun listExtras(ctx: Context, folder: String): List<Extra> {
        val root = getRoot(ctx) ?: return emptyList()
        val dir = root.findFile(folder) ?: return emptyList()
        val out = ArrayList<Pair<Int, Extra>>()
        for (f in dir.listFiles()) {
            val n = f.name ?: continue
            val m = EXTRA_RE.matchEntire(n) ?: continue
            out.add((m.groupValues[1].toIntOrNull() ?: 0) to Extra(n, f.uri))
        }
        out.sortBy { it.first }
        return out.map { it.second }
    }

    /** Добавляет фото под следующим номером. Возвращает текст ошибки или null. */
    fun addExtra(ctx: Context, folder: String, data: ByteArray): String? {
        val root = getRoot(ctx) ?: return "Папка каталога недоступна"
        val dir = root.findFile(folder) ?: return "Папка «$folder» не найдена"
        return try {
            var max = 0
            for (f in dir.listFiles()) {
                val m = EXTRA_RE.matchEntire(f.name ?: continue) ?: continue
                max = maxOf(max, m.groupValues[1].toIntOrNull() ?: 0)
            }
            writeFile(ctx, dir, "additionally-${max + 1}.jpg", "image/jpeg", data)
            ensureNomedia(dir)
            null
        } catch (e: Exception) {
            "Ошибка записи: ${e.message}"
        }
    }

    fun replaceExtra(ctx: Context, folder: String, fileName: String, data: ByteArray): String? =
        saveExtra(ctx, folder, fileName, "image/jpeg", data)

    fun deleteExtra(ctx: Context, folder: String, fileName: String): Boolean {
        val root = getRoot(ctx) ?: return false
        val dir = root.findFile(folder) ?: return false
        return dir.findFile(fileName)?.delete() ?: false
    }

    // ---- обмен карточками ----

    /** Файлы карточек (все или выбранные): пары «имя карточки, файл». */
    fun cardFiles(ctx: Context, names: List<String>?): List<Pair<String, DocumentFile>> {
        val root = getRoot(ctx) ?: return emptyList()
        val out = ArrayList<Pair<String, DocumentFile>>()
        for (dir in root.listFiles()) {
            val n = dir.name ?: continue
            if (!dir.isDirectory || n.startsWith(".")) continue
            if (names != null && n !in names) continue
            for (f in dir.listFiles()) {
                val fn = f.name ?: continue
                if (f.isFile && fn != NOMEDIA) out.add(n to f)
            }
        }
        return out
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".jpg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    /** mode: 0 — пропустить существующую, 1 — заменить, 2 — оставить обе. Возвращает null или текст ошибки. */
    fun importCard(ctx: Context, srcDir: File, name: String, mode: Int): String? {
        val root = getRoot(ctx) ?: return "Папка каталога недоступна"
        var target = sanitize(name)
        val old = root.findFile(target)
        if (old != null) {
            when (mode) {
                0 -> return null
                1 -> old.delete()
                else -> {
                    var i = 2
                    while (root.findFile("$target-$i") != null) i++
                    target = "$target-$i"
                }
            }
        }
        val dir = root.createDirectory(target) ?: return "Не удалось создать папку «$target»"
        return try {
            for (f in srcDir.listFiles() ?: emptyArray()) {
                if (f.name == NOMEDIA) continue
                writeFile(ctx, dir, f.name, mimeFor(f.name), f.readBytes())
            }
            ensureNomedia(dir)
            null
        } catch (e: Exception) {
            "Ошибка записи: ${e.message}"
        }
    }
}
