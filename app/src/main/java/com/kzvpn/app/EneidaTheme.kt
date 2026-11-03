package com.kzvpn.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.LinearLayout

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

    fun column(context: Context, maxWidthDp: Int = 560): LinearLayout =
        object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (maxWidthDp * resources.displayMetrics.density).toInt()
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), limit)
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }

    fun interactiveSurface(context: Context, fill: Int, border: Int, radiusDp: Int,
                           rippleColor: Int = ACCENT): Drawable {
        val density = context.resources.displayMetrics.density
        fun shape(color: Int, stroke: Int, width: Int = 1) = GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(color)
            setStroke((width * density).toInt(), stroke)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, ACCENT, 2))
            addState(intArrayOf(), shape(fill, border))
        }
        val ripple = Color.argb(36, Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor))
        return RippleDrawable(ColorStateList.valueOf(ripple), states, shape(Color.WHITE, Color.WHITE))
    }

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
