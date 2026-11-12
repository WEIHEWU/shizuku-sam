package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    private val context: Context, private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null

    /**
     * mDNS 解析出的服务地址（对齐 ShizukuPlus）：
     * 回环地址归一化保持 `"127.0.0.1"`；当 `adbd` 在 mDNS 中广播本机非回环网卡地址时记录于此，
     * 供配对路径（`TLS_PAIRING`）连接真实接口地址，而常规启动（`TLS_CONNECT`）仅取端口并连接 `127.0.0.1`。
     */
    @Volatile
    var resolvedHost: String = "127.0.0.1"
        private set

    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restartJob: Job? = null
    private var restartScheduled = false
    private var attempts = 0

    fun start() {
        if (running) return
        if (!LocalNetworkGate.checkOrSkip(
                caller = "AdbMdns.start($serviceType)",
                port = -1,
                context = context,
                requiresLocalNetwork = true
            )
        ) {
            return
        }
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartJob?.cancel()
        mdnsScope.cancel()
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "listener not registered when stopping service discovery", e)
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
        resolveServiceCompat(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged("" to -1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val hostAddress = resolvedService.hostAddressCompat()
        val isLocal = hostAddress == "127.0.0.1" || hostAddress == "::1" || resolvedService.isLoopbackCompat()
        val normalizedCandidate = if (isLocal || hostAddress.isNullOrBlank()) {
            "127.0.0.1"
        } else {
            hostAddress.substringBefore('%')
        }

        val belongsToThisDevice = isLocal || (hostAddress != null && NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.any { networkInterface ->
                networkInterface.inetAddresses
                    .asSequence()
                    .any { addr ->
                        val ifAddr = addr.hostAddress ?: return@any false
                        ifAddr == hostAddress || ifAddr.substringBefore('%') == normalizedCandidate
                    }
            } == true)

        val portReady = if (serviceType == TLS_PAIRING) {
            true
        } else {
            isLoopbackPortAvailable(resolvedService.port)
        }

        if (running && belongsToThisDevice && portReady) {
            serviceName = resolvedService.serviceName
            resolvedHost = normalizedCandidate
            val callbackHost = if (serviceType == TLS_PAIRING) normalizedCandidate else "127.0.0.1"
            Log.i(TAG, "mDNS resolved $serviceType -> resolvedHost=$normalizedCandidate callback=$callbackHost:${resolvedService.port}")
            observer.onChanged(callbackHost to resolvedService.port)
        } else if (running && belongsToThisDevice && attempts < 5 && !restartScheduled) {
            attempts++
            restartScheduled = true
            val delayMs = attempts * 1000L
            restartJob = mdnsScope.launch {
                delay(delayMs)
                if (registered) {
                    try {
                        nsdManager.stopServiceDiscovery(listener)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "listener not registered when restarting service discovery", e)
                    }
                }
                delay(100L)
                if (!registered && running) {
                    if (LocalNetworkGate.checkOrSkip(
                            caller = "AdbMdns.restart($serviceType)",
                            port = -1,
                            context = context,
                            requiresLocalNetwork = true
                        )
                    ) {
                        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    } else {
                        running = false
                    }
                }
                restartScheduled = false
            }
        }
    }

    private fun isLoopbackPortAvailable(port: Int): Boolean {
        if (port !in 1..65535) return false
        val boundOnLoopback = try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }
        return boundOnLoopback && AdbPortProber.isPortOpen(
            port = port,
            timeoutMs = 150,
            caller = "AdbMdns.isLoopbackPortAvailable",
            context = context,
            host = "127.0.0.1"
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceCompat(info: NsdServiceInfo, listener: ResolveListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager.resolveService(info, context.mainExecutor, listener)
        } else {
            nsdManager.resolveService(info, listener)
        }
    }

    private fun NsdServiceInfo.hostAddressCompat(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (hostAddresses.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?: hostAddresses.firstOrNull { !it.isLoopbackAddress }
                ?: hostAddresses.firstOrNull())?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            host?.hostAddress
        }
    }

    private fun NsdServiceInfo.isLoopbackCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()?.isLoopbackAddress == true
        } else {
            @Suppress("DEPRECATION")
            host?.isLoopbackAddress == true
        }
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

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
