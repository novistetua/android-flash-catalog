package com.novis.flashcatalog

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Передача через анонимные файлообменники. Файл уже зашифрован, ключ там не светится. */
object Relay {
    var litterboxUrl = "https://litterbox.catbox.moe/resources/internals/api.php"
    var tmpfilesUrl = "https://tmpfiles.org/api/v1/upload"
    private const val UA = "FlashCatalog/1.0 (+android)"
    @Volatile private var uploadName = "fcat.bin"
    @Volatile private var uploadTime = "72h"

    fun upload(file: File, progress: (Long, Long) -> Unit, name: String = "fcat.bin", time: String = "72h"): String {
        uploadName = name; uploadTime = time
        val errors = ArrayList<String>()
        try {
            return viaLitterbox(file, progress)
        } catch (e: Exception) {
            errors.add("litterbox: ${e.message}")
        }
        try {
            return viaTmpfiles(file, progress)
        } catch (e: Exception) {
            errors.add("tmpfiles: ${e.message}")
        }
        throw IOException(errors.joinToString("; "))
    }

    private fun viaLitterbox(file: File, progress: (Long, Long) -> Unit): String {
        val body = post(litterboxUrl, mapOf("reqtype" to "fileupload", "time" to uploadTime), "fileToUpload", file, progress).trim()
        if (!body.startsWith("http")) throw IOException("неожиданный ответ")
        return body
    }

    private fun viaTmpfiles(file: File, progress: (Long, Long) -> Unit): String {
        val body = post(tmpfilesUrl, emptyMap(), "file", file, progress)
        val url = JSONObject(body).getJSONObject("data").getString("url")
        return if (url.contains("/dl/")) url else url.replaceFirst(Regex("^(https?://[^/]+)/"), "$1/dl/")
    }

    private fun post(url: String, fields: Map<String, String>, fileField: String, file: File, progress: (Long, Long) -> Unit): String {
        val boundary = "----fc" + UUID.randomUUID().toString().replace("-", "")
        val pre = StringBuilder()
        for ((k, v) in fields) {
            pre.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        pre.append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$uploadName\"\r\nContent-Type: ${if (uploadName.endsWith(".html")) "text/html" else "application/octet-stream"}\r\n\r\n")
        val head = pre.toString().toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val total = head.size + file.length() + tail.size
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(total)
        try {
            c.outputStream.use { out ->
                out.write(head)
                var sent = 0L
                val flen = file.length()
                file.inputStream().use { i ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = i.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        sent += r
                        progress(sent, flen)
                    }
                }
                out.write(tail)
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Скачивание в файл. */
    fun download(url: String, dest: File, connectMs: Int, progress: (Long, Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            copy(c.inputStream, dest, total, progress)
        } finally {
            c.disconnect()
        }
    }

    private fun copy(i: InputStream, dest: File, total: Long, progress: (Long, Long) -> Unit) {
        i.use { s ->
            dest.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (true) {
                    val r = s.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    got += r
                    progress(got, total)
                }
            }
        }
    }
}
