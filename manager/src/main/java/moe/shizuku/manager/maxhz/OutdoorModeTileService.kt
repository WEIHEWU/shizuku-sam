package moe.shizuku.manager.maxhz

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OutdoorModeTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val current = qsTile?.state == Tile.STATE_ACTIVE
        val next = !current
        updateTile(next)

        serviceScope.launch {
            val result = MaxHzController.toggleOutdoorMode()
            val confirmed = result.out.lineSequence()
                .firstOrNull { it.startsWith("MAXHZ_OUTDOOR_MODE_STATE=") }
                ?.substringAfter('=')
                ?.let { it == "1" } ?: next
            withContext(Dispatchers.Main) {
                updateTile(confirmed)
                val message = when {
                    result.code != 0 -> result.err.ifBlank { result.out }.ifBlank { "户外模式切换失败。" }
                    confirmed -> "户外模式已开启。"
                    else -> "户外模式已关闭。"
                }
                Toast.makeText(this@OutdoorModeTileService, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        serviceScope.launch {
            val enabled = MaxHzController.readOutdoorModeEnabled()
            withContext(Dispatchers.Main) {
                updateTile(enabled)
            }
        }
    }

    private fun updateTile(enabled: Boolean?) {
        qsTile?.apply {
            label = "户外模式"
            state = when (enabled) {
                true -> Tile.STATE_ACTIVE
                false -> Tile.STATE_INACTIVE
                null -> Tile.STATE_UNAVAILABLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = when (enabled) {
                    true -> "已开启"
                    false -> "已关闭"
                    null -> "需要 Shizuku"
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = subtitle
            }
            updateTile()
        }
    }
}
