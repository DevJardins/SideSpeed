package io.github.devjardins.sidespeed.companion.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedNetworksTest {
    private fun fp(
        gw: String = "192.168.5.1",
        subnet: String = "192.168.5.0/24",
        dns: Set<String> = setOf("1.1.1.1", "192.168.5.1"),
        mac: String? = null,
        ssid: String? = null,
        bssid: String? = null,
    ) = NetworkFingerprint(gw, subnet, dns, mac, ssid, bssid)

    @Test fun identicalMatches() = assertTrue(TrustMatcher.matches(fp(), fp()))
    @Test fun differentGateway() = assertFalse(TrustMatcher.matches(fp(gw = "192.168.5.2"), fp()))
    @Test fun differentSubnet() = assertFalse(TrustMatcher.matches(fp(subnet = "192.168.6.0/24"), fp()))
    @Test fun sameSubnetUnnormalisedMatches() = assertTrue(TrustMatcher.matches(fp(subnet = "192.168.5.140/24"), fp()))
    @Test fun disjointDns() = assertFalse(TrustMatcher.matches(fp(dns = setOf("9.9.9.9")), fp()))
    @Test fun intersectingDns() =
        assertTrue(TrustMatcher.matches(fp(dns = setOf("b", "c")), fp(dns = setOf("a", "b"))))
    @Test fun bothDnsEmpty() = assertTrue(TrustMatcher.matches(fp(dns = emptySet()), fp(dns = emptySet())))
    @Test fun oneDnsEmpty() = assertFalse(TrustMatcher.matches(fp(dns = emptySet()), fp()))
    @Test fun macsDiffer() = assertFalse(TrustMatcher.matches(fp(mac = "aa:bb:cc:dd:ee:01"), fp(mac = "aa:bb:cc:dd:ee:02")))
    @Test fun oneMacMissingIgnored() = assertTrue(TrustMatcher.matches(fp(mac = "aa:bb:cc:dd:ee:01"), fp()))
    @Test fun currentMacMissingWhenTrustedHasOneFails() =
        assertFalse(TrustMatcher.matches(fp(), fp(mac = "aa:bb:cc:dd:ee:01")))


    @Test fun ssidEqualMatches() = assertTrue(TrustMatcher.matches(fp(ssid = "Home"), fp(ssid = "Home")))
    @Test fun ssidMissingOnCurrentFails() = assertFalse(TrustMatcher.matches(fp(), fp(ssid = "Home")))
    @Test fun ssidDifferingFails() = assertFalse(TrustMatcher.matches(fp(ssid = "Home"), fp(ssid = "Work")))
    @Test fun ssidCaseDiffersFails() = assertFalse(TrustMatcher.matches(fp(ssid = "home"), fp(ssid = "Home")))
    @Test fun ssidUntrimmedDiffersFails() = assertFalse(TrustMatcher.matches(fp(ssid = "Home "), fp(ssid = "Home")))
    @Test fun ssidMissingOnTrustedIgnoresCurrent() =
        assertTrue(TrustMatcher.matches(fp(ssid = "Home"), fp()))

    @Test fun bssidAloneEqualMatches() =
        assertTrue(TrustMatcher.matches(fp(bssid = "AA-BB-CC-DD-EE-FF"), fp(bssid = "aa:bb:cc:dd:ee:ff")))
    @Test fun bssidAloneMissingOnCurrentFails() =
        assertFalse(TrustMatcher.matches(fp(), fp(bssid = "aa:bb:cc:dd:ee:ff")))
    @Test fun bssidAloneDifferingFails() =
        assertFalse(TrustMatcher.matches(fp(bssid = "aa:bb:cc:dd:ee:01"), fp(bssid = "aa:bb:cc:dd:ee:02")))
    @Test fun bssidIgnoredWhenTrustedHasSsid() =
        assertTrue(TrustMatcher.matches(fp(ssid = "Home", bssid = "aa:bb:cc:dd:ee:02"), fp(ssid = "Home", bssid = "aa:bb:cc:dd:ee:01")))
    @Test fun bssidMissingIgnoredWhenTrustedHasSsid() =
        assertTrue(TrustMatcher.matches(fp(ssid = "Home"), fp(ssid = "Home", bssid = "aa:bb:cc:dd:ee:01")))
    @Test fun bssidMissingOnTrustedIgnoresCurrent() =
        assertTrue(TrustMatcher.matches(fp(bssid = "aa:bb:cc:dd:ee:ff"), fp()))

    @Test fun upgradeAddsMissingFieldsOnly() {
        val record = TrustedNetwork("n", "N", fp())
        val current = fp(mac = "aa:bb:cc:dd:ee:ff", ssid = "Home", bssid = "11:22:33:44:55:66")
        val upgraded = TrustMatcher.upgraded(record, current)
        assertEquals(fp(mac = "aa:bb:cc:dd:ee:ff", ssid = "Home", bssid = "11:22:33:44:55:66"), upgraded?.fingerprint)
        assertEquals("n", upgraded?.id)
        assertEquals("N", upgraded?.name)
    }

    @Test fun upgradeKeepsExistingFields() {
        val record = TrustedNetwork("n", "N", fp(mac = "aa:bb:cc:dd:ee:ff", ssid = "Home"))
        val current = fp(mac = "aa:bb:cc:dd:ee:ff", ssid = "Home", bssid = "11:22:33:44:55:66")
        assertEquals(record.copy(fingerprint = record.fingerprint.copy(wifiBssid = "11:22:33:44:55:66")), TrustMatcher.upgraded(record, current))
    }

    @Test fun upgradeIsNullWhenNothingToAdd() {
        val record = TrustedNetwork("n", "N", fp(mac = "aa:bb:cc:dd:ee:ff", ssid = "Home"))
        val current = fp(mac = "aa:bb:cc:dd:ee:ff", ssid = "Home")
        assertNull(TrustMatcher.upgraded(record, current))
    }
    @Test fun macFormatsEqual() =
        assertTrue(TrustMatcher.matches(fp(mac = "0:1A:2b:3C:4d:5E"), fp(mac = "00-1a-2B-3c-4D-5e")))
    @Test fun blankGatewayNeverMatches() {
        assertFalse(TrustMatcher.matches(fp(gw = ""), fp(gw = "")))
        assertFalse(TrustMatcher.matches(fp(gw = " "), fp()))
        assertFalse(TrustMatcher.matches(fp(), fp(gw = "")))
    }
    @Test fun badSubnetNeverMatches() = assertFalse(TrustMatcher.matches(fp(subnet = "nope"), fp(subnet = "nope")))
    @Test fun badMacNeverMatches() = assertFalse(TrustMatcher.matches(fp(mac = "zz"), fp(mac = "zz")))

    @Test fun currentTrustedNullIsNull() {
        assertNull(TrustMatcher.currentTrusted(null, listOf(TrustedNetwork("1", "Lounge", fp()))))
    }

    @Test fun currentTrustedFindsMatch() {
        val a = TrustedNetwork("a", "A", fp(gw = "10.0.0.1", subnet = "10.0.0.0/24"))
        val b = TrustedNetwork("b", "B", fp())
        assertEquals(b, TrustMatcher.currentTrusted(fp(), listOf(a, b)))
        assertNull(TrustMatcher.currentTrusted(fp(gw = "172.16.0.1", subnet = "172.16.0.0/16"), listOf(a, b)))
    }

    @Test fun normalizeSubnets() {
        assertEquals("192.168.5.0/24", Subnets.normalize("192.168.5.140/24"))
        assertEquals("10.1.0.0/16", Subnets.normalize("10.1.200.3/16"))
        assertEquals("10.1.2.3/32", Subnets.normalize("10.1.2.3/32"))
        assertEquals("0.0.0.0/0", Subnets.normalize("10.1.2.3/0"))
        assertEquals("192.168.5.128/25", Subnets.normalize("192.168.5.140/25"))
    }

    @Test fun normalizeRejectsBadInput() {
        for (bad in listOf("", "1.2.3.4", "1.2.3.4/33", "1.2.3/24", "1.2.3.256/24", "a.b.c.d/24", "1.2.3.4/", "::1/64", "1.2.3.4/-1"))
            assertNull(Subnets.normalize(bad), bad)
    }

    @Test fun normalizeMac() {
        assertEquals("00:1a:2b:3c:4d:5e", Subnets.normalizeMac("0:1A:2b:3C:4d:5E"))
        assertNull(Subnets.normalizeMac("00:11:22"))
    }
}
