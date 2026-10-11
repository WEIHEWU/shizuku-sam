package moe.shizuku.manager.maxhz

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R

/**
 * 游戏模式快捷磁贴（单磁贴三态轮循切换）：
 *
 * 点击依次循环切换：
 * [系统默认] ➔ [省电自适应高刷] ➔ [游戏性能模式] ➔ [系统默认]
 *
 * 极致性能优化策略：
 * 1. 0ms 瞬间乐观刷新：用户手指点击磁贴瞬间，立即在主线程更新图标、高亮状态和副标题，无任何等待；
 * 2. 单进程原子 Shell 极速执行：将原本 6 条串行 Shell 整合为单次 ~30ms 原子指令，消除 Binder 与进程开销；
 * 3. 防快速连击排队：多次快速点击自动取消前置未完成协程，只提交最终状态。
 */
class GameModeTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSwitchJob: Job? = null
    @Volatile private var optimisticMode: Int = -1

    companion object {
        const val MODE_DEFAULT = 0
        const val MODE_SAVER = 1
        const val MODE_PERFORMANCE = 2
    }

    override fun onStartListening() {
        super.onStartListening()
        optimisticMode = -1
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val current = if (optimisticMode != -1) optimisticMode else detectCurrentMode()
        val nextMode = when (current) {
            MODE_DEFAULT -> MODE_SAVER
            MODE_SAVER -> MODE_PERFORMANCE
            else -> MODE_DEFAULT
        }
        optimisticMode = nextMode

        // 1. 0ms 瞬间乐观刷新：立刻改变磁贴颜色、图标与副标题，提供零延迟触感反馈！
        updateTile(nextMode)

        // 2. 取消之前可能未完成的任务（防快速连击排队）
        pendingSwitchJob?.cancel()
        pendingSwitchJob = serviceScope.launch {
            applyMode(nextMode)
            optimisticMode = -1
        }
    }

    override fun onDestroy() {
        pendingSwitchJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun detectCurrentMode(): Int {
        val tileMode = MaxHzPrefs.gameTileMode(this)
        if (tileMode in MODE_DEFAULT..MODE_PERFORMANCE) {
            return tileMode
        }
        return when {
            MaxHzPrefs.superPerformanceEnabled(this) -> MODE_PERFORMANCE
            MaxHzPrefs.compatibleSaverEnabled(this) -> MODE_SAVER
            else -> MODE_DEFAULT
        }
    }

    private fun refreshTile() {
        serviceScope.launch {
            val currentMode = detectCurrentMode()
            withContext(Dispatchers.Main) {
                // 如果当前没有正在等待提交的乐观状态，才刷新
                if (optimisticMode == -1) {
                    updateTile(currentMode)
                }
            }
        }
    }

    private suspend fun applyMode(targetMode: Int) {
        val (message, finalMode) = withContext(Dispatchers.IO) {
            when (targetMode) {
                MODE_SAVER -> {
                    // 原子极速应用：退性能冲突 + 真正开启系统省电 + 保持 120Hz 自适应 + Quick Doze
                    val r = MaxHzController.applyDailySaver(
                        MaxHzPrefs.quickDozeMaintenanceMinutes(this@GameModeTileService),
                        MaxHzPrefs.quickDozeNoMaintenance(this@GameModeTileService)
                    )
                    if (r.code == 0) {
                        MaxHzPrefs.setGameTileMode(this@GameModeTileService, MODE_SAVER)
                        MaxHzPrefs.setCompatibleSaverEnabled(this@GameModeTileService, true)
                        MaxHzPrefs.markSuperPerformanceState(this@GameModeTileService, false)
                        MaxHzPrefs.setQuickDozeEnabled(this@GameModeTileService, true)
                        "游戏模式: 省电自适应高刷 (省电+120Hz自适应)" to MODE_SAVER
                    } else {
                        val err = r.err.ifBlank { r.out }.ifBlank { "执行失败 (${r.code})" }
                        "切换省电高刷失败: $err" to detectCurrentMode()
                    }
                }
                MODE_PERFORMANCE -> {
                    // 原子极速应用：清退系统省电 + 注入 CPU/GPU 满血加速 + 120Hz
                    val relaxed = MaxHzPrefs.gameAutoThermalRelaxed(this@GameModeTileService)
                    val r = MaxHzController.quickSwitchGamePerformance(relaxed)
                    if (r.code == 0) {
                        MaxHzPrefs.setGameTileMode(this@GameModeTileService, MODE_PERFORMANCE)
                        MaxHzPrefs.setCompatibleSaverEnabled(this@GameModeTileService, false)
                        MaxHzPrefs.markSuperPerformanceState(this@GameModeTileService, true, relaxed)
                        MaxHzPrefs.setQuickDozeEnabled(this@GameModeTileService, false)
                        "游戏模式: 游戏性能模式 (CPU/GPU满血+120Hz)" to MODE_PERFORMANCE
                    } else {
                        val err = r.err.ifBlank { r.out }.ifBlank { "执行失败 (${r.code})" }
                        "切换性能模式失败: $err" to detectCurrentMode()
                    }
                }
                else -> {
                    // 原子极速应用：一次性清理性能与省电，恢复原生调度与刷新率
                    val r = MaxHzController.quickSwitchGameDefault()
                    if (r.code == 0) {
                        MaxHzPrefs.setGameTileMode(this@GameModeTileService, MODE_DEFAULT)
                        MaxHzPrefs.setCompatibleSaverEnabled(this@GameModeTileService, false)
                        MaxHzPrefs.markSuperPerformanceState(this@GameModeTileService, false)
                        MaxHzPrefs.setQuickDozeEnabled(this@GameModeTileService, false)
                        "游戏模式: 系统默认 (已恢复原生调度)" to MODE_DEFAULT
                    } else {
                        val err = r.err.ifBlank { r.out }.ifBlank { "执行失败 (${r.code})" }
                        "恢复默认失败: $err" to detectCurrentMode()
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            updateTile(finalMode)
            Toast.makeText(this@GameModeTileService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTile(mode: Int) {
        val tile = qsTile ?: return
        tile.label = "游戏模式"
        when (mode) {
            MODE_DEFAULT -> {
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_maxhz_restore_24)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "系统默认"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    tile.stateDescription = "系统默认 (未激活)"
                }
            }
            MODE_SAVER -> {
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_maxhz_battery_24)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "省电自适应高刷"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    tile.stateDescription = "省电自适应高刷 (已激活)"
                }
            }
            MODE_PERFORMANCE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_maxhz_speed_24)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "游戏性能模式"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    tile.stateDescription = "游戏性能模式 (已激活)"
                }
            }
        }
        tile.updateTile()
    }
}
