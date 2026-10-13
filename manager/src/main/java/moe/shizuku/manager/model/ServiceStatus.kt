package moe.shizuku.manager.model

import rikka.shizuku.Shizuku

data class ServiceStatus(
        val uid: Int = -1,
        val apiVersion: Int = -1,
        val patchVersion: Int = -1,
        val seContext: String? = null,
        val permission: Boolean = false,
        val running: Boolean = false
) {
    val isRunning: Boolean
        get() = (running || uid != -1) && Shizuku.pingBinder()
}