package com.kzvpn.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var serverCard: LinearLayout
    private var stackedCards = false
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
        listOf(status, statusHint).forEach { it.animate().cancel(); it.alpha = 1f }
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
        val config = resources.configuration
        val wide = config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp
        val sidePadding = if (config.screenWidthDp < 360) 18 else 24
        stackedCards = config.fontScale >= 1.5f || config.screenWidthDp < 340
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        EneidaTheme.applyInsets(scroll)
        val frame = FrameLayout(this)
        scroll.addView(frame, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val root = EneidaTheme.column(this, if (wide) 960 else 560).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(sidePadding), dp(16), dp(sidePadding), dp(20))
        }
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon(R.drawable.ic_eneida_mark, 42), LinearLayout.LayoutParams(dp(42), dp(42)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        brand.addView(label("Eneida", 25f, TEXT_PRIMARY, true, Gravity.START).apply {
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(18, 25, 1, TypedValue.COMPLEX_UNIT_SP)
        }, lp())
        brand.addView(label("Личный VPN", 11f, TEXT_MUTED, false, Gravity.START), lp(top = 2))
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label(when (BuildConfig.BUILD_TYPE) { "effects" -> "FX"; "preview" -> "PREVIEW"; else -> "VPN" }, 10f, PRIMARY, true).apply {
            id = R.id.main_fx
            letterSpacing = 0.1f
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            if (BuildConfig.BUILD_TYPE == "effects") {
                contentDescription = "Посмотреть эффекты кнопки"
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent().setClassName(this@MainActivity, "com.kzvpn.app.ButtonGalleryActivity"))
                }
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = EneidaTheme.interactiveSurface(this@MainActivity,
                EneidaTheme.ACCENT_SURFACE, EneidaTheme.ACCENT_SURFACE, 24)
        })
        root.addView(header, lp())
        val body = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        root.addView(body, lp(top = if (wide) 16 else 20))
        val hero = LinearLayout(this).apply {
            id = R.id.main_hero
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val details = LinearLayout(this).apply {
            id = R.id.main_details
            orientation = LinearLayout.VERTICAL
        }
        body.addView(hero, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(details, if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(24)
        } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = label("VPN отключён", if (wide) 22f else 24f, TEXT_PRIMARY, true).apply {
            id = R.id.main_status
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hero.addView(status, lp())
        statusHint = label("Добавьте профиль WireGuard", 12f, TEXT_MUTED).apply { maxLines = 3 }
        hero.addView(statusHint, lp(top = 7))
        sessionText = label("Профиль .conf", 11f, PRIMARY).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedDrawable(EneidaTheme.ACCENT_SURFACE, EneidaTheme.ACCENT_SURFACE, dp(16).toFloat())
        }
        hero.addView(sessionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        powerButton = EneidaPowerButton(this).apply {
            id = R.id.main_power
            setOnClickListener { handlePowerTap() }
        }
        val heroWidth = if (wide) (minOf(config.screenWidthDp, 960) - sidePadding * 2 - 24) / 2
            else minOf(config.screenWidthDp, 560) - sidePadding * 2
        val desiredSize = when {
            wide -> (config.screenHeightDp - 210).coerceIn(160, 256)
            config.fontScale >= 1.5f || config.screenHeightDp < 700 -> 216
            config.screenHeightDp < 800 -> 256
            else -> 288
        }
        val buttonSize = minOf(heroWidth, desiredSize).coerceAtLeast(100)
        hero.addView(powerButton, LinearLayout.LayoutParams(dp(buttonSize), dp(buttonSize)).apply {
            topMargin = dp(2)
            bottomMargin = dp(if (wide) 0 else 6)
        })

        serverCard = LinearLayout(this).apply {
            id = R.id.main_server
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = EneidaTheme.interactiveSurface(this@MainActivity, CARD, BORDER, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (api.enabled) openRegions() else openServers() }
        }
        serverCard.addView(icon(R.drawable.ic_server, 22).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedDrawable(EneidaTheme.ACCENT_SURFACE, EneidaTheme.ACCENT_SURFACE, dp(14).toFloat())
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val serverInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
        }
        serverInfo.addView(label("СЕРВЕР", 10f, TEXT_SOFT, true, Gravity.START).apply { letterSpacing = 0.1f })
        serverValue = label("Добавьте сервер", 17f, TEXT_PRIMARY, true, Gravity.START).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        serverInfo.addView(serverValue, lp(top = 4))
        serverHint = label("Импорт файла .conf", 11f, TEXT_MUTED, false, Gravity.START)
        serverInfo.addView(serverHint, lp(top = 4))
        serverCard.addView(serverInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        serverCard.addView(icon(R.drawable.ic_chevron, 18, TEXT_SOFT), LinearLayout.LayoutParams(dp(20), dp(20)))
        details.addView(serverCard, lp())

        val stats = LinearLayout(this).apply {
            orientation = if (stackedCards) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val receive = statCard("ПОЛУЧЕНО", R.drawable.ic_download, EneidaTheme.BLUE)
        rxValue = receive.second
        rxRateValue = receive.third
        val send = statCard("ОТПРАВЛЕНО", R.drawable.ic_upload, PRIMARY)
        txValue = send.second
        txRateValue = send.third
        if (stackedCards) {
            stats.addView(receive.first, lp())
            stats.addView(send.first, lp(top = 10))
        } else {
            stats.addView(receive.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            stats.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
            stats.addView(send.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        details.addView(stats, lp(top = 12))
        val shortcuts = LinearLayout(this).apply {
            orientation = if (stackedCards) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        fun shortcutParams() = if (stackedCards) lp() else
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        serversButton = secondaryButton("Серверы", R.drawable.ic_server) { openServers() }
        if (!api.enabled) {
            shortcuts.addView(serversButton, shortcutParams())
            shortcuts.addView(Space(this), if (stackedCards) LinearLayout.LayoutParams(1, dp(10))
                else LinearLayout.LayoutParams(dp(12), 1))
        }
        val protection = secondaryButton("Защита", R.drawable.ic_shield) {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
        protection.contentDescription = "Постоянная защита: настройки VPN Android"
        shortcuts.addView(protection, shortcutParams())
        details.addView(shortcuts, lp(top = 12))
        paymentButton = secondaryButton("Подписка и оплата", R.drawable.ic_card) { openPayment() }.apply {
            visibility = if (api.enabled) View.VISIBLE else View.GONE
        }
        details.addView(paymentButton, lp(top = if (api.enabled) 12 else 0))
        message = label("", 13f, ERROR).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            background = roundedDrawable(ERROR_BG, ERROR_BORDER, dp(16).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        details.addView(message, lp(top = 12))
        details.addView(label("ENEIDA  ·  " + BuildConfig.VERSION_NAME, 10f, TEXT_SOFT).apply {
            id = R.id.main_footer
            letterSpacing = 0.08f
        }, lp(top = 16))
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(CARD, BORDER, dp(20).toFloat())
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(icon(resource, 14, color), LinearLayout.LayoutParams(dp(14), dp(14)))
        heading.addView(label(title, 10f, TEXT_MUTED, true, Gravity.START).apply {
            letterSpacing = 0.06f
            setPadding(dp(7), 0, 0, 0)
        })
        card.addView(heading)
        val value = label("0 Б", 23f, TEXT_PRIMARY, true, Gravity.START).apply {
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(18, 23, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        card.addView(value, lp(top = 9))
        val rate = label("0 Б/с", 11f, color, false, Gravity.START)
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
            background = EneidaTheme.interactiveSurface(this@MainActivity, CARD, BORDER, 16)
            minimumHeight = dp(52)
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
                    if (!controller.state.value.configured) {
                        openServers()
                        return
                    }
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
                "Добавьте профиль WireGuard, чтобы начать"
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
            serverValue.text = if (state.configured) state.activeServerName else "Добавьте сервер"
            serverHint.text = when {
                state.servers.size > 1 ->
                    "${state.servers.size} серверов • нажмите для быстрой смены"
                state.configured -> "WireGuard • нажмите для управления"
                else -> "Импорт файла .conf"
            }
        }

        val buttonEnabled = when {
            provisioning -> false
            connected -> true
            !disconnected -> false
            api.enabled -> subscriptionKnown
            else -> true
        }

        val buttonLabel = when {
            provisioning -> "Настройка…"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTED -> "Выключить"
            state.connectionStatus == VpnController.ConnectionStatus.CONNECTING -> "Подключение…"
            state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTING -> "Выключение…"
            api.enabled && subscriptionKnown && !subscriptionActive -> "Подписка"
            !api.enabled && !state.configured -> "Настроить"
            else -> "Включить"
        }
        powerButton.setVisualState(visualState, buttonLabel, buttonEnabled)
        if (!state.configured && !api.enabled) {
            powerButton.contentDescription = "Добавить сервер для подключения VPN"
        }

        serverCard.contentDescription = "${serverValue.text}. ${serverHint.text}"
        rxValue.text = formatBytes(state.rxBytes)
        txValue.text = formatBytes(state.txBytes)
        rxRateValue.text = formatRate(state.rxRate)
        txRateValue.text = formatRate(state.txRate)

        sessionText.text = if (connected) "Сеанс " + formatDuration(state.sessionSeconds)
            else if (state.configured || api.enabled) "WireGuard" else "Профиль .conf"

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
        view.text = value
        if (view.isShown && hasWindowFocus() && ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 0.65f
            view.animate().alpha(1f).setDuration(180).start()
        } else view.alpha = 1f
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
        if (bytes < 1024) return bytes.toString() + " Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= 1024.0
            index++
        } while (value >= 1024 && index < units.lastIndex)
        return String.format(RU_LOCALE, "%.1f %s", value, units[index])
    }

    companion object {
        private const val REQ_VPN = 1001
        private val RU_LOCALE = Locale.forLanguageTag("ru-RU")

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
