package com.novis.flashcatalog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Инструкция «свой ИИ-сервер на Windows» и скрипт сервера (проверен на Gradio 6 + rembg, Linux). */
object AiHelp {

    /** Только латиница: Блокнот Windows 10 сохраняет в ANSI, кириллица в скрипте сломала бы запуск. */
    val SCRIPT = """
# Flash Catalog: local background removal server.
# Run:  python fc_ai_server.py        (default model: isnet-general-use)
import sys
import gradio as gr
from rembg import new_session, remove

MODEL = sys.argv[1] if len(sys.argv) > 1 else "isnet-general-use"
print("Loading model", MODEL, "(the first start downloads it from the internet)...")
session = new_session(MODEL)

def png(img):
    return remove(img, session=session)

demo = gr.Interface(
    png,
    gr.Image(type="pil", label="Photo"),
    gr.Image(type="pil", format="png", label="Result"),
    api_name="png",
    flagging_mode="never",
    analytics_enabled=False,
)
demo.launch(server_name="0.0.0.0", server_port=7860)
""".trimStart()

    val COMMANDS = """
mkdir C:\fc-ai
cd /d C:\fc-ai
python -m venv venv
venv\Scripts\activate
pip install gradio "rembg[cpu]"
python fc_ai_server.py
""".trimStart()

    private val TEXT = """
СВОЙ ИИ-СЕРВЕР НА КОМПЬЮТЕРЕ С WINDOWS

Зачем: ИИ режет фон на твоём компьютере. Нет дневных лимитов, фото не уходит в интернет. Телефон отправляет снимок на компьютер по Wi‑Fi или через ZeroTier.

МИНИМУМ ДЛЯ КОМПЬЮТЕРА
• Windows 10 или 11, 64-бит.
• Процессор x64 с поддержкой AVX (любой примерно с 2013 года).
• Оперативная память: минимум 4 ГБ, лучше 8 ГБ. У модели по умолчанию (isnet-general-use) в моём тесте пик был около 1,1 ГБ и около 2 секунд на фото на двух ядрах.
• Диск: около 2–3 ГБ (Python с библиотеками примерно 1–2 ГБ, модель около 180 МБ).
• Python 3.11, 3.12 или 3.13 (rembg не поддерживает старее 3.11 и новее 3.13).
• Видеокарта не нужна.
Модель побольше (birefnet-general-lite) на Linux в моём тесте съела больше 6 ГБ памяти и была убита системой, так что на слабых компьютерах её не бери. Пробуй её только при 16 ГБ и больше.

ШАГ 1. Python
Скачай установщик с python.org (раздел Downloads, Windows). В первом окне поставь галочку «Add python.exe to PATH», потом «Install Now».

ШАГ 2. Папка и библиотеки
Нажми Win+R, введи cmd, Enter. В чёрном окне по очереди выполни команды (кнопка «Скопировать команды» ниже вставит их все сразу, последнюю строку «python fc_ai_server.py» выполни после шага 3):
$COMMANDS
Установка библиотек займёт несколько минут.

ШАГ 3. Скрипт сервера
Нажми «Скопировать скрипт» ниже. Открой Блокнот, вставь, выбери «Файл → Сохранить как», папка C:\fc-ai, имя fc_ai_server.py, «Тип файла: Все файлы» (иначе Блокнот допишет .txt). В скрипте только латиница, поэтому кодировка не важна.

ШАГ 4. Запуск
В том же чёрном окне: python fc_ai_server.py
Первый запуск скачивает модель (нужен интернет). Когда появится строка «Running on local URL: http://0.0.0.0:7860», сервер работает. Windows спросит про брандмауэр: разреши «Частные сети». Окно держи открытым, пока пользуешься.
Позже запускать так: cmd, затем cd /d C:\fc-ai, venv\Scripts\activate, python fc_ai_server.py

ШАГ 5. Адрес компьютера
В cmd введи ipconfig. Найди «IPv4-адрес» своей сети, например 192.168.1.20. Если телефон и компьютер связаны через ZeroTier, возьми адрес адаптера ZeroTier.

ШАГ 6. Телефон
Настройки → «ИИ-вырезание» → поле «Адрес моего Space»: http://192.168.1.20:7860 (подставь свой адрес). Поставь «Мой Space» первым в порядке сервисов. Поле токенов оставь пустым.
Телефон и компьютер должны быть в одной сети (или в одной сети ZeroTier). Если не соединяется: проверь брандмауэр Windows и что в Wi‑Fi роутера не включена «изоляция клиентов».

ЗАМЕЧАНИЯ
• Работу сервера и формат ответа я проверил на Linux с Gradio 6. На самом Windows я не запускал, поэтому если на каком-то шаге ошибка, пришли текст из чёрного окна.
• Соединение идёт по обычному http без шифрования, так что только для своей сети или ZeroTier.
""".trimStart()

    private fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    fun show(ctx: Context) {
        val tv = TextView(ctx)
        tv.text = TEXT.replace("\$COMMANDS", COMMANDS.trimEnd())
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad / 2, pad, 0)
        val sv = ScrollView(ctx)
        sv.addView(tv)
        AlertDialog.Builder(ctx)
            .setTitle("Свой ИИ-сервер на Windows")
            .setView(sv)
            .setPositiveButton("Скопировать скрипт") { _, _ -> copy(ctx, "fc_ai_server.py", SCRIPT) }
            .setNeutralButton("Скопировать команды") { _, _ -> copy(ctx, "commands", COMMANDS) }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
