package io.github.glacier_jellyfin.androidtv.core.log

import android.content.Context
import java.io.File

/**
 * Glacier's log, called like `android.util.Log`. Everything goes to Logcat;
 * info, warnings, errors and crashes also go to a small file log in the app's
 * storage (secrets removed, see [Redaction]), which users can send from
 * Settings › System › Diagnostics without adb.
 */
object Log {

    @Volatile
    private var file: LogFile? = null

    /** Starts the file log and records crashes in it. Call once, from `Application.onCreate`. */
    fun install(context: Context) {
        val log = LogFile(File(context.filesDir, "logs"))
        file = log
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { log.crash(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Logcat only: too chatty for the file log. */
    fun d(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.d(tag, message, error)
    }

    fun i(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.i(tag, message, error)
        file?.append('I', tag, message, error)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.w(tag, message, error)
        file?.append('W', tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.e(tag, message, error)
        file?.append('E', tag, message, error)
    }

    /** The file log, oldest line first, cut to its newest [maxBytes] bytes. */
    fun read(maxBytes: Int): String = file?.read(maxBytes).orEmpty()

    /** Deletes the file log and the crash record. */
    fun clear() {
        file?.clear()
    }

    /** When the app last crashed (epoch ms), until the log is sent or cleared; null otherwise. */
    fun lastCrash(): Long? = file?.lastCrash()

    /** The log with the last crash reached its reader; [lastCrash] is null again. */
    fun crashReported() {
        file?.crashReported()
    }

    /** True once after each crash, for a notice at the next start. */
    fun takeCrashNotice(): Boolean = file?.takeCrashNotice() == true
}
