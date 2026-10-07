package com.novis.flashcatalog

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Клиент публичных Gradio-сервисов вырезания фона (Hugging Face Spaces).
 * Берём у сервиса только прозрачность (маску); пиксели флешки остаются из оригинала на телефоне.
 */
object AiMask {
    class Space(val host: String, val api: String)

    var spaces = listOf(
        Space("https://not-lain-background-removal.hf.space", "png"),
        Space("https://briaai-bria-rmbg-2-0.hf.space", "image")
    )
    private const val UA = "FlashCatalog/1.0 (+android)"

    class AiException(msg: String) : IOException(msg)

    /** Настройки из экрана «Настройки» (SharedPreferences "fc"). */
    class Config(
        val order: List<String>,          // порядок: own, public, removebg
        val ownUrl: String,
        val tokOwn: List<String>,
        val tokPublic: List<String>,
        val keysRemoveBg: List<String>
    )

    fun splitLines(t: String?): List<String> =
        (t ?: "").split('\n', ',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun loadConfig(prefs: android.content.SharedPreferences): Config {
        val known = listOf("own", "public", "removebg")
        val saved = (prefs.getString("ai_order", "") ?: "").split(',').filter { it in known }
        val order = saved + known.filter { it !in saved }
        return Config(
            order,
            (prefs.getString("own_space", "") ?: "").trim(),
            splitLines(prefs.getString("tok_own", "")),
            splitLines(prefs.getString("tok_public", "")),
            splitLines(prefs.getString("tok_removebg", ""))
        )
    }

    private var curToken = ""

    private fun isHfHost(host: String): Boolean {
        val h = try { URL(host).host.lowercase() } catch (e: Exception) { return false }
        return h.endsWith(".hf.space") || h == "huggingface.co" || h.endsWith(".huggingface.co")
    }

    /** Токен отправляем только на серверы Hugging Face. */
    private fun auth(c: HttpURLConnection, url: String) {
        if (curToken.isNotBlank() && url.startsWith("https://") && isHfHost(url)) {
            c.setRequestProperty("Authorization", "Bearer " + curToken.trim())
        }
    }

    private fun isQuota(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return "лимит" in m || "quota" in m || "429" in m || "zerogpu" in m || "401" in m || "403" in m
    }

    /** Возвращает PNG с прозрачным фоном от сервиса. Перебирает сервисы в заданном порядке, а внутри — токены. */
    fun requestCutout(jpeg: ByteArray, cfg: Config, progress: (String) -> Unit): ByteArray {
        val errors = ArrayList<String>()
        for (svc in cfg.order) {
            when (svc) {
                "own" -> {
                    val own = cfg.ownUrl.trimEnd('/')
                    if (!(own.startsWith("https://") || own.startsWith("http://"))) continue
                    val tokens = if (cfg.tokOwn.isEmpty()) listOf("") else cfg.tokOwn
                    for ((ti, t) in tokens.withIndex()) {
                        curToken = t
                        for (api in listOf("png", "image")) {
                            try {
                                progress("Свой Space" + tokTag(ti, tokens.size) + ": отправляю фото…")
                                return one(Space(own, api), jpeg, progress)
                            } catch (e: Exception) {
                                errors.add("свой Space" + tokTag(ti, tokens.size) + ": " + friendly(e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                }
                "public" -> {
                    val tokens = if (cfg.tokPublic.isEmpty()) listOf("") else cfg.tokPublic
                    for ((ti, t) in tokens.withIndex()) {
                        curToken = t
                        for (sp in spaces) {
                            try {
                                progress("${label(sp.host)}" + tokTag(ti, tokens.size) + ": отправляю фото…")
                                return one(sp, jpeg, progress)
                            } catch (e: Exception) {
                                errors.add(label(sp.host) + tokTag(ti, tokens.size) + ": " + friendly(e.message ?: e.javaClass.simpleName))
                            }
                        }
                    }
                }
                "removebg" -> {
                    for ((ki, k) in cfg.keysRemoveBg.withIndex()) {
                        try {
                            progress("remove.bg" + tokTag(ki, cfg.keysRemoveBg.size) + ": отправляю фото…")
                            return removeBg(jpeg, k)
                        } catch (e: Exception) {
                            errors.add("remove.bg" + tokTag(ki, cfg.keysRemoveBg.size) + ": " + friendly(e.message ?: e.javaClass.simpleName))
                        }
                    }
                }
            }
        }
        curToken = ""
        throw AiException(if (errors.isEmpty()) "ни один сервис не настроен" else errors.distinct().joinToString("\n"))
    }

    private fun tokTag(i: Int, n: Int) = if (n > 1) " (токен ${i + 1})" else ""

    private fun label(host: String): String =
        try { URL(host).host.removeSuffix(".hf.space") } catch (e: Exception) { host }

    private fun removeBg(jpeg: ByteArray, key: String): ByteArray {
        val boundary = "----fc" + UUID.randomUUID().toString().replace("-", "")
        fun part(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
        val head = (part("size", "preview") + part("format", "png") +
            "--$boundary\r\nContent-Disposition: form-data; name=\"image_file\"; filename=\"flash.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = URL("https://api.remove.bg/v1.0/removebg").openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-Api-Key", key.trim())
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(head.size + jpeg.size + tail.size)
        try {
            c.outputStream.use { it.write(head); it.write(jpeg); it.write(tail) }
            val code = c.responseCode
            if (code == 200) return c.inputStream.use { it.readBytes() }
            val text = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            throw AiException(when (code) {
                402 -> "лимит бесплатных обработок исчерпан"
                401, 403 -> "ключ не подошёл"
                429 -> "слишком много запросов"
                else -> "HTTP $code ${text.take(100)}"
            })
        } finally {
            c.disconnect()
        }
    }

    private fun friendly(m: String): String {
        val l = m.lowercase()
        return when {
            "quota" in l || "zerogpu" in l || "http 429" in l -> "дневной бесплатный лимит сервиса исчерпан"
            "http 401" in l || "http 403" in l -> "доступ запрещён (проверь токен)"
            "http 404" in l || "http 502" in l || "http 503" in l -> "сервис сейчас недоступен или спит"
            "timeout" in l || "timed out" in l -> "сервис долго не отвечает"
            "unable to resolve" in l || "unknownhost" in l || "failed to connect" in l -> "нет доступа в интернет"
            else -> m
        }
    }

    private fun one(sp: Space, jpeg: ByteArray, progress: (String) -> Unit): ByteArray {
        // 1. загрузка файла на сервис
        val path = upload(sp.host, jpeg)
        // 2. постановка задачи
        progress("Жду ответ сервиса (первый запуск может занять до минуты)…")
        val body = JSONObject().put(
            "data",
            JSONArray().put(JSONObject().put("path", path).put("meta", JSONObject().put("_type", "gradio.FileData")))
        ).toString()
        val ev = JSONObject(httpPostJson("${sp.host}/gradio_api/call/${sp.api}", body)).getString("event_id")
        // 3. ожидание результата (SSE)
        val data = readEvent("${sp.host}/gradio_api/call/${sp.api}/$ev")
        val url = pickFile(JSONArray(data), sp.host) ?: throw AiException("сервис не вернул картинку")
        progress("Получаю результат…")
        return get(url)
    }

    internal fun pickFile(arr: JSONArray, host: String): String? {
        val found = ArrayList<String>()
        fun walk(x: Any?) {
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
                is JSONObject -> {
                    val u = x.optString("url", "")
                    val p = x.optString("path", "")
                    if (u.startsWith("http")) found.add(u)
                    else if (p.isNotEmpty()) found.add("$host/gradio_api/file=$p")
                }
            }
        }
        walk(arr)
        // прозрачность лежит в PNG; берём последний PNG, иначе первый файл
        return found.lastOrNull { it.lowercase().substringBefore('?').endsWith(".png") } ?: found.firstOrNull()
    }

    private fun upload(host: String, jpeg: ByteArray): String {
        val boundary = "----fc" + UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"flash.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = URL("$host/gradio_api/upload").openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        auth(c, host)
        c.setFixedLengthStreamingMode(head.size + jpeg.size + tail.size)
        try {
            c.outputStream.use { it.write(head); it.write(jpeg); it.write(tail) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw AiException("загрузка: HTTP $code")
            return JSONArray(text).getString(0)
        } finally {
            c.disconnect()
        }
    }

    private fun httpPostJson(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "application/json")
        auth(c, url)
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw AiException("запрос: HTTP $code ${text.take(120)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun readEvent(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 150000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/event-stream")
        auth(c, url)
        try {
            if (c.responseCode != 200) throw AiException("результат: HTTP ${c.responseCode}")
            var event = ""
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            val d = line.substring(5).trim()
                            if (event == "complete") return d
                            if (event == "error") throw AiException(if (d == "null" || d.isEmpty()) "сервис вернул ошибку (чаще всего это исчерпанный дневной лимит GPU)" else d.take(200))
                        }
                    }
                }
            }
            throw AiException("сервис закрыл соединение без ответа")
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        auth(c, url)
        try {
            if (c.responseCode != 200) throw AiException("скачивание: HTTP ${c.responseCode}")
            val b = c.inputStream.use { it.readBytes() }
            if (b.size > 40_000_000) throw AiException("ответ слишком большой")
            return b
        } finally {
            c.disconnect()
        }
    }
}
