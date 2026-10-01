package io.github.glacier_jellyfin.androidtv.core.log

import android.content.Context
import android.content.SharedPreferences
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

    private var settings: SharedPreferences? = null

    /** Until when (epoch ms) debug lines go to the file log too; see [setVerbose]. */
    @Volatile
    private var verboseUntil = 0L

    /** Detailed logging is on: callers may add expensive debug output, e.g. Media3's event log. */
    val verbose: Boolean get() = System.currentTimeMillis() < verboseUntil

    /** Starts the file log and records crashes in it. Call once, from `Application.onCreate`. */
    fun install(context: Context) {
        val log = LogFile(File(context.filesDir, "logs"))
        file = log
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = prefs
        verboseUntil = prefs.getLong(KEY_VERBOSE_UNTIL, 0L)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { log.crash(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Logcat; the file log only while [verbose]. */
    fun d(tag: String, message: String, error: Throwable? = null) {
        android.util.Log.d(tag, message, error)
        if (verbose) file?.append('D', tag, message, error)
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

    /**
     * Detailed logging for the next [VERBOSE_MS] (24 h), so it cannot be
     * forgotten and fill the log for good; off at once with false.
     */
    fun setVerbose(on: Boolean) {
        verboseUntil = if (on) System.currentTimeMillis() + VERBOSE_MS else 0L
        settings?.edit()?.putLong(KEY_VERBOSE_UNTIL, verboseUntil)?.apply()
        i(TAG, if (on) "Detailed logging on" else "Detailed logging off")
    }

    /** When detailed logging ends (epoch ms), null while it is off. */
    fun verboseUntil(): Long? = verboseUntil.takeIf { verbose }

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

    private const val TAG = "Log"
    private const val PREFS = "log"
    private const val KEY_VERBOSE_UNTIL = "verbose_until"
    private const val VERBOSE_MS = 24 * 60 * 60 * 1000L
}
