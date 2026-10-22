package com.novis.flashcatalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Страница-редактор в самой папке каталога (FlashCatalogEditor.html). Её синхронизирует Syncthing, на компьютере её
 * открывают прямо из этой папки: список карточек и тексты зашиты в страницу, а фото берутся из подпапок по
 * относительным путям. Страница пересоздаётся только если что-то изменилось (карточки, тексты, порядок, версия приложения).
 */
object EditorPage {
    const val FILE = "FlashCatalogEditor.html"
    private val EXTRA = Regex("^additionally-(\\d+)\\.jpg$")
    @Volatile private var lastRun = 0L

    fun refresh(ctx: Context, entries: List<Entry>) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 3000) return
        lastRun = now
        val root = Storage.getRoot(ctx) ?: return
        try {
            val files = Storage.cardFiles(ctx, null).groupBy({ it.first }, { it.second.name ?: "" })
            val arr = JSONArray()
            for (e in entries) {
                val fl = files[e.name].orEmpty()
                val extras = fl.filter { EXTRA.matches(it) }.sortedBy { EXTRA.matchEntire(it)!!.groupValues[1].toInt() }
                val main = if (Storage.CUT in fl) Storage.CUT else if (Storage.PHOTO in fl) Storage.PHOTO else null
                arr.put(JSONObject().put("n", e.name).put("t", e.note).put("f", JSONArray(listOfNotNull(main) + extras)))
            }
            val tpl = ctx.assets.open("editor.html").bufferedReader(Charsets.UTF_8).readText()
            val html = tpl.replace("window.FC_INDEX=null;", "window.FC_INDEX=" + arr.toString() + ";")
            val bytes = html.toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            val prefs = ctx.getSharedPreferences("fc", Context.MODE_PRIVATE)
            val key = Storage.rootUri(ctx).toString() + "|" + hash
            if (prefs.getString("editor_hash", null) == key && root.findFile(FILE) != null) return
            if (Storage.writeRootFile(ctx, FILE, "text/html", bytes)) prefs.edit().putString("editor_hash", key).apply()
        } catch (e: Exception) {
            // страница не критична: в следующий раз попробуем снова
        }
    }
}
