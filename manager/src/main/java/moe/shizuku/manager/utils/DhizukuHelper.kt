package moe.shizuku.manager.utils

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Html
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.admin.DhizukuAdminReceiver
import moe.shizuku.manager.maxhz.LiquidGlassTheme
import moe.shizuku.manager.maxhz.MaxHzShell

object DhizukuHelper {

    private const val TAG = "DhizukuHelper"

    const val OFFICIAL_PACKAGE_NAME = "com.rosan.dhizuku"
    const val OFFICIAL_ACTION_REQUEST_PERMISSION = "com.rosan.dhizuku.action.request.permission"
    const val OFFICIAL_PROVIDER_AUTHORITY = "com.rosan.dhizuku.server.provider"
    private const val OFFICIAL_BINDER_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizuku"
    private const val OFFICIAL_LISTENER_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener"

    @JvmStatic
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.isDeviceOwnerApp(context.packageName) == true
        } catch (t: Throwable) {
            Log.w(TAG, "isDeviceOwner check failed", t)
            false
        }
    }

    @JvmStatic
    fun isProfileOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.isProfileOwnerApp(context.packageName) == true
        } catch (t: Throwable) {
            Log.w(TAG, "isProfileOwner check failed", t)
            false
        }
    }

    @JvmStatic
    fun isOfficialDhizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(OFFICIAL_PACKAGE_NAME, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isOfficialDhizukuOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.isDeviceOwnerApp(OFFICIAL_PACKAGE_NAME) == true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isOfficialDhizukuGranted(context: Context): Boolean {
        return try {
            val uri = android.net.Uri.parse("content://$OFFICIAL_PROVIDER_AUTHORITY")
            val bundle = context.contentResolver.call(uri, "client", null, android.os.Bundle())
            val binder = bundle?.getBinder("dhizuku_binder")
            if (binder != null && binder.isBinderAlive) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken(OFFICIAL_BINDER_DESCRIPTOR)
                    binder.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 2 /* CODE_IS_PERMISSION_GRANTED */, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = DhizukuAdminReceiver.getComponentName(context)
            dpm?.isAdminActive(admin) == true
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun requestDeviceAdmin(activity: Activity) {
        try {
            val admin = DhizukuAdminReceiver.getComponentName(activity)
            val intent = android.content.Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "激活设备管理员权限以启用后台管理与特权守护功能")
            }
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch ACTION_ADD_DEVICE_ADMIN", t)
            Toast.makeText(activity, "无法调起系统设备管理器页面", Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun removeDeviceAdmin(context: Context): Boolean {
        return removeDeviceOwner(context)
    }

    @JvmStatic
    fun isPrivileged(context: Context): Boolean {
        return isDeviceOwner(context) || isProfileOwner(context) || isOfficialDhizukuGranted(context)
    }

    @JvmStatic
    fun requestOfficialDhizukuPermission(activity: Activity, onResult: (Boolean) -> Unit) {
        try {
            val listener = object : android.os.Binder() {
                override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    if (code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
                        try {
                            data.enforceInterface(OFFICIAL_LISTENER_DESCRIPTOR)
                            val grantCode = data.readInt()
                            val granted = grantCode == android.content.pm.PackageManager.PERMISSION_GRANTED
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onResult(granted)
                            }
                            return true
                        } catch (t: Throwable) {
                            Log.w(TAG, "Official Dhizuku callback error", t)
                        }
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val bundle = android.os.Bundle().apply {
                putInt("uid", activity.applicationInfo.uid)
                putBinder("request_permission_binder", listener)
            }

            val intent = android.content.Intent(OFFICIAL_ACTION_REQUEST_PERMISSION).apply {
                setPackage(OFFICIAL_PACKAGE_NAME)
                putExtras(bundle)
                putExtra("bundle", bundle)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Official Dhizuku permission request", e)
            Toast.makeText(activity, "无法调起独立版 Dhizuku 授权，请确认其正常运行", Toast.LENGTH_SHORT).show()
            onResult(false)
        }
    }

    data class BlockerDiagnostic(
        val accounts: List<String>,
        val secondaryUsers: List<Pair<String, String>>
    ) {
        val hasBlockers: Boolean get() = accounts.isNotEmpty() || secondaryUsers.isNotEmpty()
    }

    @JvmStatic
    fun checkDeviceOwnerBlockers(): BlockerDiagnostic {
        val accounts = mutableListOf<String>()
        val secondaryUsers = mutableListOf<Pair<String, String>>()

        if (MaxHzShell.isReady()) {
            try {
                val accountRes = MaxHzShell.exec("dumpsys account")
                val accountRegex = Regex("""Account\s*\{\s*name=(.+?),\s*type=(.+?)\s*\}""")
                accountRegex.findAll(accountRes.out).forEach { match ->
                    val name = match.groupValues[1].trim()
                    val type = match.groupValues[2].trim()
                    val friendlyName = when {
                        type.contains("google", ignoreCase = true) -> "Google 账号 ($name)"
                        type.contains("osp", ignoreCase = true) || type.contains("samsung", ignoreCase = true) -> "三星账户 ($name) [在系统设置最顶端]"
                        type.contains("tencent", ignoreCase = true) || type.contains("mm", ignoreCase = true) -> "微信账号 ($name)"
                        type.contains("mobileqq", ignoreCase = true) || type.contains("qq", ignoreCase = true) -> "QQ 账号 ($name)"
                        type.contains("alipay", ignoreCase = true) -> "支付宝账号 ($name)"
                        type.contains("microsoft", ignoreCase = true) || type.contains("exchange", ignoreCase = true) -> "微软账户 ($name)"
                        type.contains("xiaomi", ignoreCase = true) -> "小米账号 ($name)"
                        type.contains("huawei", ignoreCase = true) -> "华为账号 ($name)"
                        else -> "$name ($type)"
                    }
                    if (!accounts.contains(friendlyName)) {
                        accounts.add(friendlyName)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "checkDeviceOwnerBlockers accounts error", t)
            }

            try {
                val userRes = MaxHzShell.exec("pm list users")
                val userRegex = Regex("""UserInfo\{(\d+):([^:]*):""")
                userRegex.findAll(userRes.out).forEach { match ->
                    val id = match.groupValues[1].trim()
                    val name = match.groupValues[2].trim()
                    if (id != "0") {
                        val friendlyUser = when {
                            name.contains("Secure Folder", ignoreCase = true) || id == "150" ->
                                "三星「安全文件夹」(用户 ID $id) [需在设置->安全和隐私->安全文件夹中卸载]"
                            name.contains("Dual", ignoreCase = true) || id == "95" ->
                                "三星「应用分身」(用户 ID $id) [可在设置->高级功能->应用分身右上角点'删除所有我的数据']"
                            else -> "副用户/分身: $name (ID $id)"
                        }
                        secondaryUsers.add(id to friendlyUser)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "checkDeviceOwnerBlockers users error", t)
            }
        }
        return BlockerDiagnostic(accounts, secondaryUsers)
    }

    @JvmStatic
    fun activateDeviceOwnerOnDevice(context: Context): Pair<Boolean, String> {
        if (!MaxHzShell.isReady()) {
            return false to "当前未检测到活跃的特权环境（请先启动 Shizuku 或授予 Root 权限）"
        }
        val component = DhizukuAdminReceiver.getComponentName(context).flattenToShortString()
        var result = MaxHzShell.exec("dpm set-device-owner --user 0 $component")
        if (result.code != 0 && !isDeviceOwner(context)) {
            result = MaxHzShell.exec("dpm set-device-owner $component")
        }
        val isNowOwner = isDeviceOwner(context)
        return if (result.code == 0 || isNowOwner) {
            ensureSelfPermissions(context)
            true to "恭喜！设备所有者特权已成功在手机端激活！"
        } else {
            val errorText = result.err.ifEmpty { result.out }
            val friendlyMsg = when {
                errorText.contains("account", ignoreCase = true) ->
                    "激活受限：Android 系统安全机制规定，在手机已登录任何账户（Google、微信、小米/华为/三星账号等）时，禁止直接设置设备所有者。\n\n解决方法：\n1. 前往手机【系统设置 -> 账户】暂时退出/移除所有账号后再试，激活成功后即可立即登录回来！\n2. 若您已拥有 Root 权限，直接使用主页的【Root 启动】即可，Root 权限高于设备所有者，无需退账号！"
                errorText.contains("user", ignoreCase = true) ->
                    "激活受限：检测到系统存在多用户或应用分身（如微信双开、手机分身、安全文件夹等）。\n\n解决方法：请先在系统设置中关闭应用分身或副用户后重试。"
                errorText.contains("already set", ignoreCase = true) || errorText.contains("already an owner", ignoreCase = true) ->
                    "提示：系统已被其他应用设置为设备所有者。"
                errorText.contains("Can't set package", ignoreCase = true) ->
                    "激活受限：Android 系统底层拒绝了设备所有者配置。\n\n系统底层铁律要求（无法绕过）：\n1. 手机内不能存在任何已登录账户（请先去系统设置 -> 账户中暂时退出三星账号、Google 账号、微信等）；\n2. 不能开启三星「安全文件夹」或「应用双开（Dual Messenger）」（在底层属于独立用户，必须暂时关闭）；\n3. 不能存在访客等多用户。\n\n请按上述步骤暂时退出/关闭后重试，激活成功后即可立即登录并恢复！"
                else -> "激活未成功: $errorText"
            }
            false to friendlyMsg
        }
    }

    @JvmStatic
    fun performOnDeviceActivationFlow(
        activity: Activity,
        onStatusChanged: (() -> Unit)?,
        mainDialog: AlertDialog?
    ) {
        val scanProgress = MaterialAlertDialogBuilder(activity)
            .setTitle("正在进行环境深度扫描...")
            .setMessage("正在检测底层账户与分身系统状态，请稍候...")
            .setCancelable(false)
            .create()
        scanProgress.show()

        Thread {
            val diag = checkDeviceOwnerBlockers()
            activity.runOnUiThread {
                try { scanProgress.dismiss() } catch (_: Throwable) {}

                if (diag.hasBlockers) {
                    val sb = StringBuilder()
                    sb.append("Android 系统底层规定：配置设备所有者时，系统要求手机内不能存在任何已登录账户或副用户/分身。\n\n")
                    sb.append("🔍 【当前底层扫描到的阻碍残留项】：\n")
                    if (diag.accounts.isNotEmpty()) {
                        sb.append("\n📱 未退出账户 (共 ${diag.accounts.size} 个)：\n")
                        diag.accounts.forEach { acc ->
                            sb.append("• $acc\n")
                        }
                    }
                    if (diag.secondaryUsers.isNotEmpty()) {
                        sb.append("\n👥 未清除的分身/副用户 (共 ${diag.secondaryUsers.size} 个)：\n")
                        diag.secondaryUsers.forEach { (_, desc) ->
                            sb.append("• $desc\n")
                        }
                    }
                    sb.append("\n💡 提示：退出上述项目后即可成功激活！激活后可立即登录回所有账号并重新开启分身，特权永久生效。")

                    val hasDualMessenger = diag.secondaryUsers.any { it.first == "95" }

                    val builder = MaterialAlertDialogBuilder(activity)
                        .setTitle("设备所有者激活前检测")
                        .setMessage(sb.toString())
                        .setNeutralButton("前往系统账户") { _, _ ->
                            val intent = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                activity.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    activity.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                } catch (_: Exception) {}
                            }
                        }
                        .setPositiveButton("我已清理，立即激活") { _, _ ->
                            performOnDeviceActivationFlow(activity, onStatusChanged, mainDialog)
                        }
                        .setNegativeButton(android.R.string.cancel, null)

                    builder.show()

                    if (hasDualMessenger) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("发现应用双开残留 (User 95)")
                            .setMessage("检测到系统存在三星应用双开（Dual Messenger）副用户残留，是否由本应用直接为您一键清除？")
                            .setPositiveButton("一键清除双开数据") { _, _ ->
                                Thread {
                                    MaxHzShell.exec("pm remove-user 95")
                                    activity.runOnUiThread {
                                        Toast.makeText(activity, "已清除双开残留用户，正在重新扫描...", Toast.LENGTH_SHORT).show()
                                        performOnDeviceActivationFlow(activity, onStatusChanged, mainDialog)
                                    }
                                }.start()
                            }
                            .setNegativeButton("稍后手动处理", null)
                            .show()
                    }
                } else {
                    executeActivationWithProgress(activity, onStatusChanged, mainDialog)
                }
            }
        }.start()
    }

    private fun executeActivationWithProgress(
        activity: Activity,
        onStatusChanged: (() -> Unit)?,
        mainDialog: AlertDialog?
    ) {
        val activatingProgress = MaterialAlertDialogBuilder(activity)
            .setTitle("正在激活设备所有者...")
            .setMessage("正在配置系统底层特权，请稍候...")
            .setCancelable(false)
            .create()
        activatingProgress.show()

        Thread {
            val (success, msg) = activateDeviceOwnerOnDevice(activity)
            activity.runOnUiThread {
                try { activatingProgress.dismiss() } catch (_: Throwable) {}

                if (success) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("🎉 激活成功")
                        .setMessage("设备所有者特权已成功激活！\n\n您现在可以立刻前往系统设置把刚才退出的所有账号（包括三星账户、Google 邮箱等）全部登录回来，也可以重新开启应用双开与安全文件夹。此特权永久有效，重启手机无需再次配置或连接电脑。")
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            onStatusChanged?.invoke()
                            mainDialog?.dismiss()
                        }
                        .show()
                } else {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("激活提示")
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    @JvmStatic
    fun getAdbCommand(context: Context): String {
        val component = DhizukuAdminReceiver.getComponentName(context).flattenToShortString()
        return "adb shell dpm set-device-owner --user 0 $component"
    }

    @JvmStatic
    fun getAdbRemoveCommand(context: Context): String {
        return "adb shell am broadcast -a moe.shizuku.manager.action.REMOVE_DEVICE_OWNER -p ${context.packageName}"
    }

    @JvmStatic
    fun ensureSelfPermissions(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = DhizukuAdminReceiver.getComponentName(context)
            if (dpm != null) {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                try {
                    dpm.setPermissionGrantState(
                        admin,
                        context.packageName,
                        android.Manifest.permission.WRITE_SETTINGS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (_: Throwable) {}
                try {
                    dpm.setPermittedAccessibilityServices(admin, null)
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to ensureSelfPermissions", t)
        }
    }

    @JvmStatic
    fun removeDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = DhizukuAdminReceiver.getComponentName(context)

            // 1. MUST clear Device Owner FIRST
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                try {
                    dpm.clearDeviceOwnerApp(context.packageName)
                    Log.i(TAG, "clearDeviceOwnerApp succeeded")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed clearDeviceOwnerApp", t)
                }
            }

            // 2. Clear Profile Owner if set
            if (dpm != null && dpm.isProfileOwnerApp(context.packageName)) {
                try {
                    dpm.clearProfileOwner(admin)
                    Log.i(TAG, "clearProfileOwner succeeded")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed clearProfileOwner", t)
                }
            }

            // 3. Remove Active Admin now that it is no longer DO or PO
            if (dpm != null && dpm.isAdminActive(admin)) {
                try {
                    dpm.removeActiveAdmin(admin)
                    Log.i(TAG, "removeActiveAdmin succeeded")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed removeActiveAdmin", t)
                }
            }

            // 4. Shell fallback and unlock restricted settings if shell ready
            try {
                if (MaxHzShell.isReady()) {
                    val comp = admin.flattenToShortString()
                    MaxHzShell.exec("dpm remove-active-admin $comp")
                    MaxHzShell.exec("appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow")
                }
            } catch (_: Throwable) {}

            val stillOwner = dpm?.isDeviceOwnerApp(context.packageName) == true
            val stillAdmin = dpm?.isAdminActive(admin) == true
            !stillOwner && !stillAdmin
        } catch (t: Throwable) {
            Log.e(TAG, "Failed in removeDeviceOwner", t)
            false
        }
    }

    @JvmStatic
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Dhizuku Command", text)
        clipboard?.setPrimaryClip(clip)
        try {
            Toast.makeText(context, context.getString(R.string.toast_copied_to_clipboard, text), Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun showSetupDialog(activity: Activity, onStatusChanged: (() -> Unit)? = null) {
        val isPriv = isPrivileged(activity)
        val isOwner = isDeviceOwner(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        scrollView.addView(container)

        fun createCard(): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val bg = GradientDrawable().apply {
                    setColor(if (isDark) 0x1AFFFFFF else 0x08000000)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), if (isDark) 0x22FFFFFF else 0x12000000)
                }
                background = bg
            }
        }

        // Status Badge
        val isOnlyAdmin = !isPriv && isAdminActive(activity)
        val statusBadge = TextView(activity).apply {
            text = when {
                isPriv -> "✓ " + activity.getString(R.string.dhizuku_status_active)
                isOnlyAdmin -> "⚠️ 仅普通设备管理器 (非设备所有者 · 无特权)"
                else -> "○ " + activity.getString(R.string.dhizuku_status_inactive)
            }
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            val textColor = when {
                isPriv -> if (isDark) 0xFF34D399.toInt() else 0xFF059669.toInt()
                isOnlyAdmin -> if (isDark) 0xFFF87171.toInt() else 0xFFDC2626.toInt()
                else -> if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
            }
            val bgColor = when {
                isPriv -> if (isDark) 0x2634D399 else 0x1A059669
                isOnlyAdmin -> if (isDark) 0x26F87171 else 0x1ADC2626
                else -> if (isDark) 0x26FBBF24 else 0x1AD97706
            }
            setTextColor(textColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(12).toFloat()
            }
        }
        container.addView(statusBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        var mainDialog: AlertDialog? = null

        if (isPriv) {
            // Already Activated Section
            val introTv = TextView(activity).apply {
                text = activity.getString(R.string.dhizuku_active_desc)
                textSize = 13f
                setTextColor(if (isDark) 0xDDFFFFFF.toInt() else 0xDD000000.toInt())
                setLineSpacing(dp(3).toFloat(), 1.0f)
            }
            container.addView(introTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            })

            // Card: Deactivation / Stop Admin Button
            val cancelCard = createCard().apply {
                val title = TextView(activity).apply {
                    text = "🚫 停用 / 撤销设备所有者特权"
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isDark) 0xFFF87171.toInt() else 0xFFDC2626.toInt())
                }
                addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                })

                val content = TextView(activity).apply {
                    text = "停用后本应用将恢复为普通模式，随时可重新激活。可直接点击下方红色按钮在手机端一键停用，也可在电脑运行撤销命令："
                    textSize = 12f
                    setTextColor(if (isDark) 0xBBFFFFFF.toInt() else 0xBB000000.toInt())
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                }
                addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })

                val removeAdminBtn = MaterialButton(activity).apply {
                    text = "一键停用设备所有者特权"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    background = LiquidGlassTheme.createButtonDrawable(dp(12).toFloat(), isDark, isDanger = true)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.dhizuku_remove_owner)
                            .setMessage("确定要彻底停用并退出设备所有者特权吗？\n\n本应用将立即清除设备所有者与设备管理器身份，恢复为普通模式，绝不残留，随时可卸载或重新激活。")
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val success = removeDeviceOwner(activity)
                                if (success) {
                                    Toast.makeText(activity, "设备所有者与管理员已彻底停用！随时可正常卸载或重新激活。", Toast.LENGTH_LONG).show()
                                    onStatusChanged?.invoke()
                                    mainDialog?.dismiss()
                                } else {
                                    Toast.makeText(activity, "停用失败，请在【系统设置 -> 安全/隐私 -> 设备管理器】中手动关闭", Toast.LENGTH_LONG).show()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                addView(removeAdminBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

                val copyRemoveBtn = MaterialButton(activity).apply {
                    text = "复制 ADB 强制撤销命令"
                    textSize = 12f
                    setTextColor(if (isDark) 0xDDFFFFFF.toInt() else 0xDD000000.toInt())
                    background = LiquidGlassTheme.createButtonDrawable(dp(12).toFloat(), isDark, isPrimary = false)
                    setOnClickListener {
                        copyToClipboard(activity, getAdbRemoveCommand(activity))
                        Toast.makeText(activity, "停用命令已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                    }
                }
                addView(copyRemoveBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
                    topMargin = dp(8)
                })

                val cancelTip = TextView(activity).apply {
                    text = "✅ 随时可撤销：点击上方红色按钮即可退出设备所有者特权；电脑端亦可通过上方 ADB 广播指令随时强制撤销。"
                    textSize = 11.5f
                    setTextColor(if (isDark) 0x88FFFFFF.toInt() else 0x88000000.toInt())
                    setPadding(0, dp(8), 0, 0)
                }
                addView(cancelTip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            container.addView(cancelCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            // Not Activated Setup Guide
            val introTv = TextView(activity).apply {
                text = "Dhizuku (设备所有者) 是 Android 系统最高企业级特权。一次配置即可终身抗重启，无需依赖 Wi-Fi 无线调试，且能自动自授系统安全设置权限！"
                textSize = 13f
                setTextColor(if (isDark) 0xDDFFFFFF.toInt() else 0xDD000000.toInt())
                setLineSpacing(dp(3).toFloat(), 1.0f)
            }
            container.addView(introTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            })

            // Card 0: Direct On-Device Activation (if Shell / Root is available)
            if (MaxHzShell.isReady()) {
                val directCard = createCard().apply {
                    val title = TextView(activity).apply {
                        text = "⚡ 手机端一键就地激活 (免连电脑)"
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(if (isDark) 0xFF34D399.toInt() else 0xFF059669.toInt())
                    }
                    addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    })

                    val desc = TextView(activity).apply {
                        text = "检测到当前特权环境（Shizuku 服务或 Root 权限已生效），可直接在手机上一键配置为设备所有者，无需连接电脑！"
                        textSize = 12f
                        setTextColor(if (isDark) 0xBBFFFFFF.toInt() else 0xBB000000.toInt())
                    }
                    addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    })

                    val directBtn = MaterialButton(activity).apply {
                        text = "✨ 手机端就地激活设备所有者"
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        background = LiquidGlassTheme.createButtonDrawable(dp(12).toFloat(), isDark, isPrimary = true)
                        setOnClickListener {
                            performOnDeviceActivationFlow(activity, onStatusChanged, mainDialog)
                        }
                    }
                    addView(directBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
                }
                container.addView(directCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                })
            }

            if (isAdminActive(activity)) {
                val adminCard = createCard().apply {
                    val title = TextView(activity).apply {
                        text = activity.getString(R.string.dhizuku_method_admin_title)
                        textSize = 13.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(if (isDark) 0xFFFBBF24.toInt() else 0xFFD97706.toInt())
                    }
                    addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    })

                    val content = TextView(activity).apply {
                        text = activity.getString(R.string.dhizuku_method_admin_desc)
                        textSize = 12f
                        setTextColor(if (isDark) 0xBBFFFFFF.toInt() else 0xBB000000.toInt())
                        setLineSpacing(dp(2).toFloat(), 1.0f)
                    }
                    addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    })

                    val disableBtn = MaterialButton(activity).apply {
                        text = "一键停用普通设备管理器"
                        textSize = 12.5f
                        setTextColor(if (isDark) 0xFFF87171.toInt() else 0xFFDC2626.toInt())
                        background = LiquidGlassTheme.createButtonDrawable(dp(12).toFloat(), isDark, isPrimary = false)
                        setOnClickListener {
                            removeDeviceOwner(activity)
                            Toast.makeText(activity, "已停用普通设备管理器", Toast.LENGTH_SHORT).show()
                            mainDialog?.dismiss()
                            onStatusChanged?.invoke()
                        }
                    }
                    addView(disableBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
                }
                container.addView(adminCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                })
            }

            // Card 1: ADB PC Activation (Official Device Owner)
            val adbCard = createCard().apply {
                val title = TextView(activity).apply {
                    text = "💻 电脑 ADB 激活 (官方标准 · 免 Root)"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isDark) 0xFF38BDF8.toInt() else 0xFF0284C7.toInt())
                }
                addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                })

                val desc = TextView(activity).apply {
                    text = "手机连接电脑后在终端运行以下命令，即可一次配置终身生效（即使重启也无需再连电脑）："
                    textSize = 12f
                    setTextColor(if (isDark) 0xBBFFFFFF.toInt() else 0xBB000000.toInt())
                }
                addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })

                val cmdBox = TextView(activity).apply {
                    text = getAdbCommand(activity)
                    textSize = 11.5f
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(if (isDark) 0xFFE2E8F0.toInt() else 0xFF1E293B.toInt())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(if (isDark) 0x33000000 else 0x1A000000)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                addView(cmdBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })

                val copyBtn = MaterialButton(activity).apply {
                    text = "一键复制 ADB 激活命令"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    background = LiquidGlassTheme.createButtonDrawable(dp(12).toFloat(), isDark, isPrimary = true)
                    setOnClickListener {
                        copyToClipboard(activity, getAdbCommand(activity))
                        Toast.makeText(activity, "命令已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                    }
                }
                addView(copyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

                val compareNote = TextView(activity).apply {
                    text = "💡 提示：本应用支持随时在应用内一键停用设备所有者特权，恢复为普通模式，绝无残留。"
                    textSize = 11f
                    setTextColor(if (isDark) 0x88FFFFFF.toInt() else 0x88000000.toInt())
                    setPadding(0, dp(8), 0, 0)
                }
                addView(compareNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            container.addView(adbCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })

            // Card 2: QR Provisioning (Factory Reset / Out-of-the-Box)
            val qrCard = createCard().apply {
                val title = TextView(activity).apply {
                    text = "📦 扫码开箱配置 (出厂 / 新机模式)"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isDark) 0xFFA78BFA.toInt() else 0xFF7C3AED.toInt())
                }
                addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                })

                val desc = TextView(activity).apply {
                    text = "若设备处于新机刚开箱或刚恢复出厂设置后的【欢迎设置向导】界面：\n连续点击屏幕空白处 6 次打开扫码摄像头，扫描预设的设备所有者配置二维码，即可免电脑全自动完成特权配置。"
                    textSize = 12f
                    setTextColor(if (isDark) 0xBBFFFFFF.toInt() else 0xBB000000.toInt())
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                }
                addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            container.addView(qrCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
            // Card: How to Deactivate / Cancel FAQ
            val howToCancelCard = createCard().apply {
                val title = TextView(activity).apply {
                    text = "❓ 启用后如何停用 / 撤销？"
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isDark) 0xFF34D399.toInt() else 0xFF059669.toInt())
                }
                addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                })

                val content = TextView(activity).apply {
                    text = "完全可以随时自主取消！本应用绝不锁死设备，提供三种撤销途径：\n\n" +
                            "① 应用内一键停用：激活后随时打开此弹窗，直接点击红色【停用设备所有者】按钮，秒级解除；\n" +
                            "② 电脑 ADB 撤销命令（终端运行下方命令，点击可一键复制）："
                    textSize = 12f
                    setTextColor(if (isDark) 0xBBFFFFFF.toInt() else 0xBB000000.toInt())
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                }
                addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })

                val cmdBox = TextView(activity).apply {
                    text = getAdbRemoveCommand(activity)
                    textSize = 11.5f
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(if (isDark) 0xFFE2E8F0.toInt() else 0xFF1E293B.toInt())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        setColor(if (isDark) 0x33000000 else 0x1A000000)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                addView(cmdBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })

                val copyRemoveBtn = MaterialButton(activity).apply {
                    text = "一键复制 ADB 停用命令"
                    textSize = 12.5f
                    setTextColor(Color.WHITE)
                    background = LiquidGlassTheme.createButtonDrawable(dp(12).toFloat(), isDark, isPrimary = false)
                    setOnClickListener {
                        copyToClipboard(activity, getAdbRemoveCommand(activity))
                        Toast.makeText(activity, "停用命令已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                    }
                }
                addView(copyRemoveBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

                val extraTip = TextView(activity).apply {
                    text = "③ 系统设置手动关闭：亦可在手机【系统设置 -> 安全/隐私 -> 设备管理器】中直接手动撤销并正常卸载。"
                    textSize = 11.5f
                    setTextColor(if (isDark) 0x88FFFFFF.toInt() else 0x88000000.toInt())
                    setPadding(0, dp(8), 0, 0)
                }
                addView(extraTip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            container.addView(howToCancelCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (isPriv) R.string.dhizuku_manage_title else R.string.dhizuku_setup_title)
            .setView(scrollView)
            .setPositiveButton(R.string.dialog_close, null)

        mainDialog = builder.create()
        mainDialog.show()
    }
}
