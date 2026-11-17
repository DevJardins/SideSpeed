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
    ) = NetworkFingerprint(gw, subnet, dns, mac)

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
