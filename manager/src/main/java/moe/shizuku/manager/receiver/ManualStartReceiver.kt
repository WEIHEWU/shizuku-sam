package moe.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig

class ManualStartReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        if (intent.action == "${BuildConfig.APPLICATION_ID}.START") {
            ShizukuReceiverStarter.start(context, forceStart = true)
        }
    }
}
