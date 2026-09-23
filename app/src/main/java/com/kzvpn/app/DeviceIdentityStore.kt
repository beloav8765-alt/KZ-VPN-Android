package com.kzvpn.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wireguard.crypto.KeyPair
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

class DeviceIdentityStore(context: Context) {
    data class Identity(
        val deviceId: String,
        val privateKey: String,
        val publicKey: String
    )

    private val file = File(context.filesDir, "eneida_identity.enc")

    @Synchronized
    fun getOrCreate(): Identity {
        load()?.let { return it }

        val pair = KeyPair()
        val identity = Identity(
            deviceId = UUID.randomUUID().toString(),
            privateKey = pair.privateKey.toBase64(),
            publicKey = pair.publicKey.toBase64()
        )
        save(identity)
        return identity
    }

    private fun save(identity: Identity) {
        val json = JSONObject()
            .put("device_id", identity.deviceId)
            .put("private_key", identity.privateKey)
            .put("public_key", identity.publicKey)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json)
        val iv = cipher.iv

        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(iv.size)
            out.write(iv)
            out.writeInt(encrypted.size)
            out.write(encrypted)
        }
    }

    private fun load(): Identity? {
        if (!file.exists()) return null

        return runCatching {
            val plain = DataInputStream(file.inputStream().buffered()).use { input ->
                val ivSize = input.readInt()
                require(ivSize in 12..32)
                val iv = ByteArray(ivSize).also { input.readFully(it) }

                val encryptedSize = input.readInt()
                require(encryptedSize in 1..65536)
                val encrypted = ByteArray(encryptedSize).also { input.readFully(it) }

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, iv)
                )
                cipher.doFinal(encrypted)
            }

            val json = JSONObject(String(plain, Charsets.UTF_8))
            Identity(
                deviceId = json.getString("device_id"),
                privateKey = json.getString("private_key"),
                publicKey = json.getString("public_key")
            )
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
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

    companion object {
        private const val KEY_ALIAS = "eneida-device-identity-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
