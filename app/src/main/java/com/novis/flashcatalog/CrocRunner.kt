package com.novis.flashcatalog

import android.content.Context
import java.io.File

/**
 * Запуск wormhole (wormhole-william, совместим с Magic Wormhole), встроенного в приложение как libwormhole.so.
 */
class CrocRunner(private val ctx: Context) {
    companion object {
        const val QR_PREFIX = "fcroc1?c="
        private val WORDS = listOf(
            "apple", "bridge", "candle", "daisy", "eagle", "falcon", "garden", "harbor", "island", "jungle", "kettle", "lemon",
            "mango", "nectar", "orange", "pepper", "quartz", "river", "silver", "tiger", "umbrella", "violet", "window", "yellow",
            "zebra", "anchor", "basket", "cactus", "dragon", "ember", "forest", "glacier", "hammer", "indigo", "jacket", "koala",
            "ladder", "marble", "needle", "ocean", "pillow", "rabbit", "saddle", "tunnel", "velvet", "walnut", "yogurt", "zipper",
            "amber", "blossom", "copper", "dolphin", "engine", "feather", "guitar", "honey", "iceberg", "jasmine", "lantern", "meadow"
        )

        private val CODE_LINE = Regex("code is:\\s*(\\S+)", RegexOption.IGNORE_CASE)
        fun codeFrom(line: String): String? = CODE_LINE.find(line)?.groupValues?.get(1)

        fun parseQr(text: String): String? {
            val t = Pack.unwrapQr(text) ?: return null
            return if (t.startsWith(QR_PREFIX)) java.net.URLDecoder.decode(t.substring(QR_PREFIX.length), "UTF-8").takeIf { it.length >= 6 } else null
        }

        /** QR открывается любым сканером: croc принимает файл прямо в браузере (getcroc.com); наше приложение понимает эту ссылку само. */
        fun qrText(code: String): String = QR_PREFIX + java.net.URLEncoder.encode(code, "UTF-8")

        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val PCT = Regex("(\\d{1,3})(?:\\.\\d+)?%")
        fun clean(s: String): String = ANSI.replace(s, "").trim()
        fun percent(s: String): Int? = PCT.findAll(s).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    fun binary(): File? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libwormhole.so")
        return if (f.exists()) f else null
    }

    /** DNS-серверы сети: в Android нет /etc/resolv.conf, поэтому передаём их программе явно. */
    private fun dnsList(): String {
        val l = ArrayList<String>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            cm.getLinkProperties(net)?.dnsServers?.forEach { it.hostAddress?.let { a -> l.add(a.substringBefore('%')) } }
        } catch (e: Exception) { /* ничего */ }
        l.add("1.1.1.1"); l.add("8.8.8.8")
        return l.distinct().joinToString(",")
    }

    @Volatile var process: Process? = null

    /** Запускает wormhole и построчно отдаёт вывод. Возвращает код выхода. */
    fun run(args: List<String>, answerYes: Boolean, workDir: File, onLine: (String) -> Unit): Int {
        val bin = binary() ?: throw java.io.IOException("обмен через интернет недоступен в этой сборке (нужен 64-битный ARM-телефон)")
        workDir.mkdirs()
        val home = File(ctx.filesDir, "wh_home").also { it.mkdirs() }
        val cmd = ArrayList<String>()
        cmd.add(bin.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["XDG_CONFIG_HOME"] = home.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["FC_DNS"] = dnsList()
        val p = pb.start()
        process = p
        try {
            if (answerYes) p.outputStream.apply { write("y\ny\n".toByteArray()); flush() }
            p.outputStream.close()
        } catch (e: Exception) { /* ничего */ }
        val sb = StringBuilder()
        p.inputStream.reader(Charsets.UTF_8).use { r ->
            val buf = CharArray(512)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val ch = buf[i]
                    if (ch == '\n' || ch == '\r') {
                        if (sb.isNotEmpty()) { onLine(clean(sb.toString())); sb.setLength(0) }
                    } else sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) onLine(clean(sb.toString()))
        }
        val code = p.waitFor()
        process = null
        return code
    }

    fun cancel() {
        try { process?.destroy() } catch (e: Exception) { /* ничего */ }
    }
}
