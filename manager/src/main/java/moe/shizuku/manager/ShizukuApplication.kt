package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.service.WatchdogAlarmReceiver
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.service.WatchdogWorker
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate

lateinit var application: ShizukuApplication

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            @Suppress("DEPRECATION")
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR).setTimeout(20))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    private fun init(context: Context?) {
        ShizukuSettings.initialize(context)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())
        ShizukuStateMachine.update()
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        init(this)
        if (ShizukuSettings.getWatchdog()) {
            WatchdogService.start(this)
            val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
            if (userManager == null || userManager.isUserUnlocked) {
                runCatching { WatchdogWorker.enqueue(this) }
                WatchdogAlarmReceiver.scheduleNext(this)
            }
        }
        runCatching { WatchdogService.syncWithCurrentState(this) }
    }

}
