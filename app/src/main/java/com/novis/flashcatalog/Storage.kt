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

data class Entry(val name: String, val photo: Uri?, val note: String, val cutout: Uri? = null)

data class Extra(val name: String, val uri: Uri)

object Storage {
    private const val PREFS = "fc"
    private const val KEY_ROOT = "root"
    const val PHOTO = "photo.jpg"
    const val INFO = "info.txt"
    const val NOMEDIA = ".nomedia"
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

    private fun toEntry(ctx: Context, dir: DocumentFile): Entry? {
        val name = dir.name ?: return null
        if (!dir.isDirectory || name.startsWith(".")) return null
        val kids = dir.listFiles()
        val photo = kids.firstOrNull { it.name == PHOTO }
        val info = kids.firstOrNull { it.name == INFO }
        val cut = kids.firstOrNull { it.name == "photo_nobg.png" }
        if (photo == null && info == null) return null
        val note = if (info != null) readText(ctx, info.uri) else ""
        return Entry(name, photo?.uri, note, cut?.uri)
    }

    fun list(ctx: Context): List<Entry> {
        val root = getRoot(ctx) ?: return emptyList()
        val out = ArrayList<Entry>()
        for (dir in root.listFiles()) {
            toEntry(ctx, dir)?.let { out.add(it) }
        }
        out.sortBy { it.name.lowercase(Locale.ROOT) }
        return out
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
}
