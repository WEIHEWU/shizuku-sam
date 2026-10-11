package moe.shizuku.manager.maxhz.shell

import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Shizuku 与 MaxHz Shell 之间的特权进程桥梁。
 *
 * 封装了对 Shizuku 底层特权进程的调用，提供统一的启动入口、可用性检查与错误翻译。
 */
object ShizukuShellBridge {

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 检查 Shizuku 服务当前是否可用并响应 Binder 通信。
     */
    fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 检查当前应用是否已被授予 Shizuku 权限。
     */
    fun isPermissionGranted(): Boolean {
        return try {
            if (!isReady()) false
            else Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 启动特权 /system/bin/sh 进程。
     *
     * @throws IllegalStateException 当 Shizuku 未就绪或未找到对应 API 时
     * @throws SecurityException 当调用方权限不足时
     */
    fun newShellProcess(): Process {
        if (!isReady()) {
            throw IllegalStateException("Shizuku service is not running or binder is dead.")
        }
        val method = newProcessMethod
            ?: throw IllegalStateException("Shizuku.newProcess API method not accessible.")
        return try {
            try {
                method.invoke(
                    null,
                    arrayOf("su"),
                    null,
                    null
                ) as Process
            } catch (t: Throwable) {
                method.invoke(
                    null,
                    arrayOf("/system/bin/sh"),
                    null,
                    null
                ) as Process
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val target = e.targetException ?: e
            if (target is SecurityException) {
                throw target
            }
            throw RuntimeException("Failed to invoke Shizuku.newProcess: ${target.message}", target)
        }
    }

    /**
     * 将异常信息解析为面向用户的可读提示。
     */
    fun getReadableErrorMessage(t: Throwable): String {
        val message = t.cause?.message ?: t.message ?: t.javaClass.simpleName
        return if (
            message.contains("permission", ignoreCase = true) ||
            message.contains("denied", ignoreCase = true) ||
            message.contains("SecurityException", ignoreCase = true)
        ) {
            "Shell 启动失败：Shizuku 未授权或权限已失效，请在应用中确认 Shizuku 权限"
        } else {
            "Shell 启动失败：$message"
        }
    }
}
