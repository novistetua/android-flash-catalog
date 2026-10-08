package com.novis.flashcatalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import kotlin.concurrent.thread

/** Отправка карточек через интернет (wormhole) в фоне: работает с уведомлением и кнопкой «Остановить», пока не придёт получатель. */
class CrocSendService : Service() {

    class State {
        @Volatile var code = ""
        @Volatile var cards = 0
        @Volatile var phase = "Упаковываю карточки…"
        @Volatile var pct = -1
        @Volatile var done = false
        @Volatile var ok = false
        @Volatile var stopped = false
        val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_NAMES = "names"
        @Volatile var state: State? = null
        val active: Boolean get() = state?.let { !it.done } == true
        private const val CH = "share"
        private const val ID = 78

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CrocSendService::class.java).setAction(ACTION_STOP))
        }
    }

    private var runner: CrocRunner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String, ongoing: Boolean): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setContentTitle("Flash Catalog: передача через интернет")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            val pi = PendingIntent.getService(
                this, 2, Intent(this, CrocSendService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(0, "Остановить", pi).build())
        }
        return b.build()
    }

    private fun post(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            state?.stopped = true
            runner?.cancel()
            if (state == null || state?.done == true) {
                stopForeground(true)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Ссылка и передача", NotificationManager.IMPORTANCE_LOW))
        val st = State()
        state = st
        val n = notif("Готовлю карточки…", true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        val names = intent?.getStringArrayExtra(EXTRA_NAMES)?.toList()
        val app = applicationContext
        thread(name = "croc-send") {
            val dir = File(cacheDir, "croc_send")
            try {
                val r = CrocRunner(app)
                runner = r
                if (r.binary() == null) throw java.io.IOException("обмен через интернет недоступен в этой сборке (нужен 64-битный ARM-телефон)")
                dir.deleteRecursively(); dir.mkdirs()
                val files = Storage.cardFiles(app, names)
                if (files.isEmpty()) throw IllegalStateException("нет файлов для отправки")
                val items = files.map { (card, f) ->
                    Pack.Item(card + "/" + f.name) {
                        app.contentResolver.openInputStream(f.uri) ?: throw java.io.IOException("не открыть ${f.name}")
                    }
                }
                val zip = File(dir, "FlashCatalog-cards.zip")
                zip.outputStream().buffered().use { Pack.writeZip(it, items) }
                st.cards = files.map { it.first }.distinct().size
                st.phase = "Жду получателя"
                post(notif("Готовлю код (карточек: ${st.cards})…", true))
                val rc = r.run(listOf("send", zip.name), false, dir) { line ->
                    if (line.isNotBlank()) {
                        CrocRunner.codeFrom(line)?.let { c ->
                            st.code = c
                            st.phase = "Жду получателя"
                            post(notif("Код: $c (карточек: ${st.cards}). Жду получателя.", true))
                        }
                        st.log.add(line.take(400))
                        if (st.log.size > 40) st.log.removeAt(0)
                        CrocRunner.percent(line)?.let { p ->
                            st.pct = p
                            st.phase = "Передаю: $p%"
                        }
                    }
                }
                st.ok = rc == 0 && !st.stopped
                st.phase = when {
                    st.stopped -> "Остановлено"
                    rc == 0 -> "Отправлено"
                    else -> "Передача завершилась с кодом $rc"
                }
            } catch (e: Exception) {
                st.phase = "Ошибка: ${e.message}"
            } finally {
                dir.deleteRecursively()
                st.done = true
                stopForeground(true)
                post(notif(st.phase, false))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runner?.cancel()
        super.onDestroy()
    }
}
