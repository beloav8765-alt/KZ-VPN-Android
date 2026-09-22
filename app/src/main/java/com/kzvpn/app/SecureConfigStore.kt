package com.kzvpn.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val file = File(context.filesDir, "wireguard_config.enc")

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

    fun save(plain: ByteArray) {
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

    fun load(): ByteArray? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val ivSize = input.readInt()
                require(ivSize in 12..32) { "Invalid IV" }
                val iv = ByteArray(ivSize).also { input.readFully(it) }
                val encryptedSize = input.readInt()
                require(encryptedSize in 1..1_048_576) { "Invalid encrypted config size" }
                val encrypted = ByteArray(encryptedSize).also { input.readFully(it) }

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                cipher.doFinal(encrypted)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    companion object {
        private const val KEY_ALIAS = "kzvpn-wireguard-config-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
