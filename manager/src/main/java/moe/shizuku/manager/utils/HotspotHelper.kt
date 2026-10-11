package moe.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.widget.Toast

/**
 * 个人热点向导工具类（参考 Shizuku+ 设计）：
 *
 * 在无外部 Wi-Fi（例如蜂窝 5G/4G 或离线）环境下，通过开启手机自带的个人热点，
 * 激活系统底层的虚拟无线网络接口，从而突破部分厂商对“必须连接 Wi-Fi 才能开启无线调试”的限制。
 */
object HotspotHelper {

    /**
     * 判断当前设备是否连接到了活跃的 Wi-Fi 局域网。
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 判断当前设备是否已开启了便携式热点（检测本地虚拟网卡接口是否处于 UP 状态）。
     */
    fun isHotspotActive(context: Context): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                if (intf.isUp && (intf.name.startsWith("ap") || intf.name.startsWith("wlan1") || intf.name.contains("swlan") || intf.name.contains("rndis"))) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 一键打开系统「便携式个人热点 / 网络共享」设置页面。
     * 原理：开启热点后，Android 会创建虚拟网卡接口，系统检测到有效无线接口后便允许开启无线调试，
     * 并且所有流量仅在本机 loopback 内部回环，无需连外部路由器，不消耗流量。
     */
    fun openHotspotSettings(context: Context) {
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_AP_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, "开启「个人热点」后，即可在开发者选项中无阻碍开启无线调试", Toast.LENGTH_LONG).show()
                return
            } catch (_: Throwable) {
            }
        }
        Toast.makeText(context, "请前往「系统设置 - 个人热点/网络共享」开启热点", Toast.LENGTH_LONG).show()
    }

    fun openTetheringSettings(context: Context) = openHotspotSettings(context)
}
