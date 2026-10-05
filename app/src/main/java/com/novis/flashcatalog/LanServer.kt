package com.novis.flashcatalog

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Крошечный HTTP-сервер: по секретному пути отдаёт один зашифрованный файл по локальной сети. */
class LanServer(private val file: File, private val token: String) {
    private var ss: ServerSocket? = null
    @Volatile private var closed = false
    val port: Int get() = ss?.localPort ?: 0

    fun start() {
        val s = ServerSocket(0)
        ss = s
        thread(isDaemon = true, name = "lan-accept") {
            while (!closed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "lan-client") { handle(c) }
            }
        }
    }

    fun stop() {
        closed = true
        try { ss?.close() } catch (e: Exception) { /* ничего */ }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 15000
                val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = inp.readLine() ?: return
                while (true) {
                    val h = inp.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val path = line.split(" ").getOrNull(1) ?: ""
                val out = s.getOutputStream()
                when (path) {
                    "/$token" -> {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nConnection: close\r\n\r\n").toByteArray())
                        file.inputStream().use { it.copyTo(out) }
                    }
                    "/$token/ping" -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                out.flush()
            }
        } catch (e: Exception) {
            // клиент оборвал соединение
        }
    }

    companion object {
        /** Адрес этого устройства в локальной сети (Wi‑Fi или точка доступа). */
        fun localIp(): String? {
            val list = try { NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
            var best: String? = null
            for (ni in list) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                } catch (e: Exception) { continue }
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val n = ni.name.lowercase()
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")) return a.hostAddress
                        if (best == null) best = a.hostAddress
                    }
                }
            }
            return best
        }
    }
}
