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

        if (running && (isLocal || (hostAddress != null && NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { hostAddress == it.hostAddress }
                }))
            && isPortAvailable(resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            val normalizedHost = if (isLocal || hostAddress == null) "127.0.0.1" else hostAddress
            resolvedHost = normalizedHost
            observer.onChanged(normalizedHost to resolvedService.port)
        } else if (running && attempts < 5 && !restartScheduled) {
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
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }
                restartScheduled = false
            }
        }
    }

    private fun isPortAvailable(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (_: IOException) {
        true
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
            hostAddresses.firstOrNull()?.hostAddress
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
