package com.kzvpn.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EneidaApiClientTest {
    private val identity = DeviceIdentityStore.Identity(
        deviceId = "device",
        privateKey = "test-private-key",
        publicKey = "test-public-key"
    )

    private fun provisioning(dns: String = "1.1.1.1", mtu: Int = 1280) =
        EneidaApiClient.Provisioning(
            serverId = 1,
            serverName = "test",
            regionCode = "auto",
            regionName = "test",
            endpoint = "example.org:51820",
            serverPublicKey = "test-server-key",
            address = "10.0.0.2/32",
            dns = dns,
            mtu = mtu,
            allowedIps = "0.0.0.0/0",
            persistentKeepalive = 25
        )

    @Test
    fun rejectsInjectedWireGuardSections() {
        val maliciousDns = "1.1.1.1\n[Peer]\nAllowedIPs = 0.0.0.0/0"
        try {
            EneidaApiClient().makeWireGuardConfig(identity, provisioning(dns = maliciousDns))
            fail("Newlines in provisioning data must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Некорректные"))
        }
    }

    @Test
    fun rejectsInvalidMtu() {
        try {
            EneidaApiClient().makeWireGuardConfig(identity, provisioning(mtu = -1))
            fail("Invalid MTU must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("MTU"))
        }
    }

    @Test
    fun acceptsNormalProvisioning() {
        val config = String(
            EneidaApiClient().makeWireGuardConfig(identity, provisioning()),
            Charsets.UTF_8
        )
        assertTrue(config.contains("DNS = 1.1.1.1"))
        assertTrue(config.contains("MTU = 1280"))
    }
}
