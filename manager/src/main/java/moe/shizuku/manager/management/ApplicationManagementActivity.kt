package moe.shizuku.manager.management

import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.withContext
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.home.GroupHeaderWithToggle
import moe.shizuku.manager.home.MiuixSubPageTopBar
import moe.shizuku.manager.home.onLongPressToggle
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.maxhz.BackdropProvider
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.maxhz.GlassButtonStyle
import moe.shizuku.manager.maxhz.GlassCard
import moe.shizuku.manager.maxhz.GlassLight
import moe.shizuku.manager.maxhz.GlassPillButton
import moe.shizuku.manager.maxhz.TitaniumBlack
import moe.shizuku.manager.maxhz.TitaniumDark
import moe.shizuku.manager.maxhz.getSystemOrAppPrimaryColor
import moe.shizuku.manager.utils.AppIconCache
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.lifecycle.Status
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import moe.shizuku.manager.home.CenteredAppDialog
import java.util.Objects

class ApplicationManagementActivity : AppActivity() {

    private val viewModel by appsViewModel()
    private val packagesState = mutableStateOf<List<PackageInfo>>(emptyList())
    private val isLoadingState = mutableStateOf(true)
    private val refreshTickState = mutableIntStateOf(0)
    private var isFirstResume = true

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when {
            ShizukuStateMachine.isDead() && !Shizuku.pingBinder() && !isFinishing -> finish()
            state == ShizukuStateMachine.State.RUNNING -> viewModel.load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder() && ShizukuStateMachine.isDead()) {
            finish()
            return
        }

        // 先用毫秒级快照填充缓存，确保首帧 0 等待直接渲染已授权应用列表
        AppsViewModel.ensureSnapshotHydrated()
        val initialSnapshot = AppsViewModel.cachedPackages
        if (initialSnapshot != null) {
            packagesState.value = initialSnapshot
            isLoadingState.value = false
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val dm = resources.displayMetrics
        val dialogWidth = minOf((dm.widthPixels * 0.92f).toInt(), (520 * dm.density).toInt())
        val maxHeightDp = (dm.heightPixels * 0.82f / dm.density).dp

        window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            win.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setDimAmount(0.58f)
            win.setGravity(Gravity.CENTER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                win.attributes = win.attributes.apply { blurBehindRadius = 56 }
            }
        }

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    packagesState.value = it.data ?: emptyList()
                    isLoadingState.value = false
                    refreshTickState.intValue++
                }
                Status.ERROR -> {
                    isLoadingState.value = false
                    val tr = it.error
                    LOGGER.w(tr, "load packages error")
                }
                Status.LOADING -> {
                    if (packagesState.value.isEmpty()) {
                        isLoadingState.value = true
                    }
                }
            }
        }

        ShizukuStateMachine.addListener(stateListener)

        val isDark = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsDark(this)
        val isBlackNight = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsBlackNight(this, isDark)

        val dialogBgColor = when {
            isBlackNight -> Color(0xFF0A0C10)
            isDark -> Color(0xFA161B22)
            else -> Color(0xFAFFFDFB)
        }
        val dialogStrokeColor = if (isDark) TitaniumDark.Edge else GlassLight.DialogHairline

        setContent {
            GlassAppTheme(isDark = isDark) {
                val primaryColor = getSystemOrAppPrimaryColor(isDark)
                BackdropProvider {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .heightIn(max = maxHeightDp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(dialogBgColor)
                            .border(1.dp, dialogStrokeColor, RoundedCornerShape(24.dp))
                    ) {
                        ApplicationManagementMiuixScreen(
                            isDark = isDark,
                            isBlackNightTheme = isBlackNight,
                            primaryColor = primaryColor,
                            packages = packagesState.value,
                            isLoading = isLoadingState.value,
                            refreshTick = refreshTickState.intValue,
                            onBackClick = { finish() },
                            onReloadCount = {
                                viewModel.load(onlyCount = true)
                                refreshTickState.intValue++
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 对齐上游 Shizuku Plus：跳过紧跟在 onCreate 后的首次 onResume（stateListener 已触发首次 load），
        // 避免首帧重复发起两次并发全量扫描；从外部返回时再静默刷新授权状态。
        if (isFirstResume) {
            isFirstResume = false
        } else {
            viewModel.load()
        }
        refreshTickState.intValue++
    }
}

@Composable
private fun ApplicationManagementMiuixScreen(
    isDark: Boolean,
    isBlackNightTheme: Boolean,
    primaryColor: Color,
    packages: List<PackageInfo>,
    isLoading: Boolean,
    refreshTick: Int,
    onBackClick: () -> Unit,
    onReloadCount: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val iconSizePx = with(density) { 42.dp.roundToPx() }

    var groupExpanded by remember { mutableStateOf(false) }
    var showAdbLimitedDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .overScrollVertical(),
        contentPadding = PaddingValues(
            top = 12.dp,
            start = 14.dp,
            end = 14.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            item(key = "header-top") {
                MiuixSubPageTopBar(
                    title = stringResource(R.string.home_app_management_title),
                    subtitle = "管理所有请求 Shizuku 权限的应用，长按单行或点击右上角按钮可展开/隐藏应用包名",
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = groupExpanded,
                    onToggleExpand = { groupExpanded = !groupExpanded },
                    onBackClick = onBackClick
                )
            }

            if (isLoading && packages.isEmpty()) {
                item(key = "card-loading") {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        isDark = isDark,
                        isBlackNightTheme = isBlackNightTheme,
                        cornerRadius = 20.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "正在读取已安装应用列表...",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            } else if (packages.isEmpty()) {
                item(key = "card-empty") {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        isDark = isDark,
                        isBlackNightTheme = isBlackNightTheme,
                        cornerRadius = 20.dp
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂未发现声明 Shizuku 权限的应用",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            } else {
                item(key = "section-apps") {
                    Column {
                        GroupHeaderWithToggle(
                            text = "授权应用列表 (${packages.size})",
                            isDark = isDark,
                            primaryColor = primaryColor,
                            expanded = groupExpanded,
                            onToggle = { groupExpanded = !groupExpanded }
                        )
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            isDark = isDark,
                            isBlackNightTheme = isBlackNightTheme,
                            cornerRadius = 20.dp
                        ) {
                            Column {
                                packages.forEach { pkgInfo ->
                                    val ai = pkgInfo.applicationInfo ?: return@forEach
                                    val uid = ai.uid
                                    val pkgName = pkgInfo.packageName
                                    key("${pkgName}_${uid}") {
                                        AppManagementMiuixRow(
                                            pkgInfo = pkgInfo,
                                            isDark = isDark,
                                            primaryColor = primaryColor,
                                            iconSizePx = iconSizePx,
                                            groupExpanded = groupExpanded,
                                            refreshTick = refreshTick,
                                            onAdbLimited = { showAdbLimitedDialog = true },
                                            onToggled = onReloadCount
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    // Miuix 窗口级弹窗：ADB 权限受限提示
    CenteredAppDialog(
        show = showAdbLimitedDialog,
        title = stringResource(R.string.app_management_dialog_adb_is_limited_title),
        onDismissRequest = { showAdbLimitedDialog = false }
    ) {
        val rawMsg = remember {
            context.getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get())
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
                .toString()
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = rawMsg,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassPillButton(
                    text = "查看文档",
                    isDark = isDark,
                    style = GlassButtonStyle.Regular,
                    onClick = {
                        showAdbLimitedDialog = false
                        CustomTabsHelper.launchUrlOrCopy(context, Helps.ADB.get())
                    },
                    modifier = Modifier.weight(1f)
                )
                GlassPillButton(
                    text = stringResource(android.R.string.ok),
                    isDark = isDark,
                    style = GlassButtonStyle.Prominent(primaryColor),
                    onClick = { showAdbLimitedDialog = false },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AppManagementMiuixRow(
    pkgInfo: PackageInfo,
    isDark: Boolean,
    primaryColor: Color,
    iconSizePx: Int,
    groupExpanded: Boolean,
    refreshTick: Int,
    onAdbLimited: () -> Unit,
    onToggled: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val pm = context.packageManager
    val ai = pkgInfo.applicationInfo ?: return
    val pkgName = pkgInfo.packageName
    val uid = ai.uid

    var rowToggled by remember(groupExpanded) { mutableStateOf(false) }
    val showSubtitle = groupExpanded xor rowToggled

    val appLabel = remember(pkgName, uid) {
        runCatching {
            val userId = UserHandleCompat.getUserId(uid)
            val baseLabel = ai.loadLabel(pm).toString()
            if (userId != UserHandleCompat.myUserId()) {
                val userInfo = ShizukuSystemApis.getUserInfo(userId)
                "$baseLabel - ${userInfo.name} ($userId)"
            } else {
                baseLabel
            }
        }.getOrDefault(pkgName)
    }

    val requiresRoot = remember(pkgName, uid) {
        runCatching {
            ai.metaData != null && ai.metaData.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT")
        }.getOrDefault(false)
    }

    var granted by remember(pkgName, uid, refreshTick) {
        mutableStateOf(runCatching { AuthorizationManager.granted(pkgName, uid) }.getOrDefault(false))
    }

    val iconBitmap by produceState<Bitmap?>(
        initialValue = remember(pkgName, uid) {
            runCatching { AppIconCache.get(pkgName, uid / 100000, iconSizePx) }.getOrNull()
        },
        pkgName,
        uid
    ) {
        if (value == null) {
            value = withContext(AppIconCache.dispatcher()) {
                runCatching {
                    AppIconCache.getOrLoadBitmap(context, ai, uid / 100000, iconSizePx)
                }.getOrNull()
            }
        }
    }

    val toggleGrant: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        try {
            if (AuthorizationManager.granted(pkgName, uid)) {
                AuthorizationManager.revoke(pkgName, uid)
            } else {
                AuthorizationManager.grant(pkgName, uid)
            }
            granted = AuthorizationManager.granted(pkgName, uid)
            onToggled()
        } catch (e: SecurityException) {
            val serverUid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            if (serverUid != 0 && serverUid != -1) {
                onAdbLimited()
            }
        } catch (_: Throwable) {
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onLongPressToggle(haptic) { rowToggled = !rowToggled }
    ) {
        BasicComponent(
            title = appLabel,
            summary = if (showSubtitle) pkgName else null,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
            onClick = toggleGrant,
            startAction = {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = iconBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = appLabel,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(primaryColor.copy(alpha = if (isDark) 0.22f else 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                painter = painterResource(R.drawable.ic_maxhz_apps_24),
                                contentDescription = appLabel,
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            endActions = {
                if (requiresRoot) {
                    Text(
                        text = "Root",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626),
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isDark) Color(0x2EF87171) else Color(0xFFFFECE8))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = granted,
                    onCheckedChange = { toggleGrant() }
                )
            }
        )
    }
}
