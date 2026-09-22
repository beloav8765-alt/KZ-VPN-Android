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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var statusHint: TextView
    private lateinit var sessionText: TextView
    private lateinit var serverValue: TextView
    private lateinit var serverHint: TextView
    private lateinit var rxValue: TextView
    private lateinit var txValue: TextView
    private lateinit var rxRateValue: TextView
    private lateinit var txRateValue: TextView
    private lateinit var message: TextView
    private lateinit var connectButton: Button
    private lateinit var powerRing: FrameLayout
    private lateinit var serversButton: Button

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

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                includeFontPadding = false
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        root.addView(label("Eneida", 31f, TEXT_DARK, true), lp())
        root.addView(label("Личный VPN", 14f, TEXT_MUTED), lp(top = 5))

        status = label("VPN отключён", 23f, TEXT_DARK, true)
        root.addView(status, lp(top = 24))

        statusHint = label("Подключитесь, чтобы защитить интернет-соединение", 13f, TEXT_MUTED)
        statusHint.setPadding(dp(10), 0, dp(10), 0)
        root.addView(statusHint, lp(top = 8))

        sessionText = label("Сеанс 00:00:00", 12f, TEXT_SOFT, true).apply {
            visibility = View.GONE
        }
        root.addView(sessionText, lp(top = 8))

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(22)))

        powerRing = FrameLayout(this).apply {
            background = circleDrawable(RING_IDLE, RING_IDLE)
            elevation = dp(5).toFloat()
        }

        connectButton = Button(this).apply {
            text = "ПОДКЛЮЧИТЬ"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_power, 0, 0)
            compoundDrawablePadding = dp(10)
            stateListAnimator = null
            isEnabled = false
            background = circleDrawable(PRIMARY, PRIMARY)
            setPadding(dp(12), dp(18), dp(12), dp(18))
            elevation = dp(8).toFloat()
            setOnClickListener {
                when (controller.state.value.connectionStatus) {
                    VpnController.ConnectionStatus.DISCONNECTED -> requestVpn()
                    VpnController.ConnectionStatus.CONNECTED -> controller.disconnect()
                    else -> Unit
                }
            }
        }

        powerRing.addView(
            connectButton,
            FrameLayout.LayoutParams(dp(174), dp(174), Gravity.CENTER)
        )
        root.addView(
            powerRing,
            LinearLayout.LayoutParams(dp(210), dp(210)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(24)))

        val serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = roundedDrawable(CARD, BORDER, dp(18).toFloat())
            elevation = dp(2).toFloat()
            setOnClickListener { openServers() }
        }
        serverCard.addView(label("СЕРВЕР", 11f, TEXT_SOFT, true).apply { gravity = Gravity.START })
        serverValue = label("Профиль не выбран", 17f, TEXT_DARK, true).apply { gravity = Gravity.START }
        serverCard.addView(serverValue, lp(top = 6))
        serverHint = label("Нажмите, чтобы выбрать сервер", 12f, TEXT_MUTED).apply { gravity = Gravity.START }
        serverCard.addView(serverHint, lp(top = 5))
        root.addView(serverCard, lp())

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val rxCard = statCard("ПОЛУЧЕНО")
        rxValue = rxCard.second
        rxRateValue = rxCard.third
        statsRow.addView(rxCard.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        statsRow.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))

        val txCard = statCard("ОТПРАВЛЕНО")
        txValue = txCard.second
        txRateValue = txCard.third
        statsRow.addView(txCard.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(statsRow, lp(top = 12))

        serversButton = secondaryButton("Серверы") { openServers() }
        root.addView(serversButton, lp(top = 18))

        val paymentButton = secondaryButton("Подписка и оплата") {
            startActivity(Intent(this, PaymentActivity::class.java))
        }
        root.addView(paymentButton, lp(top = 10))

        val settingsButton = secondaryButton("Постоянная защита") {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        root.addView(settingsButton, lp(top = 10))

        message = label("", 13f, ERROR, true).apply {
            background = roundedDrawable(ERROR_BG, ERROR_BORDER, dp(16).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.addView(message, lp(top = 14))

        root.addView(label("Eneida 0.7.0", 11f, TEXT_SOFT), lp(top = 22))
        return scroll
    }

    private fun statCard(title: String): Triple<LinearLayout, TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = roundedDrawable(CARD, BORDER, dp(16).toFloat())
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 10f
            setTextColor(TEXT_SOFT)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val valueView = TextView(this).apply {
            text = "0 B"
            textSize = 17f
            setTextColor(TEXT_DARK)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val rateView = TextView(this).apply {
            text = "0 B/с"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        card.addView(titleView)
        card.addView(valueView, lp(top = 6))
        card.addView(rateView, lp(top = 4))
        return Triple(card, valueView, rateView)
    }

    private fun secondaryButton(title: String, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 15f
            setTextColor(TEXT_DARK)
            isAllCaps = false
            stateListAnimator = null
            background = roundedDrawable(CARD, BORDER, dp(16).toFloat())
            setPadding(dp(16), dp(11), dp(16), dp(11))
            setOnClickListener { action() }
        }

    private fun openServers() {
        startActivity(Intent(this, ServersActivity::class.java))
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
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) controller.connect()
            else controller.setMessage("Разрешение Android на создание VPN не выдано")
        }
    }

    private fun render(state: VpnController.UiState) {
        val connected = state.connectionStatus == VpnController.ConnectionStatus.CONNECTED
        val disconnected = state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED

        status.text = when (state.connectionStatus) {
            VpnController.ConnectionStatus.DISCONNECTED -> "VPN отключён"
            VpnController.ConnectionStatus.CONNECTING -> "Подключение…"
            VpnController.ConnectionStatus.CONNECTED -> "Соединение защищено"
            VpnController.ConnectionStatus.DISCONNECTING -> "Отключение…"
        }
        status.setTextColor(if (connected) SUCCESS else TEXT_DARK)

        statusHint.text = when {
            connected -> "Интернет-соединение защищено"
            !state.configured -> "Добавьте VPN-сервер для подключения"
            else -> "Нажмите кнопку, чтобы включить защиту"
        }

        serverValue.text = state.activeServerName
        serverHint.text = when {
            state.servers.size > 1 -> "\${state.servers.size} серверов • нажмите для быстрой смены"
            state.configured -> "WireGuard • нажмите для управления"
            else -> "Нажмите, чтобы добавить сервер"
        }

        connectButton.isEnabled = state.configured && (disconnected || connected)

        connectButton.text = when (state.connectionStatus) {
            VpnController.ConnectionStatus.CONNECTED -> "ОТКЛЮЧИТЬ"
            VpnController.ConnectionStatus.CONNECTING -> "ПОДКЛЮЧЕНИЕ…"
            VpnController.ConnectionStatus.DISCONNECTING -> "ОТКЛЮЧЕНИЕ…"
            VpnController.ConnectionStatus.DISCONNECTED -> "ПОДКЛЮЧИТЬ"
        }

        connectButton.background = when {
            !connectButton.isEnabled -> circleDrawable(DISABLED, DISABLED)
            connected -> circleDrawable(PRIMARY_DARK, PRIMARY_DARK)
            else -> circleDrawable(PRIMARY, PRIMARY)
        }

        powerRing.background = when {
            connected -> circleDrawable(RING_CONNECTED, RING_CONNECTED)
            !state.configured -> circleDrawable(RING_DISABLED, RING_DISABLED)
            else -> circleDrawable(RING_IDLE, RING_IDLE)
        }

        rxValue.text = formatBytes(state.rxBytes)
        txValue.text = formatBytes(state.txBytes)
        rxRateValue.text = formatRate(state.rxRate)
        txRateValue.text = formatRate(state.txRate)

        sessionText.visibility = if (connected) View.VISIBLE else View.GONE
        sessionText.text = "Сеанс " + formatDuration(state.sessionSeconds)

        val msg = state.message.orEmpty()
        message.text = msg
        message.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    private fun circleDrawable(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(1), stroke)
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

    private fun formatRate(bytesPerSecond: Long): String =
        formatBytes(bytesPerSecond) + "/с"

    private fun formatDuration(secondsTotal: Long): String {
        val hours = secondsTotal / 3600
        val minutes = (secondsTotal % 3600) / 60
        val seconds = secondsTotal % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

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

        private val BG = Color.rgb(239, 243, 246)
        private val CARD = Color.rgb(250, 252, 253)
        private val PRIMARY = Color.rgb(46, 98, 138)
        private val PRIMARY_DARK = Color.rgb(35, 78, 112)
        private val BORDER = Color.rgb(199, 211, 219)
        private val TEXT_DARK = Color.rgb(48, 73, 91)
        private val TEXT_MUTED = Color.rgb(105, 124, 138)
        private val TEXT_SOFT = Color.rgb(148, 162, 172)
        private val SUCCESS = Color.rgb(45, 125, 104)
        private val RING_IDLE = Color.rgb(213, 228, 239)
        private val RING_CONNECTED = Color.rgb(205, 232, 222)
        private val RING_DISABLED = Color.rgb(226, 232, 236)
        private val ERROR = Color.rgb(181, 56, 56)
        private val ERROR_BG = Color.rgb(255, 240, 240)
        private val ERROR_BORDER = Color.rgb(245, 190, 190)
        private val DISABLED = Color.rgb(192, 205, 214)
    }
}
