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
import rikka.lifecycle.Resource
import rikka.lifecycle.activityViewModels
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicInteger

@MainThread
fun ComponentActivity.appsViewModel() = viewModels { AppsViewModel(this) }

@MainThread
fun Fragment.appsViewModel() = activityViewModels { AppsViewModel(requireContext()) }

class AppsViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 对齐上游 Shizuku Plus (#317)：单调递增代数令牌，防止 binderReceivedListener 与 onResume 并发 load() 时旧结果覆盖新结果
    private val loadGeneration = AtomicInteger(0)

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>().apply {
        val snapshot = cachedPackages
        if (snapshot != null) {
            value = Resource.success(snapshot)
        }
    }
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>().apply {
        val initialCount = cachedGrantedCount ?: prefs.getInt(KEY_LAST_GRANTED_COUNT, 0)
        if (initialCount > 0 || cachedPackages != null) {
            value = Resource.success(initialCount)
        }
    }
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    fun load(onlyCount: Boolean = false) {
        val myGeneration = loadGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allPackages = AuthorizationManager.getPackages()

                // 对齐上游 Shizuku Plus (#424)：若服务正在运行且此前已有非零授权数，
                // 遇到瞬态 IPC 空返回时直接跳过，严禁将有效授权数误覆写为 0
                val prevCount = _grantedCount.value?.data ?: cachedGrantedCount ?: prefs.getInt(KEY_LAST_GRANTED_COUNT, 0)
                if (allPackages.isEmpty() && Shizuku.pingBinder() && prevCount > 0) {
                    return@launch
                }

                val list: MutableList<PackageInfo> = ArrayList(allPackages.size)
                var count = 0
                for (pi in allPackages) {
                    val appInfo = pi.applicationInfo ?: continue
                    list.add(pi)
                    if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) count++
                }

                if (myGeneration != loadGeneration.get()) return@launch

                cachedPackages = list
                cachedGrantedCount = count
                prefs.edit().putInt(KEY_LAST_GRANTED_COUNT, count).apply()

                if (!onlyCount || _packages.value == null) {
                    _packages.postValue(Resource.success(list))
                }
                _grantedCount.postValue(Resource.success(count))
            } catch (e: CancellationException) {
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

        @Volatile
        var cachedPackages: List<PackageInfo>? = null

        @Volatile
        var cachedGrantedCount: Int? = null
    }
}
