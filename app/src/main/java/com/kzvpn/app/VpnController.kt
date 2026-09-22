package com.kzvpn.app

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.BadConfigException
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

    data class UiState(
        val configured: Boolean = false,
        val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        val endpoint: String = "",
        val rxBytes: Long = 0,
        val txBytes: Long = 0,
        val message: String? = null
    )

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)
    private val secureStore = SecureConfigStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var config: Config? = null
    private var statsJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            when (newState) {
                Tunnel.State.UP -> {
                    _state.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED, message = null) }
                    startStatsPolling()
                }
                Tunnel.State.DOWN -> {
                    statsJob?.cancel()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.DISCONNECTED,
                            rxBytes = 0,
                            txBytes = 0
                        )
                    }
                }
                Tunnel.State.TOGGLE -> Unit
            }
        }
    }

    init {
        scope.launch {
            restoreStoredConfig(showError = true)
        }
    }

    private fun restoreStoredConfig(showError: Boolean): Config? {
        config?.let { return it }
        val stored = secureStore.load() ?: return null
        return runCatching {
            applyConfigBytes(stored, persist = false)
            config
        }.onFailure {
            if (showError) {
                _state.update { current ->
                    current.copy(message = "Не удалось прочитать сохранённый конфиг. Импортируй .conf заново.")
                }
            }
        }.getOrNull()
    }

    fun connectFromAlwaysOn() {
        scope.launch {
            val currentConfig = restoreStoredConfig(showError = false) ?: return@launch
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

    fun importConfig(input: InputStream) {
        scope.launch {
            runCatching {
                val rawBytes = input.use { it.readBytes() }
                require(rawBytes.size <= MAX_CONFIG_SIZE) { "Файл слишком большой" }

                val bytes = normalizeConfigBytes(rawBytes)
                val parsed = Config.parse(ByteArrayInputStream(bytes))
                config = parsed

                val endpoint = extractEndpoint(bytes)
                _state.update {
                    it.copy(
                        configured = true,
                        endpoint = endpoint,
                        message = "Конфигурация импортирована"
                    )
                }

                runCatching {
                    secureStore.save(bytes)
                }.onFailure { saveError ->
                    _state.update {
                        it.copy(
                            message = "Конфигурация загружена. Не удалось сохранить её после перезапуска: ${describeError(saveError)}"
                        )
                    }
                }
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        configured = false,
                        message = "Ошибка конфигурации: ${describeError(error)}"
                    )
                }
            }
        }
    }

    private fun applyConfigBytes(bytes: ByteArray, persist: Boolean) {
        val normalized = normalizeConfigBytes(bytes)
        val parsed = Config.parse(ByteArrayInputStream(normalized))
        config = parsed
        if (persist) secureStore.save(normalized)

        val endpoint = extractEndpoint(normalized)
        _state.update {
            it.copy(
                configured = true,
                endpoint = endpoint,
                message = null
            )
        }
    }

    private fun normalizeConfigBytes(bytes: ByteArray): ByteArray {
        val text = String(bytes, StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun describeError(error: Throwable): String {
        if (error is BadConfigException) {
            val section = error.section?.name ?: "?"
            val location = error.location?.name ?: "?"
            val reason = error.reason?.name ?: "?"
            val text = error.text?.toString()?.trim().orEmpty()
            return buildString {
                append("BadConfigException: ")
                append(section)
                append(" / ")
                append(location)
                append(" / ")
                append(reason)
                if (text.isNotBlank()) {
                    append(" / ")
                    append(text.take(120))
                }
            }
        }
        val name = error.javaClass.simpleName.ifBlank { "Ошибка" }
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) name else "$name: $message"
    }

    fun connect() {
        scope.launch {
            val currentConfig = config
            if (currentConfig == null) {
                _state.update { it.copy(message = "Сначала импортируй рабочий WireGuard .conf") }
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
        scope.launch {
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            statsJob?.cancel()
            secureStore.clear()
            config = null
            _state.value = UiState(message = "Конфигурация удалена с телефона")
        }
    }

    fun setMessage(message: String?) {
        _state.update { it.copy(message = message) }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                runCatching { backend.getStatistics(tunnel) }
                    .onSuccess { stats ->
                        _state.update {
                            it.copy(
                                rxBytes = stats.totalRx(),
                                txBytes = stats.totalTx()
                            )
                        }
                    }
                delay(1_000)
            }
        }
    }

    private fun extractEndpoint(bytes: ByteArray): String {
        val text = String(bytes, StandardCharsets.UTF_8)
        return ENDPOINT_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
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
        private const val TUNNEL_NAME = "kzvpn"
        private const val MAX_CONFIG_SIZE = 1_048_576
        private val ENDPOINT_REGEX = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+)$")
    }
}
