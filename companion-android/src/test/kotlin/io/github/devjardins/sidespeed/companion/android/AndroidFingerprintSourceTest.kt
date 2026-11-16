package io.github.devjardins.sidespeed.companion.android

import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidFingerprintSourceTest {
    @Test fun mapsGatewaySubnetAndDns() {
        val data = LinkData(listOf("192.168.5.1"), listOf("192.168.5.23/24"), listOf("192.168.5.1"))
        assertEquals(NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1")), FingerprintMapping.map(data))
    }

    @Test fun noIpv4DefaultRouteIsNull() {
        assertNull(FingerprintMapping.map(LinkData(emptyList(), listOf("192.168.5.23/24"), emptyList())))
        assertNull(FingerprintMapping.map(LinkData(listOf("fe80::1"), listOf("192.168.5.23/24"), emptyList())))
        assertNull(FingerprintMapping.map(LinkData(listOf(null), listOf("192.168.5.23/24"), emptyList())))
        assertNull(FingerprintMapping.map(null))
    }

    @Test fun noIpv4AddressIsNull() {
        assertNull(FingerprintMapping.map(LinkData(listOf("192.168.5.1"), emptyList(), emptyList())))
    }

    @Test fun prefersIpv4GatewayAndSubnetHoldingIt() {
        val data = LinkData(
            listOf("fe80::1", "10.0.0.1"),
            listOf("172.16.9.4/16", "10.0.0.7/24"),
            listOf("fe80::1%wlan0", "10.0.0.1"),
        )
        assertEquals(NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1")), FingerprintMapping.map(data))
    }
}
