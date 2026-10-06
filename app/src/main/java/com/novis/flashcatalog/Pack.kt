package com.novis.flashcatalog

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Упаковка карточек для передачи: ZIP, зашифрованный кусками AES-256-GCM (по 1 МБ, чтобы не держать всё в памяти).
 * Ключ хранится только в QR-коде, поэтому файлообменник видит лишь шифр.
 */
object Pack {
    const val CHUNK = 1 shl 20
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte())

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun iv(nonce: ByteArray, idx: Int): ByteArray {
        val iv = ByteArray(12)
        System.arraycopy(nonce, 0, iv, 0, 8)
        iv[8] = (idx ushr 24).toByte(); iv[9] = (idx ushr 16).toByte(); iv[10] = (idx ushr 8).toByte(); iv[11] = idx.toByte()
        return iv
    }

    class EncryptStream(private val out: OutputStream, key: ByteArray) : OutputStream() {
        private val ks = SecretKeySpec(key, "AES")
        private val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var idx = 0
        private var closed = false

        init {
            out.write(MAGIC)
            out.write(nonce)
        }

        private fun emit(last: Boolean) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, ks, GCMParameterSpec(128, iv(nonce, idx)))
            c.updateAAD(byteArrayOf(if (last) 1 else 0))
            val ct = c.doFinal(buf, 0, n)
            out.write(byteArrayOf((ct.size ushr 24).toByte(), (ct.size ushr 16).toByte(), (ct.size ushr 8).toByte(), ct.size.toByte()))
            out.write(ct)
            idx++
            n = 0
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                if (n == CHUNK) emit(false) // выпускаем полный кусок только когда есть ещё данные: так последний кусок помечается точно
                val c = minOf(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, c)
                n += c; o += c; l -= c
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            emit(true)
            out.flush()
            out.close()
        }
    }

    class DecryptStream(private val inp: InputStream, key: ByteArray) : InputStream() {
        private val ks = SecretKeySpec(key, "AES")
        private val din = DataInputStream(inp)
        private val nonce = ByteArray(8)
        private var idx = 0
        private var cur = ByteArray(0)
        private var pos = 0
        private var done = false
        private var pending: ByteArray? = null

        init {
            val m = ByteArray(4)
            din.readFully(m)
            if (!m.contentEquals(MAGIC)) throw IOException("Это не файл обмена Flash Catalog")
            din.readFully(nonce)
            pending = readChunk()
        }

        private fun readChunk(): ByteArray? {
            val len: Int
            try {
                len = din.readInt()
            } catch (e: EOFException) {
                return null
            }
            if (len < 16 || len > CHUNK + 16) throw IOException("Повреждённый файл обмена")
            val ct = ByteArray(len)
            din.readFully(ct)
            return ct
        }

        private fun nextPlain(): Boolean {
            val ct = pending ?: return false
            val nxt = readChunk()
            val last = nxt == null
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, ks, GCMParameterSpec(128, iv(nonce, idx)))
                c.updateAAD(byteArrayOf(if (last) 1 else 0))
                cur = c.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw IOException("Неверный ключ или данные повреждены (или передача оборвалась)")
            }
            idx++
            pos = 0
            pending = nxt
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val r = read(one, 0, 1)
            return if (r <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) {
                if (done) return -1
                if (!nextPlain()) { done = true; return -1 }
            }
            val c = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, c)
            pos += c
            return c
        }

        override fun close() = inp.close()
    }

    // ---------- ZIP ----------

    class Item(val path: String, val open: () -> InputStream)

    fun writeZip(out: OutputStream, items: List<Item>, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val z = ZipOutputStream(out)
        z.setLevel(1)
        for ((i, it) in items.withIndex()) {
            z.putNextEntry(ZipEntry(it.path))
            it.open().use { s -> s.copyTo(z) }
            z.closeEntry()
            progress(i + 1, items.size)
        }
        z.finish()
        z.close()
    }

    private val FILE_RE = Regex("^(photo\\.jpg|\\.photo\\.jpg|\\.mask\\.png|info\\.txt|photo_nobg\\.png|\\.nomedia|additionally-\\d+\\.jpg)$")

    /** Распаковка с проверкой путей. Возвращает названия карточек (папок). */
    fun unzipTo(input: InputStream, dest: File): List<String> {
        val cards = LinkedHashSet<String>()
        val z = ZipInputStream(input)
        var count = 0
        while (true) {
            val e = z.nextEntry ?: break
            if (e.isDirectory) continue
            val parts = e.name.split("/")
            if (parts.size != 2 || parts[0].isBlank() || parts[0].startsWith(".") || parts[0] == ".." ||
                parts[0].contains("\\") || !FILE_RE.matches(parts[1])
            ) continue
            if (++count > 20000) throw IOException("Слишком много файлов")
            val dir = File(dest, parts[0])
            dir.mkdirs()
            val f = File(dir, parts[1])
            var total = 0L
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = z.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > 200L * 1024 * 1024) throw IOException("Файл слишком большой")
                    o.write(buf, 0, r)
                }
            }
            cards.add(parts[0])
        }
        return cards.toList()
    }

    // ---------- содержимое QR ----------

    class Payload(val key: ByteArray, val lan: String?, val relay: String?, val count: Int, val size: Long) {
        fun encode(): String {
            val sb = StringBuilder("fcat1?k=").append(hex(key))
            if (lan != null) sb.append("&l=").append(URLEncoder.encode(lan, "UTF-8"))
            if (relay != null) sb.append("&u=").append(URLEncoder.encode(relay, "UTF-8"))
            sb.append("&n=").append(count).append("&s=").append(size)
            return sb.toString()
        }

        companion object {
            fun parse(text: String): Payload? {
                val t = text.trim()
                if (!t.startsWith("fcat1?")) return null
                val m = HashMap<String, String>()
                for (kv in t.substring(6).split("&")) {
                    val i = kv.indexOf('=')
                    if (i > 0) m[kv.substring(0, i)] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                }
                val k = m["k"] ?: return null
                val key = try { unhex(k) } catch (e: Exception) { return null }
                if (key.size != 32) return null
                val lan = m["l"]?.takeIf { it.startsWith("http://") }
                val relay = m["u"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                if (lan == null && relay == null) return null
                return Payload(key, lan, relay, m["n"]?.toIntOrNull() ?: 0, m["s"]?.toLongOrNull() ?: 0L)
            }
        }
    }

}
