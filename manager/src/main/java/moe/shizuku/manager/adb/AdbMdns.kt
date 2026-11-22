package moe.shizuku.manager.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.ArrayDeque
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    private val context: Context,
    private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {

    private var isBackground: Boolean = false

    constructor(
        context: Context,
        serviceType: String,
        observer: Observer<Pair<String, Int>>,
        isBackground: Boolean
    ) : this(context, serviceType, observer) {
        this.isBackground = isBackground
    }

    @Volatile
    private var registered = false

    @Volatile
    private var running = false

    @Volatile
    private var serviceName: String? = null

    /**
     * mDNS 解析出的服务地址（对齐 ShizukuPlus / Shizuku-Next）：
     * 回环地址归一化为 `"127.0.0.1"`；当 `adbd` 广播本机非回环网卡地址时记录真实接口 IP 作为回退目标，
     * 配对与连接路径始终优先尝试 `"127.0.0.1"`，仅当回环连接失败时才回退至该网卡地址。
     */
    @Volatile
    var resolvedHost: String = "127.0.0.1"
        private set

    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val resolveLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var currentResolveToken = 0L
    private var resolveTimeoutJob: Job? = null

    private var restartJob: Job? = null

    @Volatile
    private var restartScheduled = false
    private var attempts = 0

    fun start() {
        if (running) return
        if (!LocalNetworkGate.checkOrSkip(
                caller = "AdbMdns.start($serviceType)",
                port = -1,
                context = context,
                isBackground = isBackground,
                requiresLocalNetwork = true
            )
        ) {
            return
        }
        running = true
        attempts = 0
        restartScheduled = false
        if (!registered) {
            runCatching {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { e ->
                Log.w(TAG, "discoverServices failed on start", e)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartScheduled = false
        restartJob?.cancel()
        restartJob = null
        synchronized(resolveLock) {
            resolveQueue.clear()
            resolving = false
            currentResolveToken++
            resolveTimeoutJob?.cancel()
            resolveTimeoutJob = null
        }
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "listener not registered when stopping service discovery", e)
            } catch (e: Throwable) {
                Log.w(TAG, "failed to stop service discovery", e)
            }
            registered = false
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        if (!running) return
        synchronized(resolveLock) {
            if (resolveQueue.none { it.serviceName == info.serviceName }) {
                resolveQueue.addLast(info)
            }
        }
        resolveNext()
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) {
            mdnsScope.launch(Dispatchers.Main.immediate) {
                if (running && info.serviceName == serviceName) {
                    observer.onChanged("" to -1)
                }
            }
        }
    }

    private fun resolveNext() {
        val next: NsdServiceInfo
        val token: Long
        synchronized(resolveLock) {
            if (!running || resolving || resolveQueue.isEmpty()) return
            next = resolveQueue.removeFirst()
            resolving = true
            token = ++currentResolveToken
            resolveTimeoutJob?.cancel()
            resolveTimeoutJob = mdnsScope.launch {
                delay(RESOLVE_TIMEOUT_MS)
                Log.w(TAG, "resolveService timed out for ${next.serviceName}, advancing queue")
                onResolveFinished(token)
            }
        }
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(next, ResolveListener(this, token))
        } catch (e: Throwable) {
            Log.w(TAG, "resolveService threw for ${next.serviceName}", e)
            onResolveFinished(token)
        }
    }

    private fun onResolveFinished(token: Long) {
        val shouldContinue: Boolean
        synchronized(resolveLock) {
            if (token != currentResolveToken) return
            resolving = false
            resolveTimeoutJob?.cancel()
            resolveTimeoutJob = null
            shouldContinue = running && resolveQueue.isNotEmpty()
        }
        if (shouldContinue) {
            resolveNext()
        }
    }

    private fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int, token: Long) {
        Log.w(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, errorCode=$errorCode")
        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && running) {
            val isCurrent: Boolean
            synchronized(resolveLock) {
                isCurrent = token == currentResolveToken
                if (isCurrent) {
                    if (resolveQueue.none { it.serviceName == nsdServiceInfo.serviceName }) {
                        resolveQueue.addFirst(nsdServiceInfo)
                    }
                    resolving = false
                    resolveTimeoutJob?.cancel()
                    resolveTimeoutJob = null
                }
            }
            if (isCurrent) {
                mdnsScope.launch {
                    delay(ALREADY_ACTIVE_RETRY_DELAY_MS)
                    resolveNext()
                }
            }
        } else {
            onResolveFinished(token)
        }
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo, token: Long) {
        onResolveFinished(token)
        if (!running) return

        val hosts = resolvedService.allHostAddressesCompat()
        val resolvedPort = resolvedService.port
        val resolvedName = resolvedService.serviceName

        mdnsScope.launch {
            if (!running) return@launch

            val belongsToThisDevice = isDeviceAddress(hosts)
            val portReady = isPortAvailable(resolvedPort, hosts)
            val normalizedCandidate = resolvePreferredHost(hosts)

            if (running && belongsToThisDevice && portReady) {
                serviceName = resolvedName
                resolvedHost = normalizedCandidate
                Log.i(
                    TAG,
                    "mDNS resolved $serviceType -> resolvedHost=$normalizedCandidate port=$resolvedPort"
                )
                withContext(Dispatchers.Main.immediate) {
                    if (running) {
                        observer.onChanged(normalizedCandidate to resolvedPort)
                    }
                }
            } else if (running && belongsToThisDevice && !portReady && attempts < 5 && !restartScheduled) {
                Log.d(
                    TAG,
                    "mDNS port $resolvedPort ($serviceType) not ready yet, scheduling discovery restart (attempt=${attempts + 1})"
                )
                attempts++
                restartScheduled = true
                val delayMs = attempts * 1000L
                restartJob = mdnsScope.launch {
                    try {
                        delay(delayMs)
                        if (!running) return@launch
                        if (registered) {
                            try {
                                nsdManager.stopServiceDiscovery(listener)
                            } catch (e: IllegalArgumentException) {
                                Log.w(TAG, "listener not registered when restarting service discovery", e)
                            } catch (e: Throwable) {
                                Log.w(TAG, "stopServiceDiscovery failed on restart", e)
                            }
                        }
                        var waited = 0L
                        while (registered && waited < 500L && running) {
                            delay(50L)
                            waited += 50L
                        }
                        if (!registered && running) {
                            if (LocalNetworkGate.checkOrSkip(
                                    caller = "AdbMdns.restart($serviceType)",
                                    port = -1,
                                    context = context,
                                    isBackground = isBackground,
                                    requiresLocalNetwork = true
                                )
                            ) {
                                runCatching {
                                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                                }.onFailure { e ->
                                    Log.w(TAG, "discoverServices failed on restart", e)
                                }
                            } else {
                                running = false
                            }
                        }
                    } finally {
                        restartScheduled = false
                    }
                }
            } else {
                Log.d(
                    TAG,
                    "Ignoring mDNS service $resolvedName ($serviceType:$resolvedPort): belongs=$belongsToThisDevice portReady=$portReady hosts=${hosts.mapNotNull { it.hostAddress }}"
                )
            }
        }
    }

    private fun isDeviceAddress(hosts: List<InetAddress>): Boolean {
        if (hosts.isEmpty()) return serviceType == TLS_PAIRING
        if (hosts.any { isLoopbackAddressCompat(it) }) return true

        val normalizedTargets = hosts.mapNotNull {
            it.hostAddress?.substringBefore('%')?.lowercase(Locale.ROOT)
        }.toSet()

        // Tier 1: ConnectivityManager LinkProperties (不受后台 NetworkInterface netlink 限制)
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val networks = buildList {
                    cm.activeNetwork?.let { add(it) }
                    @Suppress("DEPRECATION")
                    runCatching { cm.allNetworks }.getOrNull()?.let { addAll(it) }
                }.distinct()
                for (network in networks) {
                    val linkAddresses = runCatching { cm.getLinkProperties(network)?.linkAddresses }
                        .getOrNull()
                        .orEmpty()
                    for (la in linkAddresses) {
                        val localIp = la.address?.hostAddress?.substringBefore('%')?.lowercase(Locale.ROOT)
                        if (localIp != null && localIp in normalizedTargets) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ConnectivityManager address check failed", e)
        }

        // Tier 2: NetworkInterface 回退枚举
        try {
            val matched = NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.any { ni ->
                    ni.inetAddresses.asSequence().any { addr ->
                        val ifAddr = addr.hostAddress?.substringBefore('%')?.lowercase(Locale.ROOT)
                        ifAddr != null && ifAddr in normalizedTargets
                    }
                } == true
            if (matched) return true
        } catch (e: Throwable) {
            Log.w(TAG, "NetworkInterface address check failed", e)
        }

        // Tier 3: TLS_PAIRING 仅在用户主动开启配对码弹窗时广播，One UI 后台接口枚举受限时放行内网/链路本地地址
        if (serviceType == TLS_PAIRING && hosts.any { it.isSiteLocalAddress || it.isLinkLocalAddress }) {
            Log.i(TAG, "Allowing site/link-local pairing address via Tier 3 fallback: $normalizedTargets")
            return true
        }

        return false
    }

    private fun isPortAvailable(port: Int, hosts: List<InetAddress>): Boolean {
        if (port !in 1..65535) return false

        // 1. 回环 IPv4
        if (isAddressBoundInUse(InetAddress.getByName("127.0.0.1"), port, allowAnyIoAsInUse = true)) {
            return true
        }
        // 2. 回环 IPv6
        val v6Loopback = runCatching { InetAddress.getByName("::1") }.getOrNull()
        if (v6Loopback != null && isAddressBoundInUse(v6Loopback, port, allowAnyIoAsInUse = true)) {
            return true
        }
        // 3. 非回环接口地址（兼容 Android 15/16/17 仅在 wlan0 绑定 adbd 端口的 ROM）
        for (addr in hosts) {
            if (!isLoopbackAddressCompat(addr) && isAddressBoundInUse(addr, port, allowAnyIoAsInUse = false)) {
                return true
            }
        }
        return false
    }

    private fun isAddressBoundInUse(
        address: InetAddress,
        port: Int,
        allowAnyIoAsInUse: Boolean
    ): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(address, port), 1)
                false
            }
        } catch (e: IOException) {
            if (allowAnyIoAsInUse) {
                true
            } else {
                isAddrInUseError(e)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isAddrInUseError(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is ErrnoException && cur.errno == OsConstants.EADDRINUSE) {
                return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("EADDRINUSE") || msg.contains("Address already in use", ignoreCase = true)) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun NsdServiceInfo.allHostAddressesCompat(): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.filterNotNullTo(result)
        }
        @Suppress("DEPRECATION")
        val legacyHost = host
        if (legacyHost != null && result.none { it.hostAddress == legacyHost.hostAddress }) {
            result.add(legacyHost)
        }
        return result
    }

    private fun resolvePreferredHost(hosts: List<InetAddress>): String {
        if (hosts.any { isLoopbackAddressCompat(it) }) {
            return "127.0.0.1"
        }
        val preferred = hosts.firstOrNull { !isLoopbackAddressCompat(it) && it is Inet4Address }
            ?: hosts.firstOrNull { !isLoopbackAddressCompat(it) }
            ?: hosts.firstOrNull()
        return preferred?.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    }

    private fun isLoopbackAddressCompat(addr: InetAddress): Boolean {
        val ip = addr.hostAddress?.substringBefore('%')
        return addr.isLoopbackAddress || ip == "127.0.0.1" || ip == "::1"
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val token: Long
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            adbMdns.onResolveFailed(nsdServiceInfo, errorCode, token)
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo, token)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
        private const val RESOLVE_TIMEOUT_MS = 3_000L
        private const val ALREADY_ACTIVE_RETRY_DELAY_MS = 150L
    }
}
