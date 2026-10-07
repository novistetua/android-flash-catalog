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
    private class Card(val name: String, val note: String, val files: List<DocumentFile>)

    private var ss: ServerSocket? = null
    @Volatile private var closed = false
    private var cards: List<Card> = emptyList()
    val port: Int get() = ss?.localPort ?: 0

    fun start() {
        val entries = Storage.list(ctx).filter { names == null || it.name in names }
        val byCard = Storage.cardFiles(ctx, names).groupBy({ it.first }, { it.second })
        cards = entries.map { e -> Card(e.name, e.note, (byCard[e.name] ?: emptyList()).sortedBy { it.name ?: "" }) }
        val s = ServerSocket(0)
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
        sb.append("<title>Flash Catalog</title><style>")
        sb.append("body{font-family:system-ui,sans-serif;background:#f4f6f8;margin:0;color:#222}header{background:#1565c0;color:#fff;padding:14px 18px}")
        sb.append("h1{margin:0;font-size:20px}main{max-width:900px;margin:0 auto;padding:12px}")
        sb.append(".card{background:#fff;border-radius:10px;padding:12px;margin:12px 0;display:flex;gap:14px;box-shadow:0 1px 3px #0002}")
        sb.append(".card img{width:220px;max-width:40%;height:auto;object-fit:contain;background:#ddd;border-radius:6px;align-self:flex-start}")
        sb.append(".card h2{margin:0 0 6px;font-size:17px;word-break:break-all}.note{white-space:pre-wrap;margin:6px 0}")
        sb.append("a{color:#1565c0}.files a{display:inline-block;margin:2px 10px 2px 0;font-size:13px}.all{display:inline-block;margin:8px 0;padding:8px 14px;background:#1565c0;color:#fff;border-radius:6px;text-decoration:none}")
        sb.append("@media(max-width:560px){.card{flex-direction:column}.card img{width:100%;max-width:none}}")
        sb.append("</style></head><body><header><h1>Flash Catalog: карточек ${cards.size}</h1></header><main>")
        sb.append("<a class=all href='all.zip'>Скачать всё одним архивом (zip)</a>")
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
                val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = inp.readLine() ?: return
                while (true) {
                    val h = inp.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val out = s.getOutputStream().buffered(64 * 1024)
                val rawPath = line.split(" ").getOrNull(1) ?: ""
                val query = rawPath.substringAfter('?', "")
                val prefix = "/t/$token/"
                if (!rawPath.startsWith(prefix) && rawPath != "/t/$token") {
                    header(out, "404 Not Found", "text/plain", 0); out.flush(); return
                }
                val rest = rawPath.substringBefore('?').removePrefix("/t/$token").removePrefix("/")
                val dl = "dl=1" in query
                when {
                    rest.isEmpty() -> {
                        val b = page().toByteArray(Charsets.UTF_8)
                        header(out, "200 OK", "text/html; charset=utf-8", b.size.toLong())
                        out.write(b)
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
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        val n = b.setContentTitle("Flash Catalog раздаёт карточки")
            .setContentText("Ссылка работает, пока это уведомление здесь (до часа)")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Остановить", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        val names = intent?.getStringArrayExtra(EXTRA_NAMES)?.toList()
        server?.stop()
        val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ws = WebServer(applicationContext, names, token)
        thread {
            try {
                ws.start()
                server = ws
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
    fun start(act: Activity, names: List<String>?) {
        if (Build.VERSION.SDK_INT >= 33 && act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711)
        }
        val i = Intent(act, ShareService::class.java).setAction(ShareService.ACTION_START)
        if (names != null) i.putExtra(ShareService.EXTRA_NAMES, names.toTypedArray())
        if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i) else act.startService(i)
        waitAndShow(act, 0)
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
        val lines = addrs.map { "${it.kind} (${it.iface}): http://${it.ip}:${s.port}/t/${s.token}/" }
        val text = "Карточки Flash Catalog (открывай в браузере; адрес зависит от того, в какой сети ты находишься):\n" + lines.joinToString("\n")
        AlertDialog.Builder(act)
            .setTitle("Ссылка работает")
            .setMessage(lines.joinToString("\n\n") + "\n\nОткрой нужную ссылку в браузере на компьютере. Она действует до часа, пока видно уведомление; ссылку знает только тот, кому ты её отправишь. Передача по открытому http без шифрования, поэтому только для своей сети или ZeroTier.")
            .setPositiveButton("Поделиться") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                act.startActivity(Intent.createChooser(send, "Отправить ссылку"))
            }
            .setNeutralButton("Копировать") { _, _ ->
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("links", text))
                android.widget.Toast.makeText(act, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Остановить") { _, _ -> ShareService.stop(act) }
            .show()
    }
}
