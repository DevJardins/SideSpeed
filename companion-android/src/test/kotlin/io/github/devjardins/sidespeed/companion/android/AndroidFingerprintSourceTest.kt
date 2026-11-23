package io.github.devjardins.sidespeed.companion.android

import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidFingerprintSourceTest {
    @Test fun mapsQuotedSsidAndBssid() {
        val data = LinkData(listOf("192.168.5.1"), listOf("192.168.5.23/24"), listOf("192.168.5.1"), "\"Home\"", "AA-BB-CC-DD-EE-FF")
        assertEquals("Home", FingerprintMapping.map(data)?.wifiSsid)
        assertEquals("aa:bb:cc:dd:ee:ff", FingerprintMapping.map(data)?.wifiBssid)
    }

    @Test fun redactedWifiValuesAreNull() {
        assertNull(FingerprintMapping.cleanSsid("<unknown ssid>"))
        assertNull(FingerprintMapping.cleanSsid("\"<unknown ssid>\""))
        assertNull(FingerprintMapping.cleanSsid(null))
        assertNull(FingerprintMapping.cleanSsid(""))
        assertNull(FingerprintMapping.cleanBssid("02:00:00:00:00:00"))
        assertNull(FingerprintMapping.cleanBssid(null))
        assertNull(FingerprintMapping.cleanBssid("not-a-mac"))
        assertEquals("aa:bb:cc:dd:ee:ff", FingerprintMapping.cleanBssid("AA-BB-CC-DD-EE-FF"))
    }

    @Test fun redactedWifiValuesMapToNulls() {
        val data = LinkData(listOf("192.168.5.1"), listOf("192.168.5.23/24"), listOf("192.168.5.1"), "<unknown ssid>", "02:00:00:00:00:00")
        val fp = FingerprintMapping.map(data)
        assertNull(fp?.wifiSsid)
        assertNull(fp?.wifiBssid)
    }

    @Test fun mapsGatewaySubnetAndDns() {
        val data = LinkData(listOf("192.168.5.1"), listOf("192.168.5.23/24"), listOf("192.168.5.1"))
        assertEquals(NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"), ownIp = "192.168.5.23"), FingerprintMapping.map(data))
    }

    @Test fun ownIpComesFromTheLinkAddressInsideTheGatewaySubnet() {
        val data = LinkData(
            listOf("10.0.0.1"),
            listOf("172.16.9.4/16", "10.0.0.7/24"),
            listOf("10.0.0.1"),
        )
        assertEquals("10.0.0.7", FingerprintMapping.map(data)?.ownIp)
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
        assertEquals(NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"), ownIp = "10.0.0.7"), FingerprintMapping.map(data))
    }

    @Test fun zeroGatewayAndGatewayOutsideLinkSubnetAreNull() {
        assertNull(FingerprintMapping.map(LinkData(listOf("0.0.0.0"), listOf("10.20.30.2/30"), emptyList())))
        assertNull(FingerprintMapping.map(LinkData(listOf("192.168.9.1"), listOf("10.20.30.2/30"), emptyList())))
    }

    @Test fun ipv4CidrKeepsOnlyIpv4() {
        assertEquals("192.168.5.23/24", FingerprintMapping.ipv4Cidr("192.168.5.23", 24))
        assertNull(FingerprintMapping.ipv4Cidr("fe80::1", 64))
        assertNull(FingerprintMapping.ipv4Cidr(null, 24))
    }

    @Test fun scheduleRules() {
        assertEquals(15L, UpkeepWorker.periodMinutes(15))
        assertEquals(60L, UpkeepWorker.periodMinutes(60))
        assertEquals(15L, UpkeepWorker.periodMinutes(7))
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.REPLACE, UpkeepWorker.policy(true))
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.KEEP, UpkeepWorker.policy(false))
    }

    @Test fun notificationPermissionAskedOnlyOnOffToOn() {
        assertEquals(true, MainActivity.shouldAskForNotifications(false, true))
        assertEquals(false, MainActivity.shouldAskForNotifications(null, true))
        assertEquals(false, MainActivity.shouldAskForNotifications(true, true))
        assertEquals(false, MainActivity.shouldAskForNotifications(true, false))
    }

    private val wifi = LinkData(listOf("192.168.5.1"), listOf("192.168.5.23/24"), listOf("192.168.5.1"))
    private val vpn = LinkData(emptyList(), listOf("100.101.2.3/32"), listOf("100.100.100.100"))
    private val exitVpn = LinkData(listOf(null), listOf("100.101.2.3/32"), listOf("100.100.100.100"))
    private val cell = LinkData(listOf("10.20.30.1"), listOf("10.20.30.2/30"), emptyList())
    private val homeFingerprint = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"), ownIp = "192.168.5.23")

    @Test fun vpnActiveFallsBackToTheWifiUnderneath() {
        val active = NetworkCandidate(isVpn = true, isWifiOrEthernet = false, link = vpn)
        val all = listOf(active, NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi))
        assertEquals(homeFingerprint, FingerprintMapping.map(FingerprintMapping.pickLan(active, all)?.link))
    }

    @Test fun exitNodeVpnIsSkippedToo() {
        val active = NetworkCandidate(isVpn = true, isWifiOrEthernet = false, link = exitVpn)
        val all = listOf(active, NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi))
        assertEquals(homeFingerprint, FingerprintMapping.map(FingerprintMapping.pickLan(active, all)?.link))
    }

    @Test fun activeWifiWins() {
        val ethernet = LinkData(listOf("10.0.0.1"), listOf("10.0.0.7/24"), listOf("10.0.0.1"))
        val active = NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi)
        val all = listOf(NetworkCandidate(isVpn = false, isWifiOrEthernet = true, link = ethernet), active)
        assertEquals(wifi, FingerprintMapping.pickLan(active, all)?.link)
    }

    @Test fun vpnOverMobileDataGivesNothing() {
        val active = NetworkCandidate(isVpn = true, isWifiOrEthernet = false, link = vpn)
        val all = listOf(active, NetworkCandidate(isVpn = false, isWifiOrEthernet = false, link = cell))
        assertNull(FingerprintMapping.pickLan(active, all)?.link)
    }

    @Test fun nullLinksAreSkipped() {
        val active = NetworkCandidate(isVpn = true, isWifiOrEthernet = false, link = vpn)
        val all = listOf(active, NetworkCandidate(isVpn = false, isWifiOrEthernet = true, link = null), NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi))
        assertEquals(wifi, FingerprintMapping.pickLan(active, all)?.link)
    }

    @Test fun noActiveNetworkStillFindsTheWifi() {
        val all = listOf(NetworkCandidate(isVpn = true, isWifiOrEthernet = false, link = vpn), NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi))
        assertEquals(wifi, FingerprintMapping.pickLan(null, all)?.link)
    }

    @Test fun pickedLinkKnowsWhetherItIsWifi() {
        val wifiCandidate = NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi)
        val ethernet = LinkData(listOf("10.0.0.1"), listOf("10.0.0.7/24"), listOf("10.0.0.1"))
        val ethernetCandidate = NetworkCandidate(isVpn = false, isWifiOrEthernet = true, link = ethernet)
        val vpnCandidate = NetworkCandidate(isVpn = true, isWifiOrEthernet = false, link = vpn)
        assertEquals(true, FingerprintMapping.pickLan(wifiCandidate, listOf(ethernetCandidate, wifiCandidate))?.isWifi)
        assertEquals(false, FingerprintMapping.pickLan(ethernetCandidate, listOf(wifiCandidate, ethernetCandidate))?.isWifi)
        assertEquals(true, FingerprintMapping.pickLan(vpnCandidate, listOf(vpnCandidate, wifiCandidate))?.isWifi)
        assertEquals(false, FingerprintMapping.pickLan(vpnCandidate, listOf(vpnCandidate, ethernetCandidate))?.isWifi)
    }

    @Test fun wifiNameNeedsOptInPermissionLocationAndAWifiLink() {
        val wifiCandidate = NetworkCandidate(isVpn = false, isWifiOrEthernet = true, isWifi = true, link = wifi)
        val ethernet = LinkData(listOf("10.0.0.1"), listOf("10.0.0.7/24"), listOf("10.0.0.1"))
        val ethernetCandidate = NetworkCandidate(isVpn = false, isWifiOrEthernet = true, link = ethernet)
        assertEquals(true, FingerprintMapping.wifiNameAllowed(useWifiName = true, permissionGranted = true, locationOn = true, picked = wifiCandidate))
        assertEquals(false, FingerprintMapping.wifiNameAllowed(useWifiName = false, permissionGranted = true, locationOn = true, picked = wifiCandidate))
        assertEquals(false, FingerprintMapping.wifiNameAllowed(useWifiName = true, permissionGranted = false, locationOn = true, picked = wifiCandidate))
        assertEquals(false, FingerprintMapping.wifiNameAllowed(useWifiName = true, permissionGranted = true, locationOn = false, picked = wifiCandidate))
        assertEquals(false, FingerprintMapping.wifiNameAllowed(useWifiName = true, permissionGranted = true, locationOn = true, picked = ethernetCandidate))
        assertEquals(false, FingerprintMapping.wifiNameAllowed(useWifiName = true, permissionGranted = true, locationOn = true, picked = null))
    }
}
