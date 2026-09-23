package com.kzvpn.app

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class VpnController(context: Context) {
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    data class ServerSummary(
        val id: String,
        val name: String,
        val endpoint: String
    )

    data class UiState(
        val configured: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val endpoint: String = "",
        val activeServerId: String? = null,
        val activeServerName: String = "Профиль не выбран",
        val servers: List<ServerSummary> = emptyList(),
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        val rxRate: Long = 0,
        val txRate: Long = 0,
        val sessionSeconds: Long = 0,
        val message: String? = null
    )

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)
    private val legacyStore = SecureConfigStore(appContext)
    private val serverStore = ServerProfileStore(appContext)
    private val managedPrefs = appContext.getSharedPreferences(
        "eneida_managed_profile",
        Context.MODE_PRIVATE
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var config: Config? = null
    private var statsJob: Job? = null
    private var connectedAtMs: Long = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            when (newState) {
                Tunnel.State.UP -> {
                    connectedAtMs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.CONNECTED,
                            rxRate = 0,
                            txRate = 0,
                            sessionSeconds = 0,
                            message = null
                        )
                    }
                    startStatsPolling()
                }

                Tunnel.State.DOWN -> {
                    statsJob?.cancel()
                    connectedAtMs = 0L
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            rxBytes = 0,
                            txBytes = 0,
                            rxRate = 0,
                            txRate = 0,
                            sessionSeconds = 0
                        )
                    }
                }

                Tunnel.State.TOGGLE -> Unit
            }
        }
    }

    init {
        scope.launch {
            restoreProfilesAndActiveConfig(showError = true)
        }
    }

    private fun restoreProfilesAndActiveConfig(showError: Boolean): Config? {
        migrateLegacyProfileIfNeeded()

        val servers = serverStore.listServers()
        if (servers.isEmpty()) {
            config = null
            _state.update {
                it.copy(
                    configured = false,
                    endpoint = "",
                    activeServerId = null,
                    activeServerName = "Профиль не выбран",
                    servers = emptyList()
                )
            }
            return null
        }

        var activeId = serverStore.getActiveId()
        if (activeId == null || servers.none { it.id == activeId }) {
            activeId = servers.first().id
            serverStore.setActiveId(activeId)
        }

        return runCatching {
            loadServerIntoMemory(activeId, servers)
            config
        }.onFailure { error ->
            if (showError) {
                _state.update {
                    it.copy(message = "Не удалось загрузить выбранный сервер: ${describeError(error)}")
                }
            }
        }.getOrNull()
    }

    private fun migrateLegacyProfileIfNeeded() {
        if (serverStore.listServers().isNotEmpty()) return

        val legacy = legacyStore.load() ?: return
        runCatching {
            val normalized = normalizeConfigBytes(legacy)
            Config.parse(ByteArrayInputStream(normalized))
            val endpoint = extractEndpoint(normalized)
            val profile = serverStore.addServer(
                name = "Основной сервер",
                endpoint = endpoint,
                configBytes = normalized
            )
            serverStore.setActiveId(profile.id)
        }
    }

    private fun loadServerIntoMemory(
        id: String,
        knownServers: List<ServerProfileStore.StoredServer> = serverStore.listServers()
    ) {
        val server = knownServers.firstOrNull { it.id == id }
            ?: error("Сервер не найден")
        val bytes = serverStore.loadConfig(id)
            ?: error("Конфигурация сервера недоступна")
        val normalized = normalizeConfigBytes(bytes)
        config = Config.parse(ByteArrayInputStream(normalized))
        serverStore.setActiveId(id)

        _state.update {
            it.copy(
                configured = true,
                endpoint = server.endpoint,
                activeServerId = server.id,
                activeServerName = server.name,
                servers = knownServers.map { item ->
                    ServerSummary(item.id, item.name, item.endpoint)
                },
                message = null
            )
        }
    }

    fun connectFromAlwaysOn() {
        scope.launch {
            val currentConfig = config ?: restoreProfilesAndActiveConfig(showError = false) ?: return@launch
            runCatching {
                backend.setState(tunnel, Tunnel.State.UP, currentConfig)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        message = humanizeError(error)
                    )
                }
            }
        }
    }

    fun importConfig(input: InputStream, suggestedName: String? = null) {
        scope.launch {
            runCatching {
                val rawBytes = input.use { it.readBytes() }
                require(rawBytes.size <= MAX_CONFIG_SIZE) { "Файл слишком большой" }

                val bytes = normalizeConfigBytes(rawBytes)
                Config.parse(ByteArrayInputStream(bytes))

                val endpoint = extractEndpoint(bytes)
                val currentCount = serverStore.listServers().size
                val name = cleanServerName(suggestedName)
                    ?: if (currentCount == 0) "Основной сервер" else "Сервер ${currentCount + 1}"

                val profile = serverStore.addServer(
                    name = name,
                    endpoint = endpoint,
                    configBytes = bytes
                )
                loadServerIntoMemory(profile.id)

                _state.update {
                    it.copy(message = "Сервер «${profile.name}» добавлен")
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(message = "Ошибка конфигурации: ${describeError(error)}")
                }
            }
        }
    }

    suspend fun installManagedConfig(
        bytes: ByteArray,
        serverName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.size <= MAX_CONFIG_SIZE) { "Конфигурация слишком большая" }
            val normalized = normalizeConfigBytes(bytes)
            Config.parse(ByteArrayInputStream(normalized))
            val endpoint = extractEndpoint(normalized)

            val previousManagedId = managedPrefs.getString(MANAGED_PROFILE_ID_KEY, null)
            if (!previousManagedId.isNullOrBlank()) {
                serverStore.deleteServer(previousManagedId)
            }

            val profile = serverStore.addServer(
                name = serverName.ifBlank { MANAGED_PROFILE_NAME },
                endpoint = endpoint,
                configBytes = normalized
            )
            managedPrefs.edit()
                .putString(MANAGED_PROFILE_ID_KEY, profile.id)
                .apply()
            loadServerIntoMemory(profile.id)
            _state.update {
                it.copy(message = "Eneida настроена автоматически")
            }
        }.onFailure { error ->
            _state.update {
                it.copy(message = "Автонастройка VPN: ${describeError(error)}")
            }
        }
    }

    fun selectServer(id: String) {
        scope.launch {
            if (_state.value.activeServerId == id) return@launch

            val wasConnected = _state.value.connectionStatus == ConnectionStatus.CONNECTED
            if (wasConnected) {
                _state.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTING, message = null) }
                val downResult = runCatching {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
                if (downResult.isFailure) {
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.CONNECTED,
                            message = humanizeError(downResult.exceptionOrNull()!!)
                        )
                    }
                    return@launch
                }
            }

            runCatching {
                loadServerIntoMemory(id)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        message = "Не удалось выбрать сервер: ${describeError(error)}"
                    )
                }
                return@launch
            }

            if (wasConnected) {
                val currentConfig = config ?: return@launch
                _state.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING) }
                runCatching {
                    backend.setState(tunnel, Tunnel.State.UP, currentConfig)
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            message = humanizeError(error)
                        )
                    }
                }
            } else {
                _state.update { it.copy(message = "Сервер выбран") }
            }
        }
    }

    fun renameServer(id: String, newName: String) {
        scope.launch {
            val cleaned = newName.trim().take(40)
            if (cleaned.isBlank()) return@launch
            serverStore.renameServer(id, cleaned)
            refreshServerList()
            _state.update { it.copy(message = "Сервер переименован") }
        }
    }

    fun removeServer(id: String) {
        scope.launch {
            val isActive = _state.value.activeServerId == id
            if (isActive && _state.value.connectionStatus == ConnectionStatus.CONNECTED) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            }

            serverStore.deleteServer(id)

            val remaining = serverStore.listServers()
            if (remaining.isEmpty()) {
                config = null
                _state.value = UiState(message = "Сервер удалён")
                return@launch
            }

            val nextId = if (isActive) remaining.first().id
            else serverStore.getActiveId()?.takeIf { active -> remaining.any { it.id == active } }
                ?: remaining.first().id

            loadServerIntoMemory(nextId, remaining)
            _state.update { it.copy(message = "Сервер удалён") }
        }
    }

    private fun refreshServerList() {
        val servers = serverStore.listServers()
        val activeId = serverStore.getActiveId()
        val active = servers.firstOrNull { it.id == activeId }
        _state.update {
            it.copy(
                servers = servers.map { item -> ServerSummary(item.id, item.name, item.endpoint) },
                activeServerId = active?.id,
                activeServerName = active?.name ?: "Профиль не выбран",
                endpoint = active?.endpoint.orEmpty(),
                configured = active != null
            )
        }
    }

    fun connect() {
        scope.launch {
            val currentConfig = config ?: restoreProfilesAndActiveConfig(showError = false)
            if (currentConfig == null) {
                _state.update { it.copy(message = "Сначала добавьте VPN-сервер") }
                return@launch
            }

            _state.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING, message = null) }
            runCatching {
                backend.setState(tunnel, Tunnel.State.UP, currentConfig)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        message = humanizeError(error)
                    )
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            _state.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTING, message = null) }
            runCatching {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        message = humanizeError(error)
                    )
                }
            }
        }
    }

    fun forgetConfig() {
        _state.value.activeServerId?.let(::removeServer)
    }

    fun setMessage(message: String?) {
        _state.update { it.copy(message = message) }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var previousRx = 0L
            var previousTx = 0L
            var initialized = false

            while (isActive) {
                runCatching { backend.getStatistics(tunnel) }
                    .onSuccess { stats ->
                        val rx = stats.totalRx()
                        val tx = stats.totalTx()
                        val rxRate = if (initialized) (rx - previousRx).coerceAtLeast(0L) else 0L
                        val txRate = if (initialized) (tx - previousTx).coerceAtLeast(0L) else 0L
                        previousRx = rx
                        previousTx = tx
                        initialized = true

                        val seconds = if (connectedAtMs > 0L)
                            ((System.currentTimeMillis() - connectedAtMs) / 1000L).coerceAtLeast(0L)
                        else 0L

                        _state.update {
                            it.copy(
                                rxBytes = rx,
                                txBytes = tx,
                                rxRate = rxRate,
                                txRate = txRate,
                                sessionSeconds = seconds
                            )
                        }
                    }
                delay(1_000)
            }
        }
    }

    private fun normalizeConfigBytes(bytes: ByteArray): ByteArray {
        val text = String(bytes, StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun extractEndpoint(bytes: ByteArray): String {
        val text = String(bytes, StandardCharsets.UTF_8)
        return ENDPOINT_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun cleanServerName(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw
            .removeSuffix(".conf")
            .removeSuffix(".CONF")
            .trim()
            .take(40)
            .ifBlank { null }
    }

    private fun describeError(error: Throwable): String {
        if (error is BadConfigException) {
            val section = error.section?.name ?: "?"
            val location = error.location?.name ?: "?"
            val reason = error.reason?.name ?: "?"
            return "$section / $location / $reason"
        }
        val name = error.javaClass.simpleName.ifBlank { "Ошибка" }
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) name else "$name: $message"
    }

    private fun humanizeError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("VPN_NOT_AUTHORIZED", ignoreCase = true) ->
                "Android не дал разрешение на VPN"
            raw.isNotBlank() -> "VPN: $raw"
            else -> "VPN: ${error.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TUNNEL_NAME = "eneida"
        private const val MANAGED_PROFILE_NAME = "Eneida Auto"
        private const val MANAGED_PROFILE_ID_KEY = "active_managed_profile_id"
        private const val MAX_CONFIG_SIZE = 1_048_576
        private val ENDPOINT_REGEX = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+)$")
    }
}
