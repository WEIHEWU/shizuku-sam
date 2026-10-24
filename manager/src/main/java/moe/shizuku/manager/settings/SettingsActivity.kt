package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.maxhz.getSystemOrAppPrimaryColor
import rikka.core.res.isNight

class SettingsActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isDark = resources.configuration.isNight()
        val isBlack = isDark && ThemeHelper.isBlackNightTheme(this)

        setContent {
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
