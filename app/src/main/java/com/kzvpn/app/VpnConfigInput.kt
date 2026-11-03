package com.kzvpn.app

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads WireGuard configuration without ever buffering more than the maximum
 * accepted size. Input streams from Android document providers may be untrusted.
 */
internal object VpnConfigInput {
    const val MAX_CONFIG_SIZE = 1_048_576

    fun readBounded(input: InputStream): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_CONFIG_SIZE) {
                "Файл слишком большой"
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
