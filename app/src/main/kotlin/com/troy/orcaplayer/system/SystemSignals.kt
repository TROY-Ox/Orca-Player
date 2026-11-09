package com.troy.orcaplayer.system

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings

/** System state the theme follows: Remove animations, contrast, TV and low-RAM devices. */
data class SystemSignals(
    val removeAnimations: Boolean = false,
    val highContrast: Boolean = false,
    val isTv: Boolean = false,
    val lowRam: Boolean = false,
    /** The system is in dark (night) mode; "Match the system" follows it. */
    val systemDark: Boolean = true,
) {
    companion object {
        fun read(context: Context): SystemSignals {
            val animatorScale = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val contrast = if (Build.VERSION.SDK_INT >= 34) uiMode?.contrast ?: 0f else 0f
            val isTv = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return SystemSignals(
                removeAnimations = animatorScale == 0f,
                highContrast = contrast >= 0.5f,
                isTv = isTv,
                lowRam = am?.isLowRamDevice == true,
                systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO,
            )
        }
    }
}
