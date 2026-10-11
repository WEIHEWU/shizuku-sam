package moe.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import rikka.shizuku.Shizuku

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        if (intent.action == "${BuildConfig.APPLICATION_ID}.STOP" && Shizuku.pingBinder()) {
            runCatching { Shizuku.exit() }
        }
    }
}
