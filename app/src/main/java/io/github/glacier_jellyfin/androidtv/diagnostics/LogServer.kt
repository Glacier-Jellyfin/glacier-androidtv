package io.github.glacier_jellyfin.androidtv.diagnostics

import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Offers one text file over HTTP on the local network until [close], so a
 * phone or computer can download the log: `http://<address>:<port>/<token>`.
 * The random token keeps others on the network from guessing the address.
 */
class LogServer(address: String, private val fileName: String, private val content: ByteArray) : Closeable {

    private val socket = ServerSocket(0)
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    val url = "http://$address:${socket.localPort}/$token"

    init {
        thread(name = "log-server", isDaemon = true) { serve() }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun serve() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return // Closed.
            }
            client.use { runCatching { answer(it) }.onFailure { e -> Log.w(TAG, "Answering a log download failed", e) } }
        }
    }

    private fun answer(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine() ?: return
        // Read the headers too: closing with unread input resets the connection before the browser reads the answer.
        while (!reader.readLine().isNullOrEmpty()) Unit
        val parts = request.split(' ')
        val found = parts.getOrNull(0) == "GET" && parts.getOrNull(1) == "/$token"
        Log.i(TAG, if (found) "Log downloaded by ${client.inetAddress.hostAddress}" else "Refused ${parts.getOrNull(0)} from ${client.inetAddress.hostAddress}")
        val body = if (found) content else "Not found\n".toByteArray()
        val head = buildString {
            append(if (found) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            if (found) append("Content-Disposition: attachment; filename=\"$fileName\"\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    private companion object {
        const val TAG = "LogServer"
        const val TOKEN_BYTES = 16
        const val TIMEOUT_MS = 5_000
    }
}
