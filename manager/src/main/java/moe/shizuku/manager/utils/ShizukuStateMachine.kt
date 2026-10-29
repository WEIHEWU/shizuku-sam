package moe.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.application
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private var state = AtomicReference<State>(loadPersistedSettledState())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val startingTimestamp = AtomicLong(0L)
    private const val STARTING_TIMEOUT_MS = 90_000L

    private fun loadPersistedSettledState(): State = try {
        when (ShizukuSettings.getLastSettledState()) {
            State.RUNNING.name -> State.RUNNING
            State.CRASHED.name -> State.CRASHED
            else -> State.STOPPED
        }
    } catch (_: Throwable) {
        State.STOPPED
    }

    init {
        Shizuku.addBinderReceivedListenerSticky {
            set(State.RUNNING)
        }
        Shizuku.addBinderDeadListener {
            setDead()
        }
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed ?: transform(oldState)
        if (oldState != newState) {
            Log.d("ShizukuStateMachine", "$oldState -> $newState")
            if (newState == State.STARTING) {
                startingTimestamp.set(System.currentTimeMillis())
                runCatching {
                    ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
                }
            }
            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                runCatching {
                    ShizukuSettings.setLastSettledState(newState.name)
                }
            }
            listeners.forEach { listener ->
                runCatching { listener(newState) }
            }
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() = transition { current ->
        when (current) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> {
                runCatching {
                    val permissionGranted = application.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                    if (shouldDisableUsbDebugging) {
                        Settings.Global.putInt(application.contentResolver, Settings.Global.ADB_ENABLED, 0)
                    }
                }.onFailure { e ->
                    Log.w("ShizukuStateMachine", "Failed to disable USB debugging", e)
                }
                State.STOPPED
            }
            else -> current
        }
    }

    fun update(): State {
        val isAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        val currentState = get()
        val nextState = when {
            isAlive -> State.RUNNING
            currentState == State.STARTING -> {
                val elapsed = System.currentTimeMillis() - startingTimestamp.get()
                if (elapsed > STARTING_TIMEOUT_MS) State.STOPPED else State.STARTING
            }
            currentState == State.STOPPING -> State.STOPPING
            currentState == State.CRASHED -> State.CRASHED
            currentState == State.RUNNING -> State.CRASHED
            else -> State.STOPPED
        }
        set(nextState)
        return nextState
    }

    fun isRunning(): Boolean = get() == State.RUNNING

    fun isDead(): Boolean {
        val s = get()
        return s == State.STOPPED || s == State.CRASHED
    }

    /**
     * 检测内存中的 shizuku_server 是否由旧版本 APK 启动（例如升级安装后旧服务仍驻留内存）。
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = runCatching { ShizukuSettings.getServerStartedBuild() }.getOrDefault(0)
        return startedBuild != BuildConfig.VERSION_CODE
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        runCatching { listener(state.get()) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }
}
