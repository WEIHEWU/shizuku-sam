package moe.shizuku.manager.shell

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.home.GroupHeaderWithToggle
import moe.shizuku.manager.home.MiuixRowIcon
import moe.shizuku.manager.home.MiuixSubPageTopBar
import moe.shizuku.manager.home.onLongPressToggle
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.maxhz.GlassAppTheme
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.html.text.HtmlCompat
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

class ShellTutorialActivity : AppActivity() {

    companion object {
        private const val SH_NAME = "rish"
        private const val DEX_NAME = "rish_shizuku.dex"
    }

    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult

            val cr = contentResolver
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val child =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

            cr.query(
                child,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name == SH_NAME || name == DEX_NAME) {
                        DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }

            fun writeToDocument(name: String) {
                DocumentsContract.createDocument(contentResolver, doc, "application/octet-stream", name)?.runCatching {
                    cr.openOutputStream(this)?.let { assets.open(name).copyTo(it) }
                }
            }

            writeToDocument(SH_NAME)
            writeToDocument(DEX_NAME)
            Toast.makeText(this, "已导出 $SH_NAME 与 $DEX_NAME", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isBlackNight = isDark && ThemeHelper.isBlackNightTheme(this)
        val primaryColorInt = runCatching {
            val tv = TypedValue()
            if (theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) tv.data
            else if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
        }.getOrDefault(if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt())

        setContent {
            GlassAppTheme(isDark = isDark) {
                ShellTutorialMiuixScreen(
                    isDark = isDark,
                    isBlackNightTheme = isBlackNight,
                    primaryColor = Color(primaryColorInt),
                    onBackClick = { finish() },
                    onExportFilesClick = { openDocumentsTree.launch(null) }
                )
            }
        }
    }
}

@Composable
private fun ShellTutorialMiuixScreen(
    isDark: Boolean,
    isBlackNightTheme: Boolean,
    primaryColor: Color,
    onBackClick: () -> Unit,
    onExportFilesClick: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var groupExpanded by remember { mutableStateOf(false) }

    val bgColor = if (isBlackNightTheme) {
        Color.Black
    } else {
        MiuixTheme.colorScheme.surface
    }

    val rishDescText = remember {
        context.getString(R.string.rish_description, "rish")
            .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE).toString()
    }
    val step1Title = remember {
        context.getString(R.string.terminal_tutorial_1, "rish", "rish_shizuku.dex").toHtml().toString()
    }
    val step1Summary = stringResource(R.string.terminal_tutorial_1_description)
    val step2Title = remember {
        context.getString(R.string.terminal_tutorial_2, "rish").toHtml().toString()
    }
    val step2Summary = remember {
        context.getString(R.string.terminal_tutorial_2_description, "Termux", "PKG", "com.termux", "com.termux")
            .toHtml().toString()
    }
    val step3Title = remember {
        context.getString(R.string.terminal_tutorial_3, "sh rish").toHtml().toString()
    }
    val step3Summary = remember {
        context.getString(R.string.terminal_tutorial_3_description, "rish", "PATH").toHtml().toString()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = bgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = 54.dp,
                start = 14.dp,
                end = 14.dp,
                bottom = 48.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header-top") {
                MiuixSubPageTopBar(
                    title = stringResource(R.string.home_terminal_title),
                    subtitle = "在终端应用（如 Termux）中通过 rish 直接运行 Shizuku 高权限 Shell",
                    isDark = isDark,
                    primaryColor = primaryColor,
                    expanded = groupExpanded,
                    onToggleExpand = { groupExpanded = !groupExpanded },
                    onBackClick = onBackClick
                )
            }

            // rish 简介与文档卡
            item(key = "card-overview") {
                var cardToggled by remember(groupExpanded) { mutableStateOf(false) }
                val showSub = groupExpanded xor cardToggled
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onLongPressToggle(haptic) { cardToggled = !cardToggled },
                    cornerRadius = 20.dp,
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixRowIcon(
                                isDark = isDark,
                                icon = painterResource(R.drawable.ic_terminal_24),
                                iconTint = primaryColor
                            )
                            Text(
                                text = "在终端模拟器中使用 Shizuku (rish)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        AnimatedVisibility(
                            visible = showSub,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Text(
                                text = rishDescText,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable { cardToggled = !cardToggled }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            text = "阅读 rish 官方说明文档",
                            onClick = { CustomTabsHelper.launchUrlOrCopy(context, Helps.RISH.get()) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 步骤引导组
            item(key = "section-steps") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupHeaderWithToggle(
                        text = "配置与启动步骤",
                        isDark = isDark,
                        primaryColor = primaryColor,
                        expanded = groupExpanded,
                        onToggle = { groupExpanded = !groupExpanded }
                    )

                    // 步骤 1
                    var step1Toggled by remember(groupExpanded) { mutableStateOf(false) }
                    val showStep1Sub = groupExpanded xor step1Toggled
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { step1Toggled = !step1Toggled },
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_numeric_1_circle_outline_24),
                                    iconTint = primaryColor
                                )
                                Text(
                                    text = step1Title,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            AnimatedVisibility(
                                visible = showStep1Sub,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Text(
                                    text = step1Summary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clickable { step1Toggled = !step1Toggled }
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            TextButton(
                                text = stringResource(R.string.terminal_export_files),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = onExportFilesClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 步骤 2
                    var step2Toggled by remember(groupExpanded) { mutableStateOf(false) }
                    val showStep2Sub = groupExpanded xor step2Toggled
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { step2Toggled = !step2Toggled },
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_numeric_2_circle_outline_24),
                                    iconTint = primaryColor
                                )
                                Text(
                                    text = step2Title,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            AnimatedVisibility(
                                visible = showStep2Sub,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Text(
                                    text = step2Summary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clickable { step2Toggled = !step2Toggled }
                                )
                            }
                        }
                    }

                    // 步骤 3
                    var step3Toggled by remember(groupExpanded) { mutableStateOf(false) }
                    val showStep3Sub = groupExpanded xor step3Toggled
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onLongPressToggle(haptic) { step3Toggled = !step3Toggled },
                        cornerRadius = 20.dp,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixRowIcon(
                                    isDark = isDark,
                                    icon = painterResource(R.drawable.ic_numeric_3_circle_outline_24),
                                    iconTint = Color(0xFF10B981)
                                )
                                Text(
                                    text = step3Title,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            AnimatedVisibility(
                                visible = showStep3Sub,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Text(
                                    text = step3Summary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .clickable { step3Toggled = !step3Toggled }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
