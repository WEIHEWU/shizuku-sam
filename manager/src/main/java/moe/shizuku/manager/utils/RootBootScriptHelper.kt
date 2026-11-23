package moe.shizuku.manager.utils

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants

object RootBootScriptHelper {

    private const val BOOT_SCRIPT_NAME = "00_shizuku_boot.sh"
    private const val MAGISK_SERVICE_D = "/data/adb/service.d"

    fun installBootScript() {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val shell = Shell.getShell()
                if (!shell.isRoot) return@launch

                val scriptContent = """
                    #!/system/bin/sh
                    # Shizuku Root Boot Auto-starter for Magisk / KernelSU / APatch
                    while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
                        sleep 1
                    done
                    sleep 2
                    if /system/bin/pidof shizuku_server >/dev/null 2>&1; then
                        exit 0
                    fi

                    APK_PATH=${'$'}(pm path moe.shizuku.privileged.api 2>/dev/null | head -n1 | cut -d: -f2 | tr -d '\r')
                    if [ -n "${'$'}APK_PATH" ]; then
                        BASE_DIR=${'$'}(dirname "${'$'}APK_PATH")
                        LIB_SO=${'$'}(find "${'$'}BASE_DIR/lib" -name "libshizuku.so" 2>/dev/null | head -n1)
                        if [ -n "${'$'}LIB_SO" ] && [ -f "${'$'}LIB_SO" ]; then
                            chmod 755 "${'$'}LIB_SO" 2>/dev/null || true
                            "${'$'}LIB_SO" --apk="${'$'}APK_PATH" >/dev/null 2>&1 &
                        fi
                    fi

                    if [ -f "/data/local/tmp/shizuku" ]; then
                        /data/local/tmp/shizuku >/dev/null 2>&1 &
                    fi
                """.trimIndent()

                Shell.cmd(
                    "mkdir -p $MAGISK_SERVICE_D",
                    "cat << 'EOF' > $MAGISK_SERVICE_D/$BOOT_SCRIPT_NAME\n$scriptContent\nEOF",
                    "chmod 755 $MAGISK_SERVICE_D/$BOOT_SCRIPT_NAME"
                ).exec()
                Log.i(AppConstants.TAG, "Root boot script installed to $MAGISK_SERVICE_D/$BOOT_SCRIPT_NAME")
            }.onFailure {
                Log.w(AppConstants.TAG, "Failed to install root boot script", it)
            }
        }
    }

    fun removeBootScript() {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val shell = Shell.getShell()
                if (!shell.isRoot) return@launch
                Shell.cmd("rm -f $MAGISK_SERVICE_D/$BOOT_SCRIPT_NAME").exec()
                Log.i(AppConstants.TAG, "Root boot script removed from $MAGISK_SERVICE_D/$BOOT_SCRIPT_NAME")
            }.onFailure {
                Log.w(AppConstants.TAG, "Failed to remove root boot script", it)
            }
        }
    }
}
