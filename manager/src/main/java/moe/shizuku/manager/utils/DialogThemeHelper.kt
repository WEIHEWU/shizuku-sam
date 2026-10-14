package moe.shizuku.manager.utils

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.app.ThemeHelper

object DialogThemeHelper {

    fun createDialogBackground(context: Context): GradientDrawable {
        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isBlack = ThemeHelper.isBlackNightTheme(context) && isDark
        val useSystem = ThemeHelper.isUsingSystemColor()

        val bgColor = if (isDark) {
            if (isBlack) 0xFF000000.toInt()
            else if (useSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context).surfaceContainerHigh.toArgb()
            } else 0xFF181B22.toInt()
        } else {
            if (useSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context).surfaceContainerHigh.toArgb()
            } else 0xFFFFFFFF.toInt()
        }

        val strokeColor = if (isDark) {
            if (isBlack) 0x26FFFFFF.toInt()
            else if (useSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context).outlineVariant.toArgb()
            } else 0x224E5766.toInt()
        } else {
            if (useSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(context).outlineVariant.toArgb()
            } else 0x12000000.toInt()
        }

        val density = context.resources.displayMetrics.density
        val radiusPx = 24f * density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        return GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(bgColor)
            setStroke(strokePx, strokeColor)
        }
    }

    fun applyUnifiedDialogWindowStyle(dialog: Dialog, customWidth: Int? = null) {
        val context = dialog.context
        dialog.window?.let { window ->
            window.setBackgroundDrawable(createDialogBackground(context))
            val dm = context.resources.displayMetrics
            val maxW = (520 * dm.density).toInt()
            val w = customWidth ?: minOf((dm.widthPixels * 0.92f).toInt(), maxW)
            window.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setDimAmount(0.55f)
            window.setGravity(Gravity.CENTER)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (dialog is AlertDialog) {
            val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val primaryColor = if (ThemeHelper.isUsingSystemColor() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context).primary.toArgb() else dynamicLightColorScheme(context).primary.toArgb()
            } else {
                if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
            }
            val onSurfaceVariant = if (ThemeHelper.isUsingSystemColor() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context).onSurfaceVariant.toArgb() else dynamicLightColorScheme(context).onSurfaceVariant.toArgb()
            } else {
                if (isDark) 0xFF949BA8.toInt() else 0xFF5C6370.toInt()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(primaryColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(onSurfaceVariant)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(onSurfaceVariant)
        }
    }

    fun MaterialAlertDialogBuilder.showUnified(customWidth: Int? = null): AlertDialog {
        val dialog = this.create()
        dialog.show()
        applyUnifiedDialogWindowStyle(dialog, customWidth)
        return dialog
    }
}
