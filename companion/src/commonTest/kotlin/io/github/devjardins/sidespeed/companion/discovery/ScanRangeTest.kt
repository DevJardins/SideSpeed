package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanRangeTest {
    private val trusted = TrustedNetwork("home", "Home", NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1")))

    @Test fun blankIsAutomatic() {
        assertEquals(RangeChoice.Default, scanRangeFor("", trusted))
        assertEquals(RangeChoice.Default, scanRangeFor("   ", trusted))
    }

    @Test fun aSweepableRangeOnTheGatewaysSlash16IsCustom() {
        assertEquals(RangeChoice.Custom(Cidr.parse("192.168.4.0/22")!!), scanRangeFor("192.168.4.0/22", trusted))
        assertEquals(RangeChoice.Custom(Cidr.parse("192.168.5.0/24")!!), scanRangeFor(" 192.168.5.7/24\n", trusted))
        assertEquals(RangeChoice.Custom(Cidr.parse("192.168.9.0/24")!!), scanRangeFor("192.168.9.0/24", trusted))
    }

    @Test fun anythingElseIsInvalid() {
        for (text in listOf(
            "10.0.0.0/8", "192.168.5.0/31", "192.168.5.0/32", "172.16.0.0/24",
            "100.64.0.0/24", "8.8.8.0/24", "192.168.5.0", "garbage", "192.168.5.0/24/1",
        )) assertEquals(RangeChoice.Invalid, scanRangeFor(text, trusted), text)
    }

    @Test fun aNonIpv4GatewayRejectsEveryRange() {
        val broken = TrustedNetwork("home", "Home", NetworkFingerprint("", "192.168.5.0/24", setOf("192.168.5.1")))
        assertEquals(RangeChoice.Invalid, scanRangeFor("192.168.5.0/24", broken))
    }
}
