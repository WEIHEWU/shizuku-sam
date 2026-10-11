package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") {
            return false
        }

        val binder = extractReceiverBinder(intent)
        if (binder == null) {
            LOGGER.w("Shell binder request ignored: receiver binder missing")
            return false
        }
        val shizukuBinder = Shizuku.getBinder()
        if (shizukuBinder == null) {
            LOGGER.w("Binder not received or Shizuku service not running")
        }

        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            data.recycle()
        }
    }

    private fun extractReceiverBinder(intent: Intent): IBinder? {
        return try {
            intent.setExtrasClassLoader(Bundle::class.java.classLoader)
            intent.getBundleExtra("data")?.getBinder("binder")
                ?: intent.extras?.getBinder("binder")
        } catch (e: Throwable) {
            LOGGER.w(e, "Failed to read shell request binder")
            null
        }
    }
}
