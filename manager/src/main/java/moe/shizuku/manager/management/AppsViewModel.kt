package moe.shizuku.manager.management

import android.content.Context
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.lifecycle.Resource
import rikka.lifecycle.activityViewModels
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@MainThread
fun ComponentActivity.appsViewModel() = viewModels { AppsViewModel(this) }

@MainThread
fun Fragment.appsViewModel() = activityViewModels { AppsViewModel(requireContext()) }

class AppsViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val loadGeneration = AtomicInteger(0)

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>()
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    private val cacheListener: (List<PackageInfo>, Int) -> Unit = { list, count ->
        _packages.postValue(Resource.success(list))
        _grantedCount.postValue(Resource.success(count))
    }

    init {
        ensureSnapshotHydrated(prefs)
        val snapshot = cachedPackages
        if (snapshot != null) {
            _packages.value = Resource.success(snapshot)
        }
        val initialCount = cachedGrantedCount ?: prefs.getInt(KEY_LAST_GRANTED_COUNT, 0)
        if (initialCount > 0 || snapshot != null) {
            _grantedCount.value = Resource.success(initialCount)
        }
        listeners.add(cacheListener)
    }

    override fun onCleared() {
        listeners.remove(cacheListener)
        super.onCleared()
    }

    fun load(onlyCount: Boolean = false) {
        val myGeneration = loadGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 若当前仅需刷新授权数量且已有应用列表缓存，直接对已知列表计数（耗时 < 1ms），无需全机重扫
                val currentCached = cachedPackages
                if (onlyCount && !currentCached.isNullOrEmpty() && Shizuku.pingBinder()) {
                    var count = 0
                    for (pi in currentCached) {
                        val ai = pi.applicationInfo ?: continue
                        if (AuthorizationManager.granted(pi.packageName, ai.uid)) count++
                    }
                    publishCache(prefs, currentCached, count)
                    return@launch
                }

                // 2. 冷启动/划掉后台后若内存缓存为空，先用毫秒级快照（< 2ms）瞬间点亮 UI，告别等待弹窗
                if (cachedPackages == null) {
                    val quickList = AuthorizationManager.getQuickPackagesFromSnapshot()
                    if (quickList.isNotEmpty()) {
                        var quickCount = 0
                        val binderReady = Shizuku.pingBinder()
                        if (binderReady) {
                            for (pi in quickList) {
                                val ai = pi.applicationInfo ?: continue
                                if (AuthorizationManager.granted(pi.packageName, ai.uid)) quickCount++
                            }
                        } else {
                            quickCount = prefs.getInt(KEY_LAST_GRANTED_COUNT, 0)
                        }
                        publishCache(prefs, quickList, quickCount)
                    }
                }

                // 3. 完整查询（内部带锁去重与 2s 节流保护，且服务端正常返回时绝不重复执行客户端全量扫描）
                val allPackages = AuthorizationManager.getPackages()

                // 对齐上游 Shizuku Plus (#424)：若服务正在运行且此前已有非零授权数，
                // 遇到瞬态 IPC 空返回时直接跳过，严禁将有效授权数误覆写为 0
                val prevCount = _grantedCount.value?.data ?: cachedGrantedCount ?: prefs.getInt(KEY_LAST_GRANTED_COUNT, 0)
                if (allPackages.isEmpty() && (ShizukuStateMachine.isRunning() || Shizuku.pingBinder()) && prevCount > 0) {
                    return@launch
                }

                val list: MutableList<PackageInfo> = ArrayList(allPackages.size)
                var count = 0
                for (pi in allPackages) {
                    val appInfo = pi.applicationInfo ?: continue
                    list.add(pi)
                    if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) count++
                }

                if (myGeneration != loadGeneration.get() && cachedPackages != null) return@launch

                publishCache(prefs, list, count)
            } catch (_: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                if (cachedPackages == null) {
                    _packages.postValue(Resource.error(e, null))
                    _grantedCount.postValue(Resource.error(e, 0))
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "shizuku_apps_cache"
        private const val KEY_LAST_GRANTED_COUNT = "last_granted_count"

        private val listeners = CopyOnWriteArrayList<(List<PackageInfo>, Int) -> Unit>()

        @Volatile
        var cachedPackages: List<PackageInfo>? = null

        @Volatile
        var cachedGrantedCount: Int? = null

        fun ensureSnapshotHydrated(prefs: android.content.SharedPreferences? = null) {
            if (cachedPackages != null) return
            val quickList = AuthorizationManager.getQuickPackagesFromSnapshot()
            if (quickList.isNotEmpty()) {
                cachedPackages = quickList
                if (Shizuku.pingBinder()) {
                    var count = 0
                    for (pi in quickList) {
                        val ai = pi.applicationInfo ?: continue
                        if (AuthorizationManager.granted(pi.packageName, ai.uid)) count++
                    }
                    cachedGrantedCount = count
                    prefs?.edit()?.putInt(KEY_LAST_GRANTED_COUNT, count)?.apply()
                } else if (cachedGrantedCount == null && prefs != null) {
                    cachedGrantedCount = prefs.getInt(KEY_LAST_GRANTED_COUNT, 0)
                }
            }
        }

        private fun publishCache(
            prefs: android.content.SharedPreferences,
            list: List<PackageInfo>,
            count: Int
        ) {
            cachedPackages = list
            cachedGrantedCount = count
            prefs.edit().putInt(KEY_LAST_GRANTED_COUNT, count).apply()
            for (listener in listeners) {
                runCatching { listener(list, count) }
            }
        }
    }
}
