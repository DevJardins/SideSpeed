package io.github.devjardins.sidespeed.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressTest {
    private fun vpn(ip: String? = "100.79.202.46") = LanCandidate(isVpn = true, isWifiOrEthernet = false, ipv4 = ip)
    private fun wifi(ip: String? = "192.168.1.5") = LanCandidate(isVpn = false, isWifiOrEthernet = true, ipv4 = ip)
    private fun mobile(ip: String? = "10.20.30.40") = LanCandidate(isVpn = false, isWifiOrEthernet = false, ipv4 = ip)

    @Test fun vpnActiveFallsBackToTheWifi() {
        assertEquals("192.168.1.5", pickLanIpv4(vpn(), listOf(vpn(), wifi())))
    }

    @Test fun activeWifiWins() {
        assertEquals("192.168.1.5", pickLanIpv4(wifi(), listOf(wifi("192.168.1.9"), vpn())))
    }

    @Test fun vpnOverMobileDataGivesNothing() {
        assertNull(pickLanIpv4(vpn(), listOf(vpn(), mobile())))
    }

    @Test fun noIpv4IsSkipped() {
        assertEquals("192.168.1.9", pickLanIpv4(vpn(), listOf(wifi(null), wifi("192.168.1.9"))))
    }

    @Test fun noActiveNetworkStillFindsTheWifi() {
        assertEquals("192.168.1.5", pickLanIpv4(null, listOf(mobile(), wifi())))
    }
}
