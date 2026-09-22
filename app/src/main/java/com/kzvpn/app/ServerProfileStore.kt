package com.kzvpn.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ServerProfileStore(context: Context) {
    data class StoredServer(
        val id: String,
        val name: String,
        val endpoint: String
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, "vpn_servers").apply { mkdirs() }

    fun listServers(): List<StoredServer> {
        val raw = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val endpoint = obj.optString("endpoint").trim()
                    if (id.isNotBlank() && name.isNotBlank() && fileFor(id).exists()) {
                        add(StoredServer(id, name, endpoint))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun addServer(name: String, endpoint: String, configBytes: ByteArray): StoredServer {
        val id = UUID.randomUUID().toString()
        writeEncrypted(fileFor(id), configBytes)
        val server = StoredServer(id, name.trim().take(40), endpoint.trim().take(200))
        val updated = listServers().toMutableList().apply { add(server) }
        saveMetadata(updated)
        return server
    }

    fun loadConfig(id: String): ByteArray? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return readEncrypted(file)
    }

    fun renameServer(id: String, newName: String) {
        val updated = listServers().map {
            if (it.id == id) it.copy(name = newName.trim().take(40)) else it
        }
        saveMetadata(updated)
    }

    fun deleteServer(id: String) {
        fileFor(id).delete()
        val updated = listServers().filterNot { it.id == id }
        saveMetadata(updated)
        if (getActiveId() == id) {
            setActiveId(updated.firstOrNull()?.id)
        }
    }

    fun getActiveId(): String? =
        prefs.getString(KEY_ACTIVE_ID, null)

    fun setActiveId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE_ID)
            else putString(KEY_ACTIVE_ID, id)
        }.apply()
    }

    private fun saveMetadata(servers: List<StoredServer>) {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("endpoint", server.endpoint)
            )
        }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun fileFor(id: String): File =
        File(directory, "server_$id.enc")

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun writeEncrypted(file: File, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv

        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(iv.size)
            out.write(iv)
            out.writeInt(encrypted.size)
            out.write(encrypted)
        }
    }

    private fun readEncrypted(file: File): ByteArray? {
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val ivSize = input.readInt()
                require(ivSize in 12..32)
                val iv = ByteArray(ivSize).also { input.readFully(it) }

                val encryptedSize = input.readInt()
                require(encryptedSize in 1..1_048_576)
                val encrypted = ByteArray(encryptedSize).also { input.readFully(it) }

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(encrypted)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "nivora_server_profiles"
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE_ID = "active_server_id"
        private const val KEY_ALIAS = "nivora-server-profiles-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
