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

/**
 * 跨 Activity 全局单例应用缓存（对齐架构设计：彻底消除每次打开应用管理时的空白与阻塞等待）
 */
object AppsRepository {
    @Volatile
    var cachedPackages: List<PackageInfo>? = null
    @Volatile
    var cachedGrantedCount: Int? = null
    @Volatile
    var lastLoadTime: Long = 0L

    fun isFresh(maxAgeMs: Long = 5000L): Boolean {
        return cachedPackages != null && (System.currentTimeMillis() - lastLoadTime < maxAgeMs)
    }

    fun clear() {
        cachedPackages = null
        cachedGrantedCount = null
        lastLoadTime = 0L
    }
}

@MainThread
fun ComponentActivity.appsViewModel() = viewModels { AppsViewModel(this) }

@MainThread
fun Fragment.appsViewModel() = activityViewModels { AppsViewModel(requireContext()) }

class AppsViewModel(context: Context) : ViewModel() {

    companion object {
        private val loadGeneration = AtomicInteger(0)
    }

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>().apply {
        AppsRepository.cachedPackages?.let {
            value = Resource.success(it)
        }
    }
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>().apply {
        AppsRepository.cachedGrantedCount?.let {
            value = Resource.success(it)
        }
    }
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    fun load(onlyCount: Boolean = false, force: Boolean = false) {
        if (!force && onlyCount && AppsRepository.isFresh(3000L)) {
            return
        }

        val myGeneration = loadGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list: MutableList<PackageInfo> = ArrayList()
                var count = 0
                val packages = AuthorizationManager.getPackages()

                // 与 Shizuku Plus #424 一致：
                // 若本次返回空但之前有缓存且服务处于存活态，避免瞬态 IPC 异常冲掉有效数据
                if (packages.isEmpty() && Shizuku.pingBinder() && (AppsRepository.cachedGrantedCount ?: 0) > 0) {
                    return@launch
                }

                for (pi in packages) {
                    list.add(pi)
                    if (AuthorizationManager.granted(pi.packageName, pi.applicationInfo!!.uid)) count++
                }

                if (myGeneration != loadGeneration.get()) return@launch

                AppsRepository.cachedPackages = list
                AppsRepository.cachedGrantedCount = count
                AppsRepository.lastLoadTime = System.currentTimeMillis()

                if (!onlyCount) _packages.postValue(Resource.success(list))
                _grantedCount.postValue(Resource.success(count))
            } catch (e: CancellationException) {

            } catch (e: Throwable) {
                if (AppsRepository.cachedPackages == null) {
                    _packages.postValue(Resource.error(e, null))
                    _grantedCount.postValue(Resource.error(e, 0))
                }
            }
        }
    }
}
