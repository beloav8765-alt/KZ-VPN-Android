package com.kzvpn.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class VpnConfigInputTest {
    @Test
    fun acceptsSmallConfiguration() {
        val data = "[Interface]\nAddress = 10.0.0.2/32\n".toByteArray()
        assertArrayEquals(data, VpnConfigInput.readBounded(ByteArrayInputStream(data)))
    }

    @Test
    fun acceptsMaximumSize() {
        val data = ByteArray(VpnConfigInput.MAX_CONFIG_SIZE) { 65 }
        assertArrayEquals(data, VpnConfigInput.readBounded(ByteArrayInputStream(data)))
    }

    @Test
    fun rejectsOversizedConfigurationAndClosesInput() {
        val source = object : ByteArrayInputStream(ByteArray(VpnConfigInput.MAX_CONFIG_SIZE + 1)) {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }
        try {
            VpnConfigInput.readBounded(source)
            fail("A configuration larger than the limit must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("слишком большой"))
        }
        assertTrue("The document stream must be closed on failure", source.closed)
    }
}
