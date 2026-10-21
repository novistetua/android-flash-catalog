package com.novis.flashcatalog

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Один адрес устройства: для показа в диалоге и в тексте «Поделиться». */
class NetAddr(val iface: String, val ip: String) {
    val kind: String
        get() {
            val n = iface.lowercase()
            return when {
                n.startsWith("wlan") || n.startsWith("swlan") -> "Wi‑Fi"
                n.startsWith("ap") -> "точка доступа"
                n.startsWith("zt") -> "ZeroTier"
                n.startsWith("tun") || n.startsWith("wg") || n.startsWith("tailscale") -> "VPN"
                n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") -> "мобильная сеть"
                n.startsWith("eth") -> "Ethernet"
                else -> iface
            }
        }
}

/** Веб-сервер: страница со списком карточек и скачиванием файлов. Доступ только по секретной ссылке. */
class WebServer(private val ctx: Context, private val names: List<String>?, val token: String) {
    val selection: List<String>? get() = names
    private class Card(val name: String, val note: String, val files: List<DocumentFile>)

    private var ss: ServerSocket? = null
    @Volatile private var closed = false
    private var cards: List<Card> = emptyList()
    val port: Int get() = ss?.localPort ?: 0

    // обратный приём: страница даёт кнопку «Отправить карточки на это устройство»; пришедшее добавляется в каталог (обе копии при совпадении имён)
    private val inboxToken = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val inboxStage = java.io.File(ctx.cacheDir, "fcat_webin")
    private val inbox = InboxServer(inboxStage, inboxToken, false) { cards ->
        var added = 0
        for (c in cards) { if (Storage.importCard(ctx, java.io.File(inboxStage, c), c, 2) == null) added++ ; java.io.File(inboxStage, c).deleteRecursively() }
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx, "Получено карточек: $added", android.widget.Toast.LENGTH_LONG).show()
            try { (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(longArrayOf(0, 150, 100, 150), -1) } catch (e: Exception) { }
        }
    }

    /** Путь страницы: «/» без ключа или «/t/ключ/». */
    fun basePath(): String = if (token.isBlank()) "/" else "/t/$token/"

    fun start() {
        inbox.prepare()
        val entries = Storage.list(ctx).filter { names == null || it.name in names }
        val byCard = Storage.cardFiles(ctx, names).groupBy({ it.first }, { it.second })
        cards = entries.map { e -> Card(e.name, e.note, (byCard[e.name] ?: emptyList()).sortedBy { it.name ?: "" }) }
        // постоянный порт, чтобы ссылку можно было сохранить в закладки; если занят, берём любой свободный
        val s = try { ServerSocket(8765) } catch (e: Exception) { ServerSocket(0) }
        ss = s
        thread(isDaemon = true, name = "web-accept") {
            while (!closed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "web-client") { handle(c) }
            }
        }
    }

    fun stop() {
        closed = true
        try { ss?.close() } catch (e: Exception) { /* ничего */ }
    }

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun enc(t: String) = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    private fun mime(n: String): String = when {
        n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
        n.endsWith(".png") -> "image/png"
        n.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun header(out: OutputStream, code: String, type: String, len: Long = -1, extra: String = "") {
        val sb = StringBuilder("HTTP/1.1 $code\r\nContent-Type: $type\r\nConnection: close\r\nCache-Control: no-store\r\n")
        if (len >= 0) sb.append("Content-Length: $len\r\n")
        sb.append(extra).append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun page(): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=ru><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
        sb.append("<title>Flash Catalog</title><link rel='icon' type='image/png' href='data:image/png;base64,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'><style>")
        sb.append("body{font-family:system-ui,sans-serif;background:#f4f6f8;margin:0;color:#222}header{background:#1565c0;color:#fff;padding:14px 18px}")
        sb.append("h1{margin:0;font-size:20px}main{max-width:900px;margin:0 auto;padding:12px}")
        sb.append(".card{background:#fff;border-radius:10px;padding:12px;margin:12px 0;display:flex;gap:14px;box-shadow:0 1px 3px #0002}")
        sb.append(".card img{width:220px;max-width:40%;height:auto;object-fit:contain;background:#ddd;border-radius:6px;align-self:flex-start}")
        sb.append(".card h2{margin:0 0 6px;font-size:17px;word-break:break-all}.note{white-space:pre-wrap;margin:6px 0}")
        sb.append("a{color:#1565c0}.files a{display:inline-block;margin:2px 10px 2px 0;font-size:13px}.all{display:inline-block;margin:8px 0;padding:8px 14px;background:#1565c0;color:#fff;border-radius:6px;text-decoration:none}")
        sb.append("@media(max-width:560px){.card{flex-direction:column}.card img{width:100%;max-width:none}}")
        sb.append("</style></head><body><header><h1>Flash Catalog: карточек ${cards.size}</h1></header><main>")
        sb.append("<a class=all href='app.apk'>Скачать приложение Flash Catalog (APK)</a> ")
        sb.append("<a class=all id=openapp style='display:none' href='#'>Открыть в приложении</a> ")
        sb.append("<script>var h=decodeURIComponent(location.hash.slice(1));if(/android/i.test(navigator.userAgent)&&h.indexOf('fcat1?')==0){var a=document.getElementById('openapp');")
        sb.append("a.href='intent://r?p='+encodeURIComponent(h)+'#Intent;scheme=fcat;package=com.novis.flashcatalog;S.browser_fallback_url='+encodeURIComponent(location.origin+location.pathname+'app.apk')+';end';a.style.display='inline-block';}</script>")
        sb.append("<a class=all href='/in/$inboxToken/'>Отправить карточки на это устройство (обратный обмен)</a> ")
        sb.append("<a class=all href='editor.html'>Страница-редактор для компьютера (Chrome/Edge, скачай и открой с диска)</a> ")
        if (cards.isNotEmpty()) sb.append("<a class=all href='all.html'>Скачать одной HTML-страницей (откроется в любом браузере, без интернета)</a> <a class=all href='all.zip'>Скачать всё одним архивом (zip)</a>")
        for ((i, c) in cards.withIndex()) {
            val thumb = c.files.firstOrNull { it.name == Storage.CUT } ?: c.files.firstOrNull { it.name == Storage.PHOTO }
            sb.append("<div class=card>")
            if (thumb != null) sb.append("<a href='c/$i/${enc(thumb.name ?: "")}'><img loading=lazy src='c/$i/${enc(thumb.name ?: "")}'></a>")
            sb.append("<div><h2>${esc(c.name)}</h2><div class=note>${esc(c.note.ifBlank { "(без аннотации)" })}</div><div class=files>")
            for (f in c.files) {
                val n = f.name ?: continue
                sb.append("<a href='c/$i/${enc(n)}?dl=1'>${esc(n)}</a>")
            }
            sb.append("<a href='c/$i.zip'><b>вся карточка (zip)</b></a></div></div></div>")
        }
        sb.append("</main></body></html>")
        return sb.toString()
    }

    private fun zipTo(out: OutputStream, sel: List<Card>) {
        val items = sel.flatMap { c ->
            c.files.mapNotNull { f ->
                val n = f.name ?: return@mapNotNull null
                Pack.Item(c.name + "/" + n) { ctx.contentResolver.openInputStream(f.uri) ?: throw java.io.IOException("не открыть $n") }
            }
        }
        Pack.writeZip(out, items)
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 20000
                val bin = s.getInputStream().buffered(64 * 1024)
                val line = inbox.readLine(bin) ?: return
                var clen = 0L
                while (true) {
                    val h = inbox.readLine(bin) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", true)) clen = h.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                val method = line.substringBefore(' ')
                val ipath = (line.split(" ").getOrNull(1) ?: "").substringBefore('?')
                if (ipath == "/in/$inboxToken" || ipath.startsWith("/in/$inboxToken/")) {
                    inbox.serve(s, bin, method, ipath.removePrefix("/in/$inboxToken").removePrefix("/"), clen)
                    return
                }
                val out = s.getOutputStream().buffered(64 * 1024)
                val rawPath = line.split(" ").getOrNull(1) ?: ""
                val query = rawPath.substringAfter('?', "")
                val base = basePath()
                if (!rawPath.startsWith(base) && !(token.isNotBlank() && rawPath == "/t/$token")) {
                    header(out, "404 Not Found", "text/plain", 0); out.flush(); return
                }
                val rest = rawPath.substringBefore('?').removePrefix(base.trimEnd('/')).removePrefix("/")
                val dl = "dl=1" in query
                when {
                    rest.isEmpty() -> {
                        val b = page().toByteArray(Charsets.UTF_8)
                        header(out, "200 OK", "text/html; charset=utf-8", b.size.toLong())
                        out.write(b)
                    }
                    rest == "app.apk" -> {
                        val apk = java.io.File(ctx.applicationInfo.sourceDir)
                        header(out, "200 OK", "application/vnd.android.package-archive", apk.length(), "Content-Disposition: attachment; filename=\"${AppVer.apkName(ctx)}\"\r\n")
                        apk.inputStream().use { it.copyTo(out) }
                    }
                    rest == "editor.html" -> {
                        val b = ctx.assets.open("editor.html").use { it.readBytes() }
                        header(out, "200 OK", "text/html; charset=utf-8", b.size.toLong(), "Content-Disposition: attachment; filename=\"FlashCatalogEditor.html\"\r\n")
                        out.write(b)
                    }
                    rest == "all.html" -> {
                        header(out, "200 OK", "text/html; charset=utf-8", -1, "Content-Disposition: attachment; filename=\"FlashCatalog.html\"\r\n")
                        HtmlExport.write(ctx, names, out)
                    }
                    rest == "all.zip" -> {
                        header(out, "200 OK", "application/zip", -1, "Content-Disposition: attachment; filename=\"FlashCatalog.zip\"\r\n")
                        zipTo(out, cards)
                    }
                    Regex("^c/\\d+\\.zip$").matches(rest) -> {
                        val i = rest.substring(2).removeSuffix(".zip").toInt()
                        val c = cards.getOrNull(i)
                        if (c == null) { header(out, "404 Not Found", "text/plain", 0) } else {
                            val fn = enc(c.name) + ".zip"
                            header(out, "200 OK", "application/zip", -1, "Content-Disposition: attachment; filename*=UTF-8''$fn\r\n")
                            zipTo(out, listOf(c))
                        }
                    }
                    Regex("^c/\\d+/.+$").matches(rest) -> {
                        val i = rest.substring(2).substringBefore('/').toInt()
                        val fname = URLDecoder.decode(rest.substringAfter('/').substringAfter('/'), "UTF-8")
                        val f = cards.getOrNull(i)?.files?.firstOrNull { it.name == fname }
                        if (f == null) { header(out, "404 Not Found", "text/plain", 0) } else {
                            val extra = if (dl) "Content-Disposition: attachment; filename*=UTF-8''${enc(fname)}\r\n" else ""
                            header(out, "200 OK", mime(fname), f.length(), extra)
                            ctx.contentResolver.openInputStream(f.uri)?.use { it.copyTo(out) }
                        }
                    }
                    else -> header(out, "404 Not Found", "text/plain", 0)
                }
                out.flush()
            }
        } catch (e: Exception) {
            // клиент закрыл соединение
        }
    }

    companion object {
        /** Все адреса устройства, кроме 127.x и служебных 169.254.x (Wi‑Fi, ZeroTier, VPN, мобильная сеть). */
        fun addresses(): List<NetAddr> {
            val out = ArrayList<NetAddr>()
            val list = try { NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
            for (ni in list) {
                try { if (!ni.isUp || ni.isLoopback) continue } catch (e: Exception) { continue }
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        out.add(NetAddr(ni.name, a.hostAddress ?: continue))
                    }
                }
            }
            return out
        }
    }
}

/** Служба на переднем плане: держит веб-сервер, пока ссылка нужна (до 60 минут или кнопка «Остановить»). */
class ShareService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_NAMES = "names"
        @Volatile var server: WebServer? = null
        private const val CH = "share"
        private const val ID = 77
        private const val LIFETIME_MS = 60L * 60 * 1000

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ShareService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val killer = Runnable { shutdown() }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        return b.setContentTitle("Flash Catalog раздаёт карточки")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Остановить", stopPi).build())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        server?.stop()
        server = null
        stopForeground(true)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handler.removeCallbacks(killer)
            shutdown()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Ссылка на карточки", NotificationManager.IMPORTANCE_LOW))
        }
        val n = buildNotif("Запускаю сервер…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        val names = intent?.getStringArrayExtra(EXTRA_NAMES)?.toList()
        server?.stop()
        val secret = getSharedPreferences("fc", MODE_PRIVATE).getBoolean("web_secret", false)
        val token = if (secret) ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } else ""
        val ws = WebServer(applicationContext, names, token)
        thread {
            try {
                ws.start()
                server = ws
                val a = WebServer.addresses()
                val first = a.firstOrNull { it.kind == "Wi‑Fi" } ?: a.firstOrNull()
                val txt = if (first != null) "http://${first.ip}:${ws.port}${ws.basePath()}" + (if (a.size > 1) " (и ещё ${a.size - 1})" else "") else "нет сети"
                nm.notify(ID, buildNotif("Ссылка: $txt\nРаздача до часа или пока не нажмёшь «Остановить»."))
            } catch (e: Exception) {
                handler.post { shutdown() }
            }
        }
        handler.removeCallbacks(killer)
        handler.postDelayed(killer, LIFETIME_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(killer)
        server?.stop()
        server = null
        super.onDestroy()
    }
}

/** Запуск раздачи и диалог со ссылками. */
object WebShareUi {

    private fun http(method: String, url: String, json: String?): Pair<Int, String> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) FlashCatalog/1.0")
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.connectTimeout = 8000
        c.readTimeout = 10000
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return Pair(code, body)
    }

    /** Короткая ссылка без регистрации: пробуем несколько сервисов по очереди. Возвращает (ссылка, сервис или ошибки). */
    fun makeShort(url: String): Pair<String?, String> {
        val errors = ArrayList<String>()
        val steps = listOf<Pair<String, () -> String?>>(
            "is.gd" to {
                val r = http("GET", "https://is.gd/create.php?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8"), null)
                val j = try { org.json.JSONObject(r.second) } catch (e: Exception) { null }
                j?.optString("shorturl")?.ifBlank { null } ?: run { errors.add("is.gd " + (j?.optString("errormessage")?.take(40) ?: r.first.toString())); null }
            },
            "a777.lt" to {
                val r = http("POST", "https://a777.lt/api/shorten", org.json.JSONObject().put("url", url).toString())
                val j = try { org.json.JSONObject(r.second) } catch (e: Exception) { null }
                j?.optString("short_url")?.ifBlank { null } ?: run { errors.add("a777.lt ${r.first}" + (if (j == null) " (не JSON)" else "")); null }
            },
            "tinyurl.com" to {
                val r = http("GET", "https://tinyurl.com/api-create.php?url=" + java.net.URLEncoder.encode(url, "UTF-8"), null)
                if (r.first in 200..299 && r.second.trim().startsWith("http")) r.second.trim() else { errors.add("tinyurl ${r.first}"); null }
            }
        )
        for ((name, f) in steps) {
            try {
                val u = f()
                if (u != null) return Pair(u, name)
            } catch (e: Exception) {
                errors.add("$name: ${e.message?.take(40)}")
            }
        }
        return Pair(null, errors.joinToString("; "))
    }

    fun start(act: Activity, names: List<String>?) {
        val run = ShareService.server
        if (run != null && run.port > 0 && run.selection == names) {
            show(act, run)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711)
        }
        val i = Intent(act, ShareService::class.java).setAction(ShareService.ACTION_START)
        if (names != null) i.putExtra(ShareService.EXTRA_NAMES, names.toTypedArray())
        if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i) else act.startService(i)
        waitAndShow(act, 0)
    }

    /** Запускает раздачу (или берёт уже работающую с тем же набором карточек) и отдаёт лучший адрес. */
    fun ensure(act: Activity, names: List<String>?, cb: (String?) -> Unit) {
        val run = ShareService.server
        if (!(run != null && run.port > 0 && run.selection == names)) {
            val i = Intent(act, ShareService::class.java).setAction(ShareService.ACTION_START)
            if (names != null) i.putExtra(ShareService.EXTRA_NAMES, names.toTypedArray())
            if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i) else act.startService(i)
        }
        waitUrl(act, names, 0, cb)
    }

    private fun waitUrl(act: Activity, names: List<String>?, tries: Int, cb: (String?) -> Unit) {
        val s = ShareService.server
        if (s != null && s.port > 0 && s.selection == names) {
            val a = WebServer.addresses()
            val best = a.firstOrNull { it.kind == "Wi‑Fi" } ?: a.firstOrNull()
            cb(best?.let { "http://${it.ip}:${s.port}${s.basePath()}" })
            return
        }
        if (tries > 40 || act.isFinishing) { cb(null); return }
        Handler(Looper.getMainLooper()).postDelayed({ waitUrl(act, names, tries + 1, cb) }, 150)
    }

    private fun waitAndShow(act: Activity, tries: Int) {
        val s = ShareService.server
        if (s != null && s.port > 0) {
            show(act, s)
            return
        }
        if (tries > 40) {
            android.widget.Toast.makeText(act, "Не удалось запустить раздачу", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ if (!act.isFinishing) waitAndShow(act, tries + 1) }, 150)
    }

    private fun show(act: Activity, s: WebServer) {
        val addrs = WebServer.addresses()
        if (addrs.isEmpty()) {
            AlertDialog.Builder(act).setTitle("Нет сети").setMessage("У устройства нет адреса в сети: включи Wi‑Fi или ZeroTier.")
                .setPositiveButton("Остановить") { _, _ -> ShareService.stop(act) }.show()
            return
        }
        val lines = addrs.map { "${it.kind} (${it.iface}): http://${it.ip}:${s.port}${s.basePath()}" }
        val best = addrs.firstOrNull { it.kind == "Wi‑Fi" } ?: addrs.first()
        val bestUrl = "http://${best.ip}:${s.port}${s.basePath()}"
        var shortUrl: String? = null
        fun text(): String = "Flash Catalog (открывай в браузере; адрес зависит от того, в какой сети ты находишься):\n" +
            lines.joinToString("\n") + (shortUrl?.let { "\nКороткая ссылка: $it" } ?: "")
        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(act)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        val tv = android.widget.TextView(act)
        tv.text = lines.joinToString("\n\n") + "\n\nОткрой ссылку в браузере или наведи на QR любой сканер: страница даёт скачать карточки и само приложение прямо с этого телефона, интернет получателю не нужен. Раздача до часа, пока видно уведомление. Ссылка без ключа и без шифрования, поэтому только для своей сети или ZeroTier."
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        box.addView(tv)
        val shortTv = android.widget.TextView(act)
        shortTv.textSize = 14f
        shortTv.setTextIsSelectable(true)
        shortTv.setPadding(0, pad / 2, 0, 0)
        val typed = "${best.ip}:${s.port}${s.basePath()}"
        fun refreshShort() {
            shortTv.text = "Ввести вручную в браузере: $typed" +
                (shortUrl?.let { "\n\nКороткая ссылка через интернет (работает в любой сети, 72 часа): $it" } ?: "")
        }
        refreshShort()
        box.addView(shortTv)
        val iv = android.widget.ImageView(act)
        iv.setImageBitmap(Qr.make(bestUrl, 600))
        iv.setBackgroundColor(0xFFFFFFFF.toInt())
        box.addView(iv, android.widget.LinearLayout.LayoutParams((240 * act.resources.displayMetrics.density).toInt(), (240 * act.resources.displayMetrics.density).toInt()).also { it.topMargin = pad / 2; it.gravity = android.view.Gravity.CENTER_HORIZONTAL })
        val sv = android.widget.ScrollView(act)
        sv.addView(box)
        AlertDialog.Builder(act)
            .setTitle("Ссылка работает")
            .setView(sv)
            .setPositiveButton("Поделиться") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text())
                act.startActivity(Intent.createChooser(send, "Отправить ссылку"))
            }
            .setNeutralButton("Копировать") { _, _ ->
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("links", text()))
                android.widget.Toast.makeText(act, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Остановить") { _, _ -> ShareService.stop(act) }
            .show()
    }
}
