package com.kzvpn.app

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView

/** Shared palette and system-bar spacing for the native Android screens. */
object EneidaTheme {
    val BACKGROUND = Color.rgb(12, 17, 24)
    val SURFACE = Color.rgb(21, 29, 40)
    val SURFACE_RAISED = Color.rgb(28, 39, 52)
    val ACCENT = Color.rgb(120, 228, 203)
    val ACCENT_SURFACE = Color.rgb(26, 57, 53)
    val ON_ACCENT = Color.rgb(10, 36, 32)
    val BORDER = Color.rgb(43, 55, 70)
    val TEXT_PRIMARY = Color.rgb(244, 248, 251)
    val TEXT_MUTED = Color.rgb(162, 178, 197)
    val TEXT_SOFT = Color.rgb(125, 144, 166)
    val BLUE = Color.rgb(145, 212, 255)
    val ERROR = Color.rgb(255, 173, 168)
    val ERROR_SURFACE = Color.rgb(51, 33, 38)
    val ERROR_BORDER = Color.rgb(108, 62, 69)

    @Suppress("DEPRECATION")
    fun prepareWindow(activity: Activity) {
        val window = activity.window
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    }

    fun applyInsets(scroll: ScrollView) {
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }
}
