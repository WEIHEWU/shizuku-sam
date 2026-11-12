package moe.shizuku.manager.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val POSITIVE_CACHE_TTL_MS = 2_500L
    private const val NEGATIVE_CACHE_TTL_MS = 1_500L

    private val PREFERRED_LAN_IF_PREFIXES = arrayOf(
        "wlan", "swlan", "wifi", "ap", "eth", "rndis", "bond", "br"
    )

    private val EXCLUDED_WAN_TUN_IF_PREFIXES = arrayOf(
        "rmnet", "ccmni", "tun", "tap", "ppp", "v4-", "sit", "ip6tnl", "dummy", "lo"
    )

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    private var inFlightProbe: Deferred<Pair<String, Int>?>? = null

    @Volatile
    private var lastCachedEndpoint: Pair<String, Int>? = null

    @Volatile
    private var lastCachedAtMs: Long = 0L

    fun invalidateCache() {
        lastCachedAtMs = 0L
    }

    /**
     * 获取当前本机活动的局域网 IPv4 地址（排除蜂窝与 VPN 隧道网卡，仅供连接诊断面板展示当前网络状态，不参与常规连接选址）。
     */
    fun findOwnLanAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            var fallbackIpv4: String? = null
            for (ni in interfaces) {
                val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
                if (!up) continue
                val name = ni.name.orEmpty().lowercase(Locale.ROOT)
                if (EXCLUDED_WAN_TUN_IF_PREFIXES.any { name.startsWith(it) }) continue
                val isPreferredIf = PREFERRED_LAN_IF_PREFIXES.any { name.startsWith(it) }
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isPreferredIf) return ip
                        if (fallbackIpv4 == null) fallbackIpv4 = ip
                    }
                }
            }
            fallbackIpv4
        }.getOrNull()
    }

    /**
     * 解析实际建连使用的目标主机（对齐上游 127.0.0.1 主线）：
     * - 显式传入非空 host（如配对路径的 mDNS `resolvedHost` 或 `127.0.0.1`）时原样返回；
     * - 未传入或为空白时，统一返回回环地址 `"127.0.0.1"`。
     */
    fun resolveConnectHost(preferredHost: String? = null): String {
        val trimmed = preferredHost?.trim().orEmpty()
        return if (trimmed.isNotEmpty()) trimmed else "127.0.0.1"
    }

    /**
     * 探测指定端口在目标主机（默认 `127.0.0.1`）上是否可连接，并返回命中的 `host to port`。
     * 绝不在未通过 LocalNetworkGate 时创建 Socket。
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
        if (!LocalNetworkGate.checkOrSkip(
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
        } catch (_: Exception) {
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
     * 单飞去重地探测回环（`127.0.0.1`）上可用的无线调试 / TCP 端口：
     * - 端口优先级：系统当前 `service.adb.tcp.port` -> 上次成功端口 -> `5555`（免 Wi-Fi 快速通道）-> 自定义 `tcpPort`
     */
    suspend fun findActiveEndpoint(
        context: Context? = null,
        timeoutMs: Int = 150,
        preferredHost: String? = null,
        caller: String = "AdbPortProber.findActiveEndpoint",
        isBackground: Boolean = false,
        forceRefresh: Boolean = false
    ): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val primaryHost = resolveConnectHost(preferredHost)
        if (!LocalNetworkGate.checkOrSkip(
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

        val now = System.currentTimeMillis()
        if (!forceRefresh && lastCachedAtMs > 0L) {
            val cached = lastCachedEndpoint
            val ttl = if (cached != null) POSITIVE_CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
            if (now - lastCachedAtMs < ttl) {
                return@withContext cached
            }
        }

        val deferred = inFlightMutex.withLock {
            val existing = inFlightProbe
            if (existing != null && existing.isActive) {
                existing
            } else {
                probeScope.async {
                    doProbeCandidates(context, timeoutMs, primaryHost, caller, isBackground)
                }.also { inFlightProbe = it }
            }
        }

        try {
            deferred.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlightProbe === deferred && deferred.isCompleted) {
                    inFlightProbe = null
                }
            }
        }
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

        val sysTcpPort = EnvironmentUtils.getAdbTcpPort()
        if (sysTcpPort in 1..65535) {
            candidatePorts.add(sysTcpPort)
        }

        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535) {
            candidatePorts.add(lastPort)
        }

        // Standard ADB TCP port (5555 - works offline / over 5G without Wi-Fi)
        candidatePorts.add(5555)

        val tcpPort = ShizukuSettings.getTcpPort()
        if (tcpPort in 1..65535) {
            candidatePorts.add(tcpPort)
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

        lastCachedEndpoint = resolved
        lastCachedAtMs = System.currentTimeMillis()
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
