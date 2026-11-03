package com.kzvpn.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

class MainActivity : Activity() {
    private val controller: VpnController
        get() = (application as KzVpnApp).vpnController

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = EneidaApiClient()
    private val identityStore by lazy { DeviceIdentityStore(this) }

    private var subscriptionKnown = !api.enabled
    private var subscriptionActive = !api.enabled
    private var provisioning = false
    private var routeWatchJob: Job? = null

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
    private lateinit var powerButton: EneidaPowerButton
    private lateinit var serversButton: Button
    private lateinit var paymentButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EneidaTheme.prepareWindow(this)
        setContentView(buildUi())

        uiScope.launch {
            controller.state.collectLatest { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        powerButton.setEffectsActive(true)
        refreshManagedAccess()
        startRouteWatch()
    }

    override fun onPause() {
        powerButton.setEffectsActive(false)
        routeWatchJob?.cancel()
        routeWatchJob = null
        super.onPause()
    }

    override fun onDestroy() {
        powerButton.stopEffects()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        EneidaTheme.applyInsets(scroll)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon(R.drawable.ic_eneida_mark, 46), LinearLayout.LayoutParams(dp(46), dp(46)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brand.addView(label("Eneida", 25f, TEXT_PRIMARY, true, Gravity.START))
        brand.addView(label("Личный VPN", 11f, TEXT_MUTED, false, Gravity.START), lp(top = 2))
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label(when (BuildConfig.BUILD_TYPE) { "effects" -> "FX"; "preview" -> "PREVIEW"; else -> "VPN" }, 10f, PRIMARY, true).apply {
            letterSpacing = 0.12f
            minimumWidth = dp(48)
            minimumHeight = dp(40)
            if (BuildConfig.BUILD_TYPE == "effects") {
                contentDescription = "Показать визуальные эффекты кнопки"
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent().setClassName(this@MainActivity, "com.kzvpn.app.ButtonGalleryActivity"))
                }
            }
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = roundedDrawable(EneidaTheme.ACCENT_SURFACE, EneidaTheme.ACCENT_SURFACE, dp(20).toFloat())
        })
        root.addView(header, lp())

        status = label("VPN отключён", 25f, TEXT_PRIMARY, true)
        root.addView(status, lp(top = 28))
        statusHint = label("Выберите сервер и подключитесь", 13f, TEXT_MUTED)
        statusHint.maxLines = 2
        root.addView(statusHint, lp(top = 8))
        sessionText = label("Сеанс 00:00:00", 12f, PRIMARY).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedDrawable(EneidaTheme.ACCENT_SURFACE, EneidaTheme.ACCENT_SURFACE, dp(16).toFloat())
            visibility = View.INVISIBLE
        }
        root.addView(sessionText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        powerButton = EneidaPowerButton(this).apply {
            setOnClickListener { handlePowerTap() }
        }
        val buttonSize = (resources.configuration.screenWidthDp - 48).coerceIn(1, 304)
        root.addView(powerButton, LinearLayout.LayoutParams(dp(buttonSize), dp(buttonSize)).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })

        val serverCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(17), dp(14), dp(17))
            background = roundedDrawable(CARD, BORDER, dp(22).toFloat())
            isClickable = true
            isFocusable = true
            contentDescription = "Выбор VPN-сервера"
            setOnClickListener { if (api.enabled) openRegions() else openServers() }
        }
        serverCard.addView(icon(R.drawable.ic_server, 23).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = roundedDrawable(EneidaTheme.ACCENT_SURFACE, EneidaTheme.ACCENT_SURFACE, dp(14).toFloat())
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val serverInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(6), 0)
        }
        serverInfo.addView(label("СЕРВЕР", 10f, TEXT_SOFT, true, Gravity.START).apply { letterSpacing = 0.12f })
        serverValue = label("Выберите сервер", 17f, TEXT_PRIMARY, true, Gravity.START)
        serverInfo.addView(serverValue, lp(top = 5))
        serverHint = label("Нажмите для настройки", 11f, TEXT_MUTED, false, Gravity.START)
        serverInfo.addView(serverHint, lp(top = 5))
        serverCard.addView(serverInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        serverCard.addView(icon(R.drawable.ic_chevron, 18, TEXT_SOFT), LinearLayout.LayoutParams(dp(20), dp(20)))
        root.addView(serverCard, lp())

        val stats = LinearLayout(this)
        val receive = statCard("ПОЛУЧЕНО", R.drawable.ic_download, EneidaTheme.BLUE)
        rxValue = receive.second
        rxRateValue = receive.third
        stats.addView(receive.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
        val send = statCard("ОТПРАВЛЕНО", R.drawable.ic_upload, PRIMARY)
        txValue = send.second
        txRateValue = send.third
        stats.addView(send.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(stats, lp(top = 12))

        val shortcuts = LinearLayout(this)
        serversButton = secondaryButton("Серверы", R.drawable.ic_server) { openServers() }
        if (!api.enabled) {
            shortcuts.addView(serversButton, LinearLayout.LayoutParams(0, dp(54), 1f))
            shortcuts.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
        }
        val protection = secondaryButton("Защита", R.drawable.ic_shield) {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        protection.contentDescription = "Постоянная защита: настройки VPN Android"
        shortcuts.addView(protection, LinearLayout.LayoutParams(0, dp(54), 1f))
        root.addView(shortcuts, lp(top = 16))
        paymentButton = secondaryButton("Подписка и оплата", R.drawable.ic_card) { openPayment() }.apply {
            visibility = if (api.enabled) View.VISIBLE else View.GONE
        }
        root.addView(paymentButton, lp(top = if (api.enabled) 12 else 0))

        message = label("", 13f, ERROR).apply {
            background = roundedDrawable(ERROR_BG, ERROR_BORDER, dp(16).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.addView(message, lp(top = 14))
        root.addView(label("ENEIDA  ·  " + BuildConfig.VERSION_NAME, 10f, TEXT_SOFT).apply {
            letterSpacing = 0.1f
        }, lp(top = 22))
        return scroll
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, alignment: Int = Gravity.CENTER): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = alignment
            includeFontPadding = false
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    private fun icon(resource: Int, size: Int, color: Int = PRIMARY): ImageView =
        ImageView(this).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(color)
            minimumWidth = dp(size)
            minimumHeight = dp(size)
            contentDescription = null
        }

    private fun statCard(title: String, resource: Int, color: Int): Triple<LinearLayout, TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = roundedDrawable(CARD, BORDER, dp(20).toFloat())
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(icon(resource, 14, color), LinearLayout.LayoutParams(dp(14), dp(14)))
        heading.addView(label(title, 10f, TEXT_MUTED, true, Gravity.START).apply {
            letterSpacing = 0.06f
            setPadding(dp(7), 0, 0, 0)
        })
        card.addView(heading)
        val value = label("0 B", 23f, TEXT_PRIMARY, true, Gravity.START)
        card.addView(value, lp(top = 11))
        val rate = label("0 B/с", 11f, color, false, Gravity.START)
        card.addView(rate, lp(top = 5))
        return Triple(card, value, rate)
    }

    private fun secondaryButton(title: String, resource: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 13f
            setTextColor(TEXT_MUTED)
            isAllCaps = false
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            stateListAnimator = null
            backgroundTintList = null
            background = roundedDrawable(CARD, BORDER, dp(16).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(resource, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(PRIMARY)
            compoundDrawablePadding = dp(8)
            setOnClickListener { action() }
        }

    private fun handlePowerTap() {
        when (controller.state.value.connectionStatus) {
            VpnController.ConnectionStatus.CONNECTED -> controller.disconnect()
            VpnController.ConnectionStatus.DISCONNECTED -> {
                if (!api.enabled) {
                    requestVpn()
                    return
                }

                if (!subscriptionKnown) {
                    refreshManagedAccess()
                    return
                }

                if (!subscriptionActive) {
                    openPayment()
                    return
                }

                val selectedRegion = selectedRegionCode()
                val currentRegion = currentManagedRegionCode()
                if (!controller.state.value.configured || selectedRegion != currentRegion) {
                    provisionManaged(connectAfter = true)
                } else {
                    requestVpn()
                }
            }
            else -> Unit
        }
    }

    private fun refreshManagedAccess() {
        if (!api.enabled) {
            subscriptionKnown = true
            subscriptionActive = true
            render(controller.state.value)
            return
        }

        uiScope.launch {
            val identity = runCatching { identityStore.getOrCreate() }.getOrElse {
                controller.setMessage("Не удалось создать защищённый профиль устройства")
                return@launch
            }

            runCatching { api.subscription(identity.deviceId) }
                .onSuccess { subscription ->
                    subscriptionKnown = true
                    subscriptionActive = subscription.active
                    saveSubscriptionCache(subscription.active, subscription.validUntil)

                    paymentButton.text = if (subscription.active) {
                        val date = formatSubscriptionDate(subscription.validUntil)
                        if (date.isBlank()) "Подписка активна" else "Подписка до $date"
                    } else {
                        "Подписка и оплата"
                    }

                    if (subscription.active &&
                        !controller.state.value.configured &&
                        !provisioning
                    ) {
                        provisionManaged(connectAfter = false)
                    } else {
                        render(controller.state.value)
                    }
                }
                .onFailure {
                    subscriptionKnown = true
                    subscriptionActive = cachedSubscriptionActive()
                    paymentButton.text =
                        if (subscriptionActive) "Подписка активна" else "Подписка и оплата"
                    if (!subscriptionActive) {
                        controller.setMessage("Не удалось проверить подписку. Проверьте интернет.")
                    }
                    render(controller.state.value)
                }
        }
    }

    private fun provisionManaged(connectAfter: Boolean) {
        if (!api.enabled || provisioning) return
        if (!subscriptionActive) {
            openPayment()
            return
        }

        provisioning = true
        render(controller.state.value)

        uiScope.launch {
            val result = runCatching {
                val identity = identityStore.getOrCreate()
                val requestedRegion = selectedRegionCode()
                val provisioningData = api.provision(
                    identity = identity,
                    preferredRegion = requestedRegion
                )
                val config = api.makeWireGuardConfig(identity, provisioningData)
                val installed = controller.installManagedConfig(
                    bytes = config,
                    serverName = provisioningData.serverName
                )
                installed.getOrThrow()
                saveCurrentManagedRegion(provisioningData.regionCode)
                saveCurrentManagedServerId(provisioningData.serverId)
            }

            provisioning = false

            result.onSuccess {
                if (connectAfter) requestVpn()
            }.onFailure { error ->
                if (error is EneidaApiException && error.code == 402) {
                    subscriptionActive = false
                    openPayment()
                } else {
                    controller.setMessage(
                        error.message ?: "Не удалось автоматически настроить VPN"
                    )
                }
                render(controller.state.value)
            }
        }
    }

    private fun startRouteWatch() {
        routeWatchJob?.cancel()
        if (!api.enabled) return

        routeWatchJob = uiScope.launch {
            while (isActive) {
                delay(30_000)

                if (provisioning || !subscriptionActive) continue
                if (controller.state.value.connectionStatus != VpnController.ConnectionStatus.CONNECTED) continue

                val serverId = currentManagedServerId()
                if (serverId == null) continue

                val identity = runCatching { identityStore.getOrCreate() }.getOrNull() ?: continue
                val route = runCatching {
                    api.routeStatus(
                        identity = identity,
                        currentServerId = serverId,
                        preferredRegion = selectedRegionCode()
                    )
                }.getOrNull() ?: continue

                if (route.reprovision) {
                    controller.setMessage("Переключаемся на рабочий сервер…")
                    // Wait for the backend confirmation; a fixed delay can race the next connect.
                    if (controller.disconnectForReprovision()) {
                        provisionManaged(connectAfter = true)
                        delay(10_000)
                    } else {
                        controller.setMessage("Не удалось отключить VPN для смены сервера")
                    }
                }
            }
        }
    }

    private fun openServers() {
        startActivity(Intent(this, ServersActivity::class.java))
    }

    private fun openRegions() {
        startActivity(Intent(this, RegionActivity::class.java))
    }

    private fun selectedRegionCode(): String =
        getSharedPreferences(RegionActivity.PREFS, MODE_PRIVATE)
            .getString(RegionActivity.KEY_CODE, "auto")
            ?: "auto"

    private fun selectedRegionName(): String =
        getSharedPreferences(RegionActivity.PREFS, MODE_PRIVATE)
            .getString(RegionActivity.KEY_NAME, "Лучший сервер")
            ?: "Лучший сервер"

    private fun currentManagedRegionCode(): String =
        getSharedPreferences("eneida_managed", MODE_PRIVATE)
            .getString("current_region_code", "")
            ?: ""

    private fun currentManagedServerId(): Int? {
        val value = getSharedPreferences("eneida_managed", MODE_PRIVATE)
            .getInt("current_server_id", 0)
        return value.takeIf { it > 0 }
    }

    private fun saveCurrentManagedServerId(serverId: Int) {
        getSharedPreferences("eneida_managed", MODE_PRIVATE)
            .edit()
            .putInt("current_server_id", serverId)
            .apply()
    }

    private fun saveCurrentManagedRegion(code: String) {
        getSharedPreferences("eneida_managed", MODE_PRIVATE)
            .edit()
            .putString("current_region_code", code)
            .apply()
    }

    private fun openPayment() {
        startActivity(Intent(this, PaymentActivity::class.java))
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
        val visualState = when {
            provisioning -> EneidaPowerButton.Mode.CONNECTING
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTING -> EneidaPowerButton.Mode.CONNECTING
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTED -> EneidaPowerButton.Mode.CONNECTED
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTING -> EneidaPowerButton.Mode.DISCONNECTING
            else -> EneidaPowerButton.Mode.DISCONNECTED
        }

        val nextStatus = when {
            provisioning -> "Настройка VPN…"
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTED -> "VPN отключён"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTING -> "Подключение…"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTED -> "VPN подключён"
            else -> "Отключение…"
        }
        crossfadeText(status, nextStatus)
        status.setTextColor(if (connected) SUCCESS else TEXT_PRIMARY)

        val nextHint = when {
            connected -> "Туннель WireGuard активен"
            provisioning -> "Подбираем доступный сервер Eneida"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTING ->
                "Устанавливаем защищённое соединение…"
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTING ->
                "Завершаем защищённое соединение…"
            api.enabled && subscriptionKnown && !subscriptionActive ->
                "Для подключения нужна активная подписка"
            api.enabled && !subscriptionKnown ->
                "Проверяем подписку…"
            api.enabled && !state.configured ->
                "Eneida автоматически настроит VPN"
            !state.configured ->
                "Добавьте VPN-сервер для подключения"
            else -> "Нажмите кнопку, чтобы включить защиту"
        }
        crossfadeText(statusHint, nextHint)

        if (api.enabled) {
            val selectedName = selectedRegionName()
            val selectedCode = selectedRegionCode()
            serverValue.text = if (selectedCode == "auto") "⚡ Лучший сервер" else selectedName
            serverHint.text = if (state.configured) {
                "Сервер: ${state.activeServerName} • нажмите для смены региона"
            } else {
                "Нажмите, чтобы выбрать регион"
            }
        } else {
            serverValue.text = state.activeServerName
            serverHint.text = when {
                state.servers.size > 1 ->
                    "${state.servers.size} серверов • нажмите для быстрой смены"
                state.configured -> "WireGuard • нажмите для управления"
                else -> "Нажмите, чтобы добавить сервер"
            }
        }

        val buttonEnabled = when {
            provisioning -> false
            connected -> true
            !disconnected -> false
            api.enabled -> subscriptionKnown
            else -> state.configured
        }

        val buttonLabel = when {
            provisioning -> "Настройка…"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTED -> "Выключить"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTING -> "Подключение…"
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTING -> "Выключение…"
            api.enabled && subscriptionKnown && !subscriptionActive -> "Подписка"
            else -> "Включить"
        }
        powerButton.setVisualState(visualState, buttonLabel, buttonEnabled)
        if (!state.configured && !api.enabled) {
            powerButton.contentDescription = "Добавьте сервер для включения VPN"
        }

        rxValue.text = formatBytes(state.rxBytes)
        txValue.text = formatBytes(state.txBytes)
        rxRateValue.text = formatRate(state.rxRate)
        txRateValue.text = formatRate(state.txRate)

        updateSessionVisibility(connected)
        sessionText.text = "Сеанс " + formatDuration(state.sessionSeconds)

        val msg = state.message.orEmpty()
        message.text = msg
        message.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    private fun saveSubscriptionCache(active: Boolean, validUntil: String?) {
        getSharedPreferences("eneida_access", MODE_PRIVATE)
            .edit()
            .putBoolean("active", active)
            .putString("valid_until", validUntil)
            .apply()
    }

    private fun cachedSubscriptionActive(): Boolean {
        val prefs = getSharedPreferences("eneida_access", MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return false
        val validUntil = prefs.getString("valid_until", null) ?: return false
        return runCatching {
            Instant.parse(validUntil).isAfter(Instant.now())
        }.getOrDefault(false)
    }

    private fun formatSubscriptionDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val parts = raw.take(10).split("-")
        return if (parts.size == 3) {
            parts[2] + "." + parts[1] + "." + parts[0]
        } else ""
    }

    private fun crossfadeText(view: TextView, value: String) {
        if (view.text.toString() == value) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(90)
            .withEndAction {
                view.text = value
                view.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun updateSessionVisibility(visible: Boolean) {
        if (visible) {
            if (sessionText.visibility != View.VISIBLE) {
                sessionText.alpha = 0f
                sessionText.visibility = View.VISIBLE
                sessionText.animate().alpha(1f).setDuration(220).start()
            }
        } else if (sessionText.visibility == View.VISIBLE) {
            sessionText.animate().cancel()
            sessionText.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction {
                    sessionText.visibility = View.INVISIBLE
                    sessionText.alpha = 1f
                }
                .start()
        } else {
            sessionText.visibility = View.INVISIBLE
            sessionText.alpha = 1f
        }
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

        private val BG = EneidaTheme.BACKGROUND
        private val CARD = EneidaTheme.SURFACE
        private val PRIMARY = EneidaTheme.ACCENT
        private val BORDER = EneidaTheme.BORDER
        private val TEXT_PRIMARY = EneidaTheme.TEXT_PRIMARY
        private val TEXT_MUTED = EneidaTheme.TEXT_MUTED
        private val TEXT_SOFT = EneidaTheme.TEXT_SOFT
        private val SUCCESS = EneidaTheme.ACCENT
        private val ERROR = EneidaTheme.ERROR
        private val ERROR_BG = EneidaTheme.ERROR_SURFACE
        private val ERROR_BORDER = EneidaTheme.ERROR_BORDER
    }
}
