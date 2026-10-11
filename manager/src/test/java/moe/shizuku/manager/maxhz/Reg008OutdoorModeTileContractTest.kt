package moe.shizuku.manager.maxhz

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Reg008OutdoorModeTileContractTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val service = File("src/main/java/moe/shizuku/manager/maxhz/OutdoorModeTileService.kt").readText()
    private val activity = File("src/main/java/moe/shizuku/manager/maxhz/MaxHzActivity.kt").readText()

    @Test
    fun manifestDeclaresAToggleableQuickSettingsTile() {
        assertTrue(manifest.contains(".maxhz.OutdoorModeTileService"))
        assertTrue(manifest.contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
        assertTrue(manifest.contains("android.service.quicksettings.action.QS_TILE"))
        assertTrue(manifest.contains("android.service.quicksettings.TOGGLEABLE_TILE"))
    }

    @Test
    fun tileReadsTheRealStateAndTogglesThroughTheSharedController() {
        assertTrue(service.contains("MaxHzController.readOutdoorModeEnabled()"))
        assertTrue(service.contains("MaxHzController.toggleOutdoorMode()"))
        assertTrue(service.contains("MAXHZ_OUTDOOR_MODE_STATE="))
        assertTrue(service.contains("Tile.STATE_ACTIVE"))
        assertTrue(service.contains("Tile.STATE_INACTIVE"))
        assertTrue(service.contains("Tile.STATE_UNAVAILABLE"))
    }

    @Test
    fun refreshPageOffersTheSystemAddTilePrompt() {
        // 「状态栏快捷开关」卡已从独立 View 卡（outdoorModeQuickSettingsCard）改为
        // Compose addActionCard，点击走 requestOutdoorModeTile() →
        // StatusBarManager.requestAddTileService。
        assertTrue(activity.contains("requestOutdoorModeTile()"))
        assertTrue(activity.contains("requestAddTileService("))
        assertTrue(activity.contains("OutdoorModeTileService::class.java"))
    }
}
