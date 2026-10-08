package com.novis.flashcatalog

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Запуск croc (https://github.com/schollz/croc), встроенного в приложение как libcroc.so.
 * Секретный код передаём через переменную CROC_SECRET (так требует безопасный режим croc).
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

        /** Код вида 4821-river-tiger-honey (английские слова, понятны всем); croc требует минимум 6 символов. */
        fun newCode(): String {
            val r = SecureRandom()
            return "%04d-%s-%s-%s".format(r.nextInt(10000), WORDS[r.nextInt(WORDS.size)], WORDS[r.nextInt(WORDS.size)], WORDS[r.nextInt(WORDS.size)])
        }

        fun parseQr(text: String): String? {
            val t = Pack.unwrapQr(text) ?: return null
            return if (t.startsWith(QR_PREFIX)) java.net.URLDecoder.decode(t.substring(QR_PREFIX.length), "UTF-8").takeIf { it.length >= 6 } else null
        }

        /** QR открывается любым сканером: croc принимает файл прямо в браузере (getcroc.com); наше приложение понимает эту ссылку само. */
        fun qrText(code: String): String = "https://getcroc.com/?code=" + java.net.URLEncoder.encode(code, "UTF-8")

        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val PCT = Regex("(\\d{1,3})%")
        fun clean(s: String): String = ANSI.replace(s, "").trim()
        fun percent(s: String): Int? = PCT.findAll(s).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

    private fun resolve(host: String): String? = try {
        java.net.InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }?.hostAddress
    } catch (e: Exception) { null }

    private fun relayAddress(): String {
        val own = ctx.getSharedPreferences("fc", Context.MODE_PRIVATE).getString("croc_relay", "")?.trim().orEmpty()
        if (own.isNotEmpty()) {
            val host = own.substringBeforeLast(":", own).let { if (own.contains(":")) it else own }
            val port = if (own.contains(":")) own.substringAfterLast(":") else "9009"
            return (resolve(host) ?: host) + ":" + port
        }
        return (resolve("croc.schollz.com") ?: "142.132.189.179") + ":9009"
    }

    private fun relayPass(): String? =
        ctx.getSharedPreferences("fc", Context.MODE_PRIVATE).getString("croc_pass", "")?.trim()
            ?.takeIf { it.isNotEmpty() && ctx.getSharedPreferences("fc", Context.MODE_PRIVATE).getString("croc_relay", "")!!.isNotBlank() }

    @Volatile private var cancelled = false

    fun binary(): File? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libcroc.so")
        return if (f.exists()) f else null
    }

    @Volatile var process: Process? = null

    /** Запускает croc и построчно отдаёт вывод. Возвращает код выхода. */
    fun run(args: List<String>, secret: String, workDir: File, onLine: (String) -> Unit): Int {
        cancelled = false
        var attempt = 0
        while (true) {
            var limited = false
            val rc = runOnce(args, secret, workDir) { line ->
                if (line.contains("rate limited", true)) limited = true
                onLine(line)
            }
            // Общий ретранслятор иногда отвечает «rate limited» (слишком много обращений с одного адреса): ждём и пробуем снова
            if (rc != 0 && limited && !cancelled && attempt < 3) {
                attempt++
                val wait = 15 * attempt
                onLine("ретранслятор просит подождать (rate limited), повтор $attempt из 3 через $wait с…")
                var t = 0
                while (t < wait && !cancelled) { Thread.sleep(1000); t++ }
                if (cancelled) return rc
                continue
            }
            return rc
        }
    }

    private fun runOnce(args: List<String>, secret: String, workDir: File, onLine: (String) -> Unit): Int {
        val bin = binary() ?: throw java.io.IOException("croc недоступен в этой сборке (нужен 64-битный ARM-телефон)")
        workDir.mkdirs()
        val home = File(ctx.filesDir, "croc_home").also { it.mkdirs() }
        val cmd = ArrayList<String>()
        cmd.add(bin.absolutePath)
        cmd.addAll(listOf("--ignore-stdin", "--internal-dns"))
        // На Android croc сам не может найти адрес ретранслятора (нет /etc/resolv.conf), поэтому узнаём его здесь и передаём явно
        val relay = relayAddress()
        cmd.addAll(listOf("--relay", relay))
        relayPass()?.let { cmd.addAll(listOf("--pass", it)) }
        onLine("ретранслятор: $relay")
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["XDG_CONFIG_HOME"] = home.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["CROC_SECRET"] = secret
        val p = pb.start()
        process = p
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
        cancelled = true
        try { process?.destroy() } catch (e: Exception) { /* ничего */ }
    }
}
