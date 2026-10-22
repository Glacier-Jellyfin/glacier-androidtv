package io.github.glacier_jellyfin.androidtv.diagnostics

import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Offers one text file over HTTP on the local network until [close], so a
 * phone or computer can download the log: `http://<address>:<port>/<token>`.
 * The random token keeps others on the network from guessing the address.
 */
class LogServer(address: String, val fileName: String, private val content: ByteArray) : Closeable {

    private val socket = ServerSocket(0)
    private val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    val url = "http://$address:${socket.localPort}/$token"

    val sizeBytes: Int get() = content.size

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
            // One thread per client: a slow one cannot hold up the next download.
            thread(name = "log-server-client", isDaemon = true) {
                client.use { runCatching { answer(it) }.onFailure { e -> Log.w(TAG, "Answering a log download failed", e) } }
            }
        }
    }

    private fun answer(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val request = RequestReader(client.getInputStream().buffered())
        val line = request.line() ?: return
        // Read the headers too: closing with unread input resets the connection before the browser reads the answer.
        do {
            val header = request.line()
        } while (!header.isNullOrEmpty())
        respond(client, line)
    }

    private fun respond(client: Socket, request: String) {
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

    /**
     * Reads a request's lines within limits: a client sending without end, or a byte now and then,
     * is cut off instead of keeping its thread and memory.
     */
    private class RequestReader(private val input: InputStream) {
        private val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        private var total = 0

        /** The next line without its line break; null at the end of the input or past a limit. */
        fun line(): String? {
            val line = StringBuilder()
            while (true) {
                if (total >= MAX_REQUEST_BYTES || System.nanoTime() > deadline) return null
                val byte = input.read()
                if (byte < 0) return null
                total++
                when (byte.toChar()) {
                    '\n' -> return line.toString()
                    '\r' -> Unit
                    else -> line.append(byte.toChar())
                }
            }
        }
    }

    private companion object {
        const val TAG = "LogServer"
        const val TOKEN_BYTES = 16
        const val TIMEOUT_MS = 5_000
        /** A browser's request with its headers is well below this. */
        const val MAX_REQUEST_BYTES = 16 * 1024
    }
}
