package com.kzvpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : Activity() {
    private val controller: VpnController
        get() = (application as KzVpnApp).vpnController

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var traffic: TextView
    private lateinit var message: TextView
    private lateinit var connectButton: Button
    private lateinit var importButton: Button
    private lateinit var settingsButton: Button
    private lateinit var forgetButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        uiScope.launch {
            controller.state.collectLatest { render(it) }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            setBackgroundColor(BG)
        }

        fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        root.addView(label("KZ VPN", 34f, TEXT_DARK, true), lp())
        root.addView(label("Личный WireGuard", 17f, TEXT_MUTED), lp(top = 4))
        root.addView(label("Версия 0.3.4 • GitHub build", 12f, TEXT_SOFT), lp(top = 4))

        status = label("Отключено", 26f, TEXT_MUTED, true)
        root.addView(status, lp(top = 28))

        endpoint = label("Конфигурация не загружена", 15f, TEXT_MUTED)
        root.addView(endpoint, lp(top = 8))

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(26)))

        connectButton = Button(this).apply {
            text = "ВКЛЮЧИТЬ\nVPN"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            isEnabled = false
            background = circleDrawable(PRIMARY, PRIMARY_BORDER)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(8).toFloat()
            setOnClickListener {
                when (controller.state.value.connectionStatus) {
                    VpnController.ConnectionStatus.DISCONNECTED -> requestVpn()
                    VpnController.ConnectionStatus.CONNECTED -> controller.disconnect()
                    else -> Unit
                }
            }
        }
        root.addView(
            connectButton,
            LinearLayout.LayoutParams(dp(220), dp(220)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        traffic = label("↓ 0 B      ↑ 0 B", 17f, TEXT_DARK, true)
        traffic.background = roundedDrawable(CARD, BORDER, dp(22).toFloat())
        traffic.setPadding(dp(18), dp(14), dp(18), dp(14))
        root.addView(traffic, lp(top = 24))

        importButton = secondaryButton("Импортировать WireGuard .conf") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                REQ_CONFIG
            )
        }
        root.addView(importButton, lp(top = 22))

        settingsButton = secondaryButton("Always-on / Kill Switch") {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        root.addView(settingsButton, lp(top = 10))

        forgetButton = secondaryButton("Удалить конфигурацию") {
            controller.forgetConfig()
        }.apply {
            visibility = View.GONE
        }
        root.addView(forgetButton, lp(top = 10))

        message = label("", 14f, ERROR, true).apply {
            background = roundedDrawable(ERROR_BG, ERROR_BORDER, dp(18).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.addView(message, lp(top = 18))

        return root
    }

    private fun secondaryButton(title: String, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 16f
            setTextColor(TEXT_DARK)
            isAllCaps = false
            stateListAnimator = null
            background = roundedDrawable(CARD, BORDER, dp(18).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { action() }
        }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            controller.connect()
        } else {
            startActivityForResult(intent, REQ_VPN)
        }
    }

    @Deprecated("Deprecated in Android API, kept intentionally for a minimal no-AndroidX UI build")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_VPN -> {
                if (resultCode == RESULT_OK) controller.connect()
                else controller.setMessage("Разрешение Android на создание VPN не выдано")
            }

            REQ_CONFIG -> {
                val uri = data?.data ?: return
                runCatching { contentResolver.openInputStream(uri) }
                    .getOrNull()
                    ?.let(controller::importConfig)
                    ?: controller.setMessage("Не удалось открыть выбранный файл")
            }
        }
    }

    private fun render(state: VpnController.UiState) {
        val connected = state.connectionStatus == VpnController.ConnectionStatus.CONNECTED

        status.text = when (state.connectionStatus) {
            VpnController.ConnectionStatus.DISCONNECTED -> "Отключено"
            VpnController.ConnectionStatus.CONNECTING -> "Подключение…"
            VpnController.ConnectionStatus.CONNECTED -> "Подключено • Казахстан"
            VpnController.ConnectionStatus.DISCONNECTING -> "Отключение…"
        }
        status.setTextColor(if (connected) SUCCESS else TEXT_MUTED)

        endpoint.text = state.endpoint.ifBlank {
            if (state.configured) "Сервер из конфигурации" else "Конфигурация не загружена"
        }

        connectButton.isEnabled =
            state.configured &&
                (state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED ||
                    state.connectionStatus == VpnController.ConnectionStatus.CONNECTED)

        connectButton.text = if (connected) "ВЫКЛЮЧИТЬ\nVPN" else "ВКЛЮЧИТЬ\nVPN"
        connectButton.background = when {
            !connectButton.isEnabled -> circleDrawable(DISABLED, DISABLED_BORDER)
            connected -> circleDrawable(PRIMARY_DARK, PRIMARY)
            else -> circleDrawable(PRIMARY, PRIMARY_BORDER)
        }

        importButton.isEnabled =
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED

        forgetButton.visibility =
            if (state.configured &&
                state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED)
                View.VISIBLE
            else View.GONE

        traffic.text = "↓ " + formatBytes(state.rxBytes) + "      ↑ " + formatBytes(state.txBytes)

        val msg = state.message.orEmpty()
        message.text = msg
        message.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    private fun circleDrawable(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(4), stroke)
        }

    private fun roundedDrawable(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_CONFIG = 1002

        private val BG = Color.rgb(235, 246, 255)
        private val CARD = Color.rgb(255, 255, 255)
        private val PRIMARY = Color.rgb(78, 161, 235)
        private val PRIMARY_DARK = Color.rgb(43, 118, 190)
        private val PRIMARY_BORDER = Color.rgb(173, 215, 248)
        private val BORDER = Color.rgb(190, 220, 244)
        private val TEXT_DARK = Color.rgb(31, 76, 115)
        private val TEXT_MUTED = Color.rgb(91, 128, 157)
        private val TEXT_SOFT = Color.rgb(129, 160, 184)
        private val SUCCESS = Color.rgb(39, 154, 105)
        private val ERROR = Color.rgb(181, 56, 56)
        private val ERROR_BG = Color.rgb(255, 240, 240)
        private val ERROR_BORDER = Color.rgb(245, 190, 190)
        private val DISABLED = Color.rgb(188, 211, 228)
        private val DISABLED_BORDER = Color.rgb(211, 228, 240)
    }
}
