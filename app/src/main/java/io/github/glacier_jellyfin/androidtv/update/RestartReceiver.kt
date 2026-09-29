package io.github.glacier_jellyfin.androidtv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.glacier_jellyfin.androidtv.MainActivity

/**
 * Android ends Glacier while it installs an update of itself; once the new
 * version is in place, this opens it again. Only Android 9 allows that:
 * newer versions block activity starts from the background, so there the
 * texts say Glacier closes and the user opens it again.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("RestartReceiver", "Cannot reopen Glacier after the update", it) }
    }
}
