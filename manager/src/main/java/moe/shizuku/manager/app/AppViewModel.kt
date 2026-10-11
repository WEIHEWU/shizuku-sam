package moe.shizuku.manager.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.maxhz.MaxHzPrefs
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku

/**
 * 统一应用全局状态 ViewModel。
 *
 * 集中管理 Shizuku 服务连接状态、服务端元数据（UID/SELinux/版本）以及 MaxHz 全局运行状态。
 * 取代分散在各个 Activity 中的独立 Binder 监听器与状态探测逻辑。
 */
class AppViewModel : ViewModel() {

    private val _serviceStatus = MutableLiveData<Resource<ServiceStatus>>()
    val serviceStatus: LiveData<Resource<ServiceStatus>> = _serviceStatus

    private val _isBinderAlive = MutableLiveData<Boolean>()
    val isBinderAlive: LiveData<Boolean> = _isBinderAlive

    private val _maxHzControlEnabled = MutableLiveData<Boolean>()
    val maxHzControlEnabled: LiveData<Boolean> = _maxHzControlEnabled

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _isBinderAlive.postValue(true)
        reload()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _isBinderAlive.postValue(false)
        _serviceStatus.postValue(Resource.success(ServiceStatus()))
    }

    init {
        _isBinderAlive.value = Shizuku.pingBinder()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        reload()
    }

    fun syncMaxHzState(context: Context) {
        _maxHzControlEnabled.value = MaxHzPrefs.controlEnabled(context)
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = loadStatus()
                _serviceStatus.postValue(Resource.success(status))
                _isBinderAlive.postValue(status.isRunning)
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                LOGGER.w(e, "Failed to load service status")
                _serviceStatus.postValue(Resource.error(e, ServiceStatus()))
                _isBinderAlive.postValue(false)
            }
        }
    }

    private fun loadStatus(): ServiceStatus {
        if (!Shizuku.pingBinder()) {
            return ServiceStatus()
        }

        val uid = Shizuku.getUid()
        val apiVersion = Shizuku.getVersion()
        val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null
        val permissionTest =
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED

        ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, BuildConfig.APPLICATION_ID, 0)
        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest)
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}
