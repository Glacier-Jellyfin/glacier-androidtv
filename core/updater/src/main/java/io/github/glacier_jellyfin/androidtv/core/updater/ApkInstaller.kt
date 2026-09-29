package io.github.glacier_jellyfin.androidtv.core.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/** What the system installer reported back. */
internal sealed interface InstallResult {
    /** The user declined, or left the confirmation with Back. */
    data object Aborted : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/**
 * Hands a checked APK to Android's package installer. Android asks the user
 * to confirm (and, the first time, to allow installs from Glacier); on
 * success it replaces the running app, so success is never reported back.
 */
internal class ApkInstaller(private val context: Context) {

    private val action = "${context.packageName}.UPDATE_INSTALL_STATUS"
    private var receiver: BroadcastReceiver? = null

    fun install(file: File, onResult: (InstallResult) -> Unit) {
        listen(onResult)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Updating itself needs no confirmation once Glacier installed the running version.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(action).setPackage(context.packageName)
                // Mutable: the installer adds its status to the intent.
                val status = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw UpdateException(UpdateError.Install, "Cannot hand the update to the installer", e)
        }
    }

    private fun listen(onResult: (InstallResult) -> Unit) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.i(TAG, "Installer status $status: $message")
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm(intent)
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    PackageInstaller.STATUS_FAILURE_ABORTED -> onResult(InstallResult.Aborted)
                    else -> onResult(InstallResult.Failed(message))
                }
            }
        }
        receiver = newReceiver
        ContextCompat.registerReceiver(context, newReceiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** The installer's confirmation screen; the first time it leads to "allow installs from Glacier". */
    private fun confirm(status: Intent) {
        val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            status.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        confirmation?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        const val TAG = "ApkInstaller"
    }
}
