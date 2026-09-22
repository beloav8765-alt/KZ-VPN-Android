package com.kzvpn.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
            setPadding(dp(22), dp(26), dp(22), dp(22))
            setBackgroundColor(Color.rgb(10, 13, 18))
        }

        fun makeText(value: String, size: Float, color: Int = Color.WHITE): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
            }

        root.addView(makeText("KZ VPN", 30f))
        root.addView(makeText("Личный WireGuard", 15f, Color.rgb(184, 194, 204)))

        status = makeText("Отключено", 20f, Color.rgb(184, 194, 204))
        root.addView(status, lp())

        endpoint = makeText("Конфигурация не загружена", 15f, Color.rgb(184, 194, 204))
        root.addView(endpoint, lp())

        connectButton = Button(this).apply {
            text = "ПОДКЛЮЧИТЬ VPN"
            isEnabled = false
            setOnClickListener {
                when (controller.state.value.connectionStatus) {
                    VpnController.ConnectionStatus.DISCONNECTED -> requestVpn()
                    VpnController.ConnectionStatus.CONNECTED -> controller.disconnect()
                    else -> Unit
                }
            }
        }
        root.addView(connectButton, lp(top = 20))

        traffic = makeText("↓ 0 B     ↑ 0 B", 16f, Color.rgb(184, 194, 204))
        root.addView(traffic, lp(top = 8))

        importButton = Button(this).apply {
            text = "Импортировать WireGuard .conf"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQ_CONFIG
                )
            }
        }
        root.addView(importButton, lp(top = 18))

        val vpnSettings = Button(this).apply {
            text = "Always-on / Kill Switch"
            setOnClickListener { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        }
        root.addView(vpnSettings, lp(top = 8))

        forgetButton = Button(this).apply {
            text = "Удалить конфигурацию"
            visibility = Button.GONE
            setOnClickListener { controller.forgetConfig() }
        }
        root.addView(forgetButton, lp(top = 8))

        message = makeText("", 14f, Color.rgb(255, 114, 114))
        root.addView(message, lp(top = 12))

        return root
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
        status.text = when (state.connectionStatus) {
            VpnController.ConnectionStatus.DISCONNECTED -> "Отключено"
            VpnController.ConnectionStatus.CONNECTING -> "Подключение…"
            VpnController.ConnectionStatus.CONNECTED -> "Подключено • Казахстан"
            VpnController.ConnectionStatus.DISCONNECTING -> "Отключение…"
        }

        status.setTextColor(
            if (state.connectionStatus == VpnController.ConnectionStatus.CONNECTED)
                Color.rgb(84, 214, 140)
            else Color.rgb(184, 194, 204)
        )

        endpoint.text = state.endpoint.ifBlank {
            if (state.configured) "Сервер из конфигурации" else "Конфигурация не загружена"
        }

        connectButton.isEnabled =
            state.configured &&
                (state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED ||
                    state.connectionStatus == VpnController.ConnectionStatus.CONNECTED)

        connectButton.text =
            if (state.connectionStatus == VpnController.ConnectionStatus.CONNECTED)
                "ОТКЛЮЧИТЬ VPN"
            else "ПОДКЛЮЧИТЬ VPN"

        importButton.isEnabled =
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED

        forgetButton.visibility =
            if (state.configured &&
                state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED)
                Button.VISIBLE
            else Button.GONE

        traffic.text = "↓ ${formatBytes(state.rxBytes)}     ↑ ${formatBytes(state.txBytes)}"
        message.text = state.message.orEmpty()
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
        if (bytes < 1024) return "$bytes B"
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
    }
}
