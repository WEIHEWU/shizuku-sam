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
        val isDark = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsDark(context)
        val isBlack = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsBlackNight(context, isDark)

        // Align 100% with SamsungRootKit GlassDialog translucent glass fill + 1dp hairline border
        val bgColor = if (isDark) {
            if (isBlack) 0xE60D1117.toInt()
            else 0xCC161B22.toInt()
        } else {
            0xD9FFFDFB.toInt()
        }

        val strokeColor = if (isDark) {
            0x33FFFFFF.toInt()
        } else {
            0xE6FFFFFF.toInt()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 56
                    }
                }
            }
        }
        if (dialog is AlertDialog) {
            val isDark = moe.shizuku.manager.maxhz.GlassThemeState.resolveIsDark(context)
            val primaryColor = if (ThemeHelper.isUsingSystemColor() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context).primary.toArgb() else dynamicLightColorScheme(context).primary.toArgb()
            } else {
                if (isDark) 0xFF10B981.toInt() else 0xFF059669.toInt()
            }
            val onSurfaceVariant = if (ThemeHelper.isUsingSystemColor() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context).onSurfaceVariant.toArgb() else dynamicLightColorScheme(context).onSurfaceVariant.toArgb()
            } else {
                if (isDark) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
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
