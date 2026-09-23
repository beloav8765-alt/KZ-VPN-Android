package com.kzvpn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EneidaApiClient {
    data class Subscription(
        val active: Boolean,
        val validUntil: String?
    )

    data class Region(
        val code: String,
        val name: String,
        val flag: String,
        val servers: Int,
        val loadPct: Int
    )

    data class Provisioning(
        val serverName: String,
        val regionCode: String,
        val regionName: String,
        val endpoint: String,
        val serverPublicKey: String,
        val address: String,
        val dns: String,
        val mtu: Int,
        val allowedIps: String,
        val persistentKeepalive: Int
    )

    val enabled: Boolean
        get() = BuildConfig.CONTROL_API_BASE_URL.startsWith("https://")

    suspend fun subscription(deviceId: String): Subscription = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/v1/subscriptions/" + deviceId, null)
        Subscription(
            active = json.optBoolean("active", false),
            validUntil = json.optString("valid_until").ifBlank { null }
        )
    }

    suspend fun regions(): List<Region> = withContext(Dispatchers.IO) {
        val json = request("GET", "/api/v1/public/regions", null)
        val array = json.optJSONArray("regions") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Region(
                        code = item.optString("code"),
                        name = item.optString("name"),
                        flag = item.optString("flag"),
                        servers = item.optInt("servers", 0),
                        loadPct = item.optInt("load_pct", 0)
                    )
                )
            }
        }
    }

    suspend fun provision(
        identity: DeviceIdentityStore.Identity,
        preferredRegion: String? = null
    ): Provisioning = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_id", identity.deviceId)
            .put("public_key", identity.publicKey)
        if (!preferredRegion.isNullOrBlank() && preferredRegion != "auto") {
            body.put("preferred_region", preferredRegion)
        }

        val json = request("POST", "/api/v1/devices/provision", body.toString())
        Provisioning(
            serverName = json.optString("server_name", "Eneida"),
            regionCode = json.optString("region_code", "auto"),
            regionName = json.optString("region_name", "Лучший сервер"),
            endpoint = json.getString("endpoint"),
            serverPublicKey = json.getString("server_public_key"),
            address = json.getString("address"),
            dns = json.optString("dns", "8.8.8.8"),
            mtu = json.optInt("mtu", 1280),
            allowedIps = json.optString("allowed_ips", "0.0.0.0/0"),
            persistentKeepalive = json.optInt("persistent_keepalive", 25)
        )
    }

    fun makeWireGuardConfig(
        identity: DeviceIdentityStore.Identity,
        provisioning: Provisioning
    ): ByteArray {
        val text = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = " + identity.privateKey)
            appendLine("Address = " + provisioning.address)
            appendLine("DNS = " + provisioning.dns)
            appendLine("MTU = " + provisioning.mtu)
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = " + provisioning.serverPublicKey)
            appendLine("Endpoint = " + provisioning.endpoint)
            appendLine("AllowedIPs = " + provisioning.allowedIps)
            appendLine("PersistentKeepalive = " + provisioning.persistentKeepalive)
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun request(method: String, path: String, body: String?): JSONObject {
        if (!enabled) error("Eneida Control API is not configured over HTTPS")

        val base = BuildConfig.CONTROL_API_BASE_URL.trimEnd('/')
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            val detail = runCatching {
                JSONObject(text).optString("detail")
            }.getOrNull()
            throw EneidaApiException(
                code = code,
                message = detail?.ifBlank { null } ?: "Ошибка Eneida Control: $code"
            )
        }

        return JSONObject(text)
    }
}

class EneidaApiException(
    val code: Int,
    override val message: String
) : Exception(message)
