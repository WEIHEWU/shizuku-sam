package moe.shizuku.manager.maxhz

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MaxHz 控制台 UI 状态与操作 ViewModel。
 */
class MaxHzViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MaxHzRepository(application)

    private val _controlEnabled = MutableLiveData(repository.isControlEnabled())
    val controlEnabled: LiveData<Boolean> = _controlEnabled

    private val _isExecuting = MutableLiveData(false)
    val isExecuting: LiveData<Boolean> = _isExecuting

    private val _lastResult = MutableLiveData<MaxHzShell.Result?>()
    val lastResult: LiveData<MaxHzShell.Result?> = _lastResult

    fun refreshState() {
        _controlEnabled.value = repository.isControlEnabled()
    }

    fun setControlEnabled(enabled: Boolean) {
        repository.setControlEnabled(enabled)
        _controlEnabled.value = enabled
    }

    fun runCommand(script: String, timeoutSeconds: Long = 8, onComplete: ((MaxHzShell.Result) -> Unit)? = null) {
        _isExecuting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val res = repository.executeShell(script, timeoutSeconds)
            _lastResult.postValue(res)
            _isExecuting.postValue(false)
            onComplete?.invoke(res)
        }
    }
}
