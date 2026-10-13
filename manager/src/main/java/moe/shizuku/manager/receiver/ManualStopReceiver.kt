package moe.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        if (intent.action == "${BuildConfig.APPLICATION_ID}.STOP") {
            ShizukuSettings.setLastSettledStateRunning(false)
            if (Shizuku.pingBinder()) {
                runCatching { Shizuku.exit() }
            }
        }
    }
}
