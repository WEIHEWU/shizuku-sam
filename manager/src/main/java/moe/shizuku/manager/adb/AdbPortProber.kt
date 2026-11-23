package moe.shizuku.manager.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.StartupCoordinator
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale

object AdbPortProber {

    private const val TAG = "AdbPortProber"

    private val PREFERRED_LAN_IF_PREFIXES = arrayOf(
        "wlan", "swlan", "wifi", "ap", "eth", "rndis"
    )

    private val EXCLUDED_WAN_TUN_IF_PREFIXES = arrayOf(
        "rmnet", "ccmni", "tun", "tap", "ppp", "v4-", "sit", "ip6tnl", "dummy", "lo"
    )

    fun invalidateCache() {
        // Live loopback probing no longer uses stale cache
    }

    /**
     * 获取当前本机活动的局域网 IPv4 地址（仅匹配显式 WLAN/以太网前缀并排除蜂窝与隧道网卡，避免无 Wi-Fi 时误返回 5G/4G 公网 IP）。
     */
    fun findOwnLanAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
                if (!up) continue
                val name = ni.name.orEmpty().lowercase(Locale.ROOT)
                if (EXCLUDED_WAN_TUN_IF_PREFIXES.any { name.startsWith(it) }) continue
                if (PREFERRED_LAN_IF_PREFIXES.none { name.startsWith(it) }) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) return ip
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 解析实际建连使用的目标主机（对齐 v13.8.41 与上游 127.0.0.1 主线）：
     * - 显式传入非空 host（如配对路径的 mDNS `resolvedHost` 或 `127.0.0.1`）时原样返回；
     * - 未传入或为空白时，统一返回回环地址 `"127.0.0.1"`。
     */
    fun resolveConnectHost(preferredHost: String? = null): String {
        val trimmed = preferredHost?.trim().orEmpty()
        return if (trimmed.isNotEmpty()) trimmed else "127.0.0.1"
    }

    /**
     * 仅探测指定目标主机（默认 `"127.0.0.1"`）上的开放端口，不再追加探测网卡 IP。
     * 回环地址 `"127.0.0.1"` / `"::1"` 永不拦截或跳过。
     */
    fun probeOpenEndpoint(
        port: Int,
        timeoutMs: Int = 150,
        preferredHost: String? = null,
        caller: String = "AdbPortProber.probeOpenEndpoint",
        context: Context? = null,
        isBackground: Boolean = false
    ): Pair<String, Int>? {
        if (port !in 1..65535) return null
        val targetHost = resolveConnectHost(preferredHost)
        if (!LocalNetworkGate.isLoopbackHost(targetHost) &&
            !LocalNetworkGate.checkOrSkip(
                caller = caller,
                port = port,
                context = context,
                isBackground = isBackground,
                host = targetHost
            )
        ) {
            return null
        }

        if (probeAddress(targetHost, port, timeoutMs)) {
            Log.d(TAG, "PROBE_HIT caller=$caller endpoint=$targetHost:$port")
            return targetHost to port
        }
        return null
    }

    private fun probeAddress(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 探测给定端口是否开放（默认探测回环 `127.0.0.1`）。
     */
    fun isPortOpen(
        port: Int,
        timeoutMs: Int = 150,
        caller: String = "AdbPortProber.isPortOpen",
        context: Context? = null,
        isBackground: Boolean = false,
        host: String? = null
    ): Boolean {
        return probeOpenEndpoint(
            port = port,
            timeoutMs = timeoutMs,
            preferredHost = host,
            caller = caller,
            context = context,
            isBackground = isBackground
        ) != null
    }

    /**
     * 直接探测回环（`127.0.0.1`）上可用的无线调试 / TCP 端口（无过期缓存，100% 对齐 v13.8.41）：
     * - 端口优先级：`5555`（免 Wi-Fi 快速通道）-> 系统当前 `adb.tcp.port` -> 上次成功端口 `lastPort` -> 自定义 `tcpPort` -> `service.adb.tls.port`
     */
    suspend fun findActiveEndpoint(
        context: Context? = null,
        timeoutMs: Int = 150,
        preferredHost: String? = null,
        caller: String = "AdbPortProber.findActiveEndpoint",
        isBackground: Boolean = false,
        @Suppress("UNUSED_PARAMETER") forceRefresh: Boolean = false
    ): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val primaryHost = resolveConnectHost(preferredHost)
        if (!LocalNetworkGate.isLoopbackHost(primaryHost) &&
            !LocalNetworkGate.checkOrSkip(
                caller = caller,
                port = -1,
                context = context,
                isBackground = isBackground,
                host = primaryHost
            )
        ) {
            StartupCoordinator.recordPortProbe(
                candidates = emptyList(),
                hitPort = -1,
                caller = caller,
                skippedReason = "本地网络权限未授予",
                candidateHosts = listOf(primaryHost)
            )
            return@withContext null
        }

        doProbeCandidates(context, timeoutMs, primaryHost, caller, isBackground)
    }

    /**
     * 兼容旧签名的回环候选端口探测入口。
     */
    suspend fun findActiveLoopbackPort(
        context: Context? = null,
        timeoutMs: Int = 150,
        caller: String = "AdbPortProber.findActiveLoopbackPort",
        isBackground: Boolean = false,
        forceRefresh: Boolean = false
    ): Int = findActiveEndpoint(
        context = context,
        timeoutMs = timeoutMs,
        caller = caller,
        isBackground = isBackground,
        forceRefresh = forceRefresh
    )?.second ?: -1

    private fun doProbeCandidates(
        context: Context?,
        timeoutMs: Int,
        targetHost: String,
        caller: String,
        isBackground: Boolean
    ): Pair<String, Int>? {
        val candidatePorts = LinkedHashSet<Int>()

        // 1. Standard ADB TCP port (5555 - works offline / over 5G without Wi-Fi)
        candidatePorts.add(5555)

        // 2. System active TCP port
        val sysTcpPort = EnvironmentUtils.getAdbTcpPort()
        if (sysTcpPort in 1..65535) {
            candidatePorts.add(sysTcpPort)
        }

        // 3. Last known port
        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535) {
            candidatePorts.add(lastPort)
        }

        // 4. User configured TCP port
        val tcpPort = ShizukuSettings.getTcpPort()
        if (tcpPort in 1..65535) {
            candidatePorts.add(tcpPort)
        }

        // 5. System TLS port
        val tlsPort = EnvironmentUtils.getAdbTlsPort()
        if (tlsPort in 1..65535) {
            candidatePorts.add(tlsPort)
        }

        var resolved: Pair<String, Int>? = null
        for (port in candidatePorts) {
            val hit = probeOpenEndpoint(
                port = port,
                timeoutMs = timeoutMs,
                preferredHost = targetHost,
                caller = "$caller[candidate]",
                context = context,
                isBackground = isBackground
            )
            if (hit != null) {
                resolved = hit
                break
            }
        }

        StartupCoordinator.recordPortProbe(
            candidates = candidatePorts,
            hitPort = resolved?.second ?: -1,
            caller = caller,
            hitHost = resolved?.first,
            candidateHosts = listOf(targetHost)
        )
        return resolved
    }
}
