package com.novis.flashcatalog

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Настройки: папка каталога, камера по умолчанию, ИИ-вырезание (порядок, токены, ключи). */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("fc", MODE_PRIVATE) }
    private lateinit var box: LinearLayout
    private lateinit var folderText: TextView
    private lateinit var orderBox: LinearLayout
    private lateinit var etOwnUrl: EditText
    private lateinit var etTokOwn: EditText
    private lateinit var etTokPublic: EditText
    private lateinit var etKeysRb: EditText
    private var order = ArrayList<String>()

    private val names = mapOf(
        "own" to "Мой сервер / Space (компьютер или копия на Hugging Face)",
        "public" to "Публичные сервисы Hugging Face",
        "removebg" to "remove.bg"
    )

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                Storage.setRoot(this, uri)
                folderText.text = "Сейчас: " + Storage.describeRoot(this)
            } catch (e: Exception) {
                Toast.makeText(this, "Эту папку использовать не получилось: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 18f
        v.setTypeface(null, android.graphics.Typeface.BOLD)
        v.setTextColor(0xFF1565C0.toInt())
        v.setPadding(0, dp(24), 0, dp(4))
        box.addView(v)
    }

    private fun note(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(0xFF666666.toInt())
        v.setPadding(0, dp(2), 0, dp(4))
        box.addView(v)
        return v
    }

    private fun label(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.setTextColor(0xFF222222.toInt())
        v.setPadding(0, dp(10), 0, 0)
        box.addView(v)
    }

    private fun multi(hintText: String, saved: String?, lines: Int): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setText(saved ?: "")
        e.textSize = 13f
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.minLines = lines
        e.gravity = Gravity.TOP
        box.addView(e)
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Настройки"
        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFFFAFAFA.toInt())
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(32))
        sv.addView(box)
        setContentView(sv)

        // ---- папка ----
        header("Папка каталога")
        folderText = note("Сейчас: " + Storage.describeRoot(this))
        folderText.textSize = 14f
        val bFolder = Button(this)
        bFolder.text = "Выбрать другую папку"
        bFolder.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Папка каталога")
                .setMessage(
                    "Выбери папку, которую синхронизирует Syncthing (можно создать новую, например FlashCatalog, " +
                        "во внутренней памяти или на microSD).\n\nЕсли меняешь папку, старые записи сами не переносятся: " +
                        "скопируй их в новую папку или подключи к ней Syncthing."
                )
                .setPositiveButton("Выбрать") { _, _ -> pickFolder.launch(null) }
                .setNegativeButton("Отмена", null)
                .show()
        }
        box.addView(bFolder)

        // ---- поделиться приложением ----
        header("Поделиться приложением")
        note("Передать Flash Catalog на другой телефон: файлом или ссылкой по Wi‑Fi (без интернета и без GitHub).")
        val bApk = Button(this)
        bApk.text = "Отправить файл приложения (APK)"
        bApk.setOnClickListener { shareApk() }
        box.addView(bApk)
        val bApkWeb = Button(this)
        bApkWeb.text = "Раздать приложение по Wi‑Fi (ссылка и QR)"
        bApkWeb.setOnClickListener { WebShareUi.start(this, emptyList()) }
        box.addView(bApkWeb)

        // ---- камера ----
        header("Камера при добавлении флешки")
        val last = if (prefs.getString("mode", "") == "macro") "макро-линза" else "обычная"
        note("Какую камеру открывать сразу. Последний выбранный режим: $last.")
        val rg = RadioGroup(this)
        val opts = listOf("last" to "Запоминать последнюю использованную", "macro" to "Всегда макро-линза", "main" to "Всегда обычная камера")
        val cur = prefs.getString("cam_default", "last")
        for ((i, o) in opts.withIndex()) {
            val rb = RadioButton(this)
            rb.id = 1000 + i
            rb.text = o.second
            rb.isChecked = o.first == cur
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("cam_default", opts[id - 1000].first).apply()
        }
        box.addView(rg)

        // ---- ссылка для браузера ----
        header("Ссылка для браузера")
        val cb = android.widget.CheckBox(this)
        cb.text = "Добавлять в ссылку секретный ключ (длиннее, зато чужие в сети не откроют)"
        cb.textSize = 14f
        cb.isChecked = prefs.getBoolean("web_secret", false)
        cb.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("web_secret", c).apply() }
        box.addView(cb)
        note("Без ключа ссылка выглядит как http://адрес:8765/ и её можно сохранить в закладки.")

        // ---- ИИ ----
        header("ИИ-вырезание фона")
        note("Куда отправлять фото в первую очередь. Если первый сервис не ответил или исчерпал лимит, приложение пробует следующий.")
        orderBox = LinearLayout(this)
        orderBox.orientation = LinearLayout.VERTICAL
        box.addView(orderBox)
        order = ArrayList(AiMask.loadConfig(prefs).order)
        drawOrder()

        val bHelp = Button(this)
        bHelp.text = "Инструкция: свой ИИ-сервер на Windows"
        bHelp.setOnClickListener { AiHelp.show(this) }
        box.addView(bHelp)

        label("Адрес моего сервера или Space")
        etOwnUrl = multi("https://имя.hf.space или http://192.168.1.20:7860", prefs.getString("own_space", ""), 1)
        etOwnUrl.setSingleLine(true)
        label("Токены Hugging Face для моего Space (по одному в строке; можно пусто)")
        etTokOwn = multi("hf_…", prefs.getString("tok_own", ""), 2)
        label("Токены Hugging Face для публичных сервисов (по одному в строке)")
        note("Лимит GPU считается по каждому аккаунту: у бесплатного 5 минут в день. Несколько токенов с разных аккаунтов по очереди дают больше. Когда у одного лимит кончился, берётся следующий.")
        etTokPublic = multi("hf_…\nhf_…", prefs.getString("tok_public", ""), 3)
        label("Ключи remove.bg (по одному в строке)")
        note("50 бесплатных обработок в месяц на ключ, низкое разрешение (для маски хватает).")
        etKeysRb = multi("ключ API", prefs.getString("tok_removebg", ""), 2)
        note("Токены хранятся только на этом телефоне и уходят только на серверы Hugging Face (ключи remove.bg только на remove.bg).")
    }

    private fun shareApk() {
        Thread {
            try {
                val f = java.io.File(cacheDir, "FlashCatalog.apk")
                java.io.File(applicationInfo.sourceDir).copyTo(f, true)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                runOnUiThread {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("application/vnd.android.package-archive")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(android.content.Intent.createChooser(i, "Отправить приложение"))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Не получилось: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun drawOrder() {
        orderBox.removeAllViews()
        for ((i, id) in order.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val t = TextView(this)
            t.text = "${i + 1}. " + (names[id] ?: id)
            t.textSize = 15f
            t.setTextColor(0xFF111111.toInt())
            row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun btn(sym: String, enabled: Boolean, d: Int) {
                val b = Button(this)
                b.text = sym
                b.isEnabled = enabled
                b.setOnClickListener {
                    val j = order.indexOf(id)
                    order.removeAt(j)
                    order.add(j + d, id)
                    saveOrder()
                    drawOrder()
                }
                row.addView(b, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            btn("↑", i > 0, -1)
            btn("↓", i < order.size - 1, 1)
            orderBox.addView(row)
        }
    }

    private fun saveOrder() {
        prefs.edit().putString("ai_order", order.joinToString(",")).apply()
    }

    private fun saveAll() {
        saveOrder()
        prefs.edit()
            .putString("own_space", etOwnUrl.text.toString().trim())
            .putString("tok_own", etTokOwn.text.toString().trim())
            .putString("tok_public", etTokPublic.text.toString().trim())
            .putString("tok_removebg", etKeysRb.text.toString().trim())
            .apply()
    }

    override fun onPause() {
        super.onPause()
        saveAll()
    }
}
