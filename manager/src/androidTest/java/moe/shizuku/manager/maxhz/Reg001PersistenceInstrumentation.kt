package moe.shizuku.manager.maxhz

import android.app.Instrumentation
import android.os.Bundle

class Reg001PersistenceInstrumentation : Instrumentation() {

    override fun onCreate(arguments: Bundle) {
        start()
    }

    override fun onStart() {
        val context = targetContext
        val packages = listOf(
            "com.example.reg001.alpha",
            "com.example.reg001.beta",
            "com.example.reg001.gamma"
        )
        try {
            MaxHzPrefs.clearAppRefreshRules(context)
            packages.reversed().forEachIndexed { index, packageName ->
                MaxHzPrefs.saveAppRefreshRule(
                    context,
                    MaxHzPrefs.AppRefreshRule(packageName, adaptive = index != 1, maxHz = 120, minHz = 10)
                )
            }

            val firstRead = MaxHzPrefs.appRefreshRules(context)
            check(firstRead.map { it.packageName } == packages) { "save/read mismatch: $firstRead" }

            MaxHzPrefs.saveAppRefreshRule(
                context,
                MaxHzPrefs.AppRefreshRule("   ", adaptive = true, maxHz = 120, minHz = 10)
            )
            check(MaxHzPrefs.appRefreshRules(context) == firstRead) { "blank input changed persisted rules" }

            val recreatedContext = context.createPackageContext(context.packageName, 0)
            check(MaxHzPrefs.appRefreshRules(recreatedContext) == firstRead) { "recreated context lost rules" }

            MaxHzPrefs.clearAppRefreshRules(context)
            check(MaxHzPrefs.appRefreshRules(context).isEmpty()) { "explicit clear did not remove rules" }
            finish(0, Bundle().apply { putString("result", "PASS") })
        } catch (t: Throwable) {
            finish(1, Bundle().apply { putString("result", "FAIL: ${t.message}") })
        }
    }
}
