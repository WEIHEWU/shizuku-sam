package moe.shizuku.manager.settings

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.maxhz.getSystemOrAppPrimaryColor
import moe.shizuku.manager.maxhz.rememberEffectiveBlackNight
import moe.shizuku.manager.maxhz.rememberEffectiveIsDark

class SettingsActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val isDark = rememberEffectiveIsDark()
            val isBlack = rememberEffectiveBlackNight(isDark)

            SideEffect {
                window.statusBarColor = if (isBlack) Color.BLACK else Color.TRANSPARENT
                WindowCompat.getInsetsController(window, window.decorView)?.let { controller ->
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                }
            }

            GlassAppTheme(isDark = isDark) {
                val primaryColor = getSystemOrAppPrimaryColor(isDark)
                SettingsGlassScreen(
                    isDark = isDark,
                    isBlackNightTheme = isBlack,
                    primaryColor = primaryColor,
                    onBackClick = { finish() },
                    onRecreate = { recreate() }
                )
            }
        }
    }
}
