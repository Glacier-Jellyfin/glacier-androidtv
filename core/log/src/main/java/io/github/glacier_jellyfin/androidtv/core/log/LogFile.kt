package io.github.glacier_jellyfin.androidtv.core.log

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The file behind [Log]: `glacier.log`, moved to `glacier.1.log` when it
 * passes [MAX_FILE_BYTES], so at most twice that is kept. Lines are written on
 * a background thread; a crash is written right away.
 */
internal class LogFile(private val dir: File) {

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "glacier-log").apply { isDaemon = true } }
    private val current = File(dir, "glacier.log")
    private val previous = File(dir, "glacier.1.log")

    /** Holds the time of the last crash until the log is sent. */
    private val crashMark = File(dir, "crash")

    /** Exists from a crash until the next start showed its notice. */
    private val crashNotice = File(dir, "crash-notice")

    fun append(level: Char, tag: String, message: String, error: Throwable?) {
        val line = format(level, tag, message, error)
        runCatching { writer.execute { write(line) } }
    }

    fun crash(thread: String, error: Throwable) {
        // Lines logged just before the crash belong in front of it.
        writer.shutdown()
        runCatching { writer.awaitTermination(CRASH_FLUSH_MS, TimeUnit.MILLISECONDS) }
        write(format('F', "Crash", "Uncaught exception in thread $thread", error))
        synchronized(lock) {
            runCatching {
                crashMark.writeText(System.currentTimeMillis().toString())
                crashNotice.createNewFile()
            }
        }
    }

    fun read(maxBytes: Int): String {
        flush()
        val bytes = synchronized(lock) {
            runCatching { readOrEmpty(previous) + readOrEmpty(current) }.getOrDefault(ByteArray(0))
        }
        if (bytes.size <= maxBytes) return bytes.decodeToString()
        // Start at a line break so no line (or character) is cut in half.
        val cut = bytes.size - maxBytes
        val start = (cut until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }?.plus(1) ?: bytes.size
        return bytes.copyOfRange(start, bytes.size).decodeToString()
    }

    fun clear() {
        flush()
        synchronized(lock) { listOf(current, previous, crashMark, crashNotice).forEach { it.delete() } }
    }

    fun lastCrash(): Long? = synchronized(lock) { runCatching { crashMark.readText().trim().toLong() }.getOrNull() }

    fun crashReported() {
        synchronized(lock) { crashMark.delete() }
    }

    fun takeCrashNotice(): Boolean = synchronized(lock) { crashNotice.delete() }

    /** Waits for lines still queued, briefly. */
    private fun flush() {
        runCatching { writer.submit {}.get(FLUSH_MS, TimeUnit.MILLISECONDS) }
    }

    private fun write(text: String) = synchronized(lock) {
        // Logging must never take the app down.
        runCatching {
            dir.mkdirs()
            if (current.length() > MAX_FILE_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(Redaction.apply(text))
        }
    }

    private fun readOrEmpty(file: File): ByteArray = if (file.exists()) file.readBytes() else ByteArray(0)

    private fun format(level: Char, tag: String, message: String, error: Throwable?): String = buildString {
        append(LocalDateTime.now().format(TIME)).append(' ').append(level).append('/').append(tag)
        append(" [").append(Thread.currentThread().name).append("]: ").append(message).append('\n')
        // Not Log.getStackTraceString: it drops UnknownHostException, a common reason for failures.
        if (error != null) append(error.stackTraceToString().trimEnd()).append('\n')
    }

    private companion object {
        const val MAX_FILE_BYTES = 400_000L
        const val FLUSH_MS = 1_000L
        const val CRASH_FLUSH_MS = 500L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
