package com.novis.flashcatalog

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Приёмник: устройство без камеры (ТВ-приставка) или компьютер показывает QR с адресом «http://ip:порт/in/ключ/».
 * Отправитель (приложение по QR или браузерная страница по этому же адресу) присылает карточки сюда.
 * Работает по локальной сети, без шифрования; защита только секретным ключом в адресе.
 */
class InboxServer(private val stage: File, val token: String, private val single: Boolean = true, private val onDone: (List<String>) -> Unit) {
    private var ss: ServerSocket? = null
    @Volatile private var closed = false
    private val got = LinkedHashSet<String>()
    val port: Int get() = ss?.localPort ?: 0

    /** Подготовка папки приёма (для встраивания в другой сервер без собственного сокета). */
    fun prepare() { stage.deleteRecursively(); stage.mkdirs() }

    fun start() {
        prepare()
        val s = try { ServerSocket(8766) } catch (e: Exception) { ServerSocket(0) }
        ss = s
        thread(isDaemon = true, name = "inbox-accept") {
            while (!closed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "inbox-client") { handle(c) }
            }
        }
    }

    fun stop() {
        closed = true
        try { ss?.close() } catch (e: Exception) { /* ничего */ }
    }

    private class Limited(private val i: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = i.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = i.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    internal fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun reply(s: Socket, code: String, type: String, body: ByteArray) {
        val o = s.getOutputStream()
        o.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
        o.write(body); o.flush()
    }

    internal fun text(s: Socket, code: String, t: String) = reply(s, code, "text/plain; charset=utf-8", t.toByteArray())

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 30000
                val inp = s.getInputStream().buffered(64 * 1024)
                val line = readLine(inp) ?: return
                val parts = line.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = (parts.getOrNull(1) ?: "").substringBefore('?')
                var len = 0L
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                val base = "/in/$token"
                if (path != base && !path.startsWith("$base/")) { text(s, "404 Not Found", "нет"); return }
                serve(s, inp, method, path.removePrefix(base).removePrefix("/"), len)
            }
        } catch (e: Exception) {
            // обрыв соединения или неверный архив: приём продолжается
        }
    }


    /** Обработка запроса к приёмнику; rest: путь после «/in/ключ/». Тело читается из inp. */
    fun serve(s: Socket, inp: InputStream, method: String, rest: String, len: Long) {
        try {
            when {
                method == "GET" && rest.isEmpty() -> reply(s, "200 OK", "text/html; charset=utf-8", PAGE.toByteArray())
                method == "POST" && rest == "zip" -> {
                    if (len <= 0 || len > 2L * 1024 * 1024 * 1024) { text(s, "400 Bad Request", "размер"); return }
                    val cards = Pack.unzipTo(Limited(inp, len), stage)
                    synchronized(got) { got.addAll(cards) }
                    text(s, "200 OK", "принято карточек: ${cards.size}")
                    finish()
                }
                method == "PUT" && rest.startsWith("f/") -> {
                    val seg = rest.substring(2).split("/")
                    if (seg.size != 2) { text(s, "400 Bad Request", "путь"); return }
                    val card = URLDecoder.decode(seg[0], "UTF-8")
                    val name = URLDecoder.decode(seg[1], "UTF-8")
                    if (card.isBlank() || card.startsWith(".") || card.contains("/") || card.contains("\\") || card.contains("..") || !Pack.FILE_RE.matches(name)) {
                        text(s, "200 OK", "пропущен"); return
                    }
                    if (len < 0 || len > 200L * 1024 * 1024) { text(s, "400 Bad Request", "размер"); return }
                    val dir = File(stage, card); dir.mkdirs()
                    File(dir, name).outputStream().use { o -> Limited(inp, len).copyTo(o) }
                    synchronized(got) { got.add(card) }
                    text(s, "200 OK", "ok")
                }
                method == "POST" && rest == "done" -> { text(s, "200 OK", "ok"); finish() }
                else -> text(s, "404 Not Found", "нет")
            }
        } catch (e: IOException) {
            // обрыв соединения или неверный архив
        }
    }

    private fun finish() {
        val cards = synchronized(got) { got.toList().also { got.clear() } }
        if (cards.isEmpty()) return
        if (single) stop()
        onDone(cards)
    }

    companion object {
        /** Разбор адреса из QR: «http://ip:порт/in/ключ/». */
        fun isInboxUrl(t: String): Boolean = Regex("^http://[^/\\s]+/in/[0-9a-f]{8,}/?$").matches(t.trim())

        private const val PAGE = """<!doctype html><html lang=ru><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
<title>Отправить в Flash Catalog</title><style>
body{font-family:system-ui,sans-serif;background:#f4f6f8;color:#222;margin:0}header{background:#1565c0;color:#fff;padding:14px 18px}h1{margin:0;font-size:20px}
main{max-width:720px;margin:0 auto;padding:14px}.b{background:#fff;border-radius:10px;padding:14px;margin:12px 0;box-shadow:0 1px 3px #0002}
label.btn{display:inline-block;background:#1565c0;color:#fff;border-radius:6px;padding:10px 16px;cursor:pointer;margin:4px 0}input{display:none}
#log{white-space:pre-wrap;font-size:13px}progress{width:100%}
</style></head><body><header><h1>Отправить карточки в Flash Catalog</h1></header><main>
<div class=b><b>Папкой.</b> Выбери папку каталога (или одну карточку): отправятся только фото и текст карточек.<br>
<label class=btn>Выбрать папку<input id=dir type=file webkitdirectory multiple></label></div>
<div class=b><b>Архивом.</b> Файл .zip, который сделало приложение («Скачать всё одним архивом»).<br>
<label class=btn>Выбрать zip<input id=zip type=file accept=.zip></label></div>
<progress id=pr value=0 max=1 style=display:none></progress><div id=log></div>
<script>
var base=location.pathname.replace(/\/?$/,'/'),log=document.getElementById('log'),pr=document.getElementById('pr');
function say(t){log.textContent=t}
document.getElementById('dir').onchange=async function(e){
 var fs=[].slice.call(e.target.files),items=[];
 fs.forEach(function(f){var p=(f.webkitRelativePath||f.name).split('/');var card,name;
  if(p.length>=3){card=p[p.length-2];name=p[p.length-1]}else if(p.length==2){card=p[0];name=p[1]}else return;
  if(/^(photo\.jpg|\.photo\.jpg|\.mask\.png|info\.txt|photo_nobg\.png|additionally-\d+\.jpg)$/.test(name))items.push([card,name,f])});
 if(!items.length){say('В этой папке нет файлов карточек (photo.jpg, info.txt …).');return}
 pr.style.display='';pr.max=items.length;pr.value=0;
 try{for(var i=0;i<items.length;i++){var it=items[i];say('Отправляю '+(i+1)+' из '+items.length+': '+it[0]+'/'+it[1]);
   var r=await fetch(base+'f/'+encodeURIComponent(it[0])+'/'+encodeURIComponent(it[1]),{method:'PUT',body:it[2]});
   if(!r.ok)throw new Error(r.status);pr.value=i+1}
  await fetch(base+'done',{method:'POST',body:''});say('Готово: отправлено файлов '+items.length+'. Карточки появятся на устройстве.')}
 catch(err){say('Ошибка: '+err.message)}};
document.getElementById('zip').onchange=async function(e){var f=e.target.files[0];if(!f)return;say('Отправляю '+f.name+'…');
 try{var r=await fetch(base+'zip',{method:'POST',body:f});say(r.ok?'Готово: '+await r.text():'Ошибка '+r.status)}catch(err){say('Ошибка: '+err.message)}};
</script></main></body></html>"""
    }
}
