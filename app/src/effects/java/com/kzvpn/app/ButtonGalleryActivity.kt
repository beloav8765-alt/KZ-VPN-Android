package com.kzvpn.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Explicitly labelled visual demonstration, isolated to the effects build. */
class ButtonGalleryActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val buttons = mutableListOf<EneidaPowerButton>()
    private val headings = mutableListOf<TextView>()
    private val modes = arrayOf(EneidaPowerButton.Mode.DISCONNECTED, EneidaPowerButton.Mode.CONNECTED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArray("button_modes")?.forEachIndexed { index, value ->
            if (index in modes.indices) {
                modes[index] = runCatching { EneidaPowerButton.Mode.valueOf(value) }
                    .getOrDefault(EneidaPowerButton.Mode.DISCONNECTED)
            }
        }
        EneidaTheme.prepareWindow(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(EneidaTheme.BACKGROUND)
        }
        EneidaTheme.applyInsets(scroll)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label("Eneida · Демонстрация", 18f, EneidaTheme.TEXT_PRIMARY))
        root.addView(label("Визуальные состояния кнопки", 12f, EneidaTheme.TEXT_MUTED),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val config = resources.configuration
        val wide = config.screenWidthDp > config.screenHeightDp
        val row = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val side = if (wide) (config.screenHeightDp - 140).coerceIn(180, 360)
            else (config.screenWidthDp - 48).coerceIn(1, 288)
        for (index in 0..1) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val heading = label("", 20f, EneidaTheme.TEXT_PRIMARY)
            headings.add(heading)
            cell.addView(heading)
            val button = EneidaPowerButton(this).apply {
                id = if (index == 0) R.id.demo_power_off else R.id.demo_power_on
                setOnClickListener { demonstrate(index) }
            }
            buttons.add(button)
            cell.addView(button, LinearLayout.LayoutParams(dp(side), dp(side)))
            row.addView(cell, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            render(index)
        }
        setContentView(scroll)
    }

    private fun demonstrate(index: Int) {
        val wasConnected = modes[index] == EneidaPowerButton.Mode.CONNECTED
        modes[index] = if (wasConnected) EneidaPowerButton.Mode.DISCONNECTING else EneidaPowerButton.Mode.CONNECTING
        render(index)
        handler.postDelayed({
            modes[index] = if (wasConnected) EneidaPowerButton.Mode.DISCONNECTED else EneidaPowerButton.Mode.CONNECTED
            render(index)
        }, if (wasConnected) 650L else 850L)
    }

    private fun render(index: Int) {
        val mode = modes[index]
        val label = when (mode) {
            EneidaPowerButton.Mode.DISCONNECTED -> "Включить"
            EneidaPowerButton.Mode.CONNECTING -> "Подключение…"
            EneidaPowerButton.Mode.CONNECTED -> "Выключить"
            EneidaPowerButton.Mode.DISCONNECTING -> "Выключение…"
        }
        headings[index].text = when (mode) {
            EneidaPowerButton.Mode.DISCONNECTED -> "VPN отключён"
            EneidaPowerButton.Mode.CONNECTING -> "Подключение…"
            EneidaPowerButton.Mode.CONNECTED -> "VPN подключён"
            EneidaPowerButton.Mode.DISCONNECTING -> "Выключение…"
        }
        headings[index].setTextColor(if (mode == EneidaPowerButton.Mode.CONNECTED) EneidaTheme.ACCENT else EneidaTheme.TEXT_PRIMARY)
        buttons[index].setVisualState(mode, label, mode == EneidaPowerButton.Mode.DISCONNECTED || mode == EneidaPowerButton.Mode.CONNECTED)
        buttons[index].contentDescription = "Демонстрация: $label"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArray("button_modes", modes.map {
            when (it) {
                EneidaPowerButton.Mode.CONNECTING -> EneidaPowerButton.Mode.CONNECTED
                EneidaPowerButton.Mode.DISCONNECTING -> EneidaPowerButton.Mode.DISCONNECTED
                else -> it
            }.name
        }.toTypedArray())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        buttons.forEach { it.setEffectsActive(true) }
    }

    override fun onPause() {
        buttons.forEach { it.setEffectsActive(false) }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        buttons.forEach { it.stopEffects() }
        super.onDestroy()
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
