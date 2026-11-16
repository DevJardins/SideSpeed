package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlin.test.Test
import kotlin.test.assertEquals

class SweepScopeTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun cidr(s: String) = Cidr.parse(s)!!

    @Test
    fun subnetsOfSlash16OrSmallerAreSweptWhole() {
        for (s in listOf("192.168.5.0/24", "10.1.0.0/16", "172.16.4.0/22", "192.168.1.4/30")) {
            assertEquals(cidr(s), sweepScope(cidr(s), ip(cidr(s).network.toString()), null), s)
        }
    }

    @Test
    fun biggerSubnetsShrinkToTheSlash16AroundTheOwnAddress() {
        assertEquals(cidr("10.20.0.0/16"), sweepScope(cidr("10.0.0.0/8"), ip("10.20.3.4"), ip("10.0.0.1")))
        assertEquals(cidr("172.17.0.0/16"), sweepScope(cidr("172.16.0.0/12"), ip("172.17.9.9"), null))
        assertEquals(cidr("10.4.0.0/16"), sweepScope(cidr("10.4.0.0/15"), ip("10.4.0.2"), null))
    }

    @Test
    fun withoutAnOwnAddressTheGatewayAnchorsTheShrink() {
        assertEquals(cidr("10.0.0.0/16"), sweepScope(cidr("10.0.0.0/8"), null, ip("10.0.0.1")))
        assertEquals(cidr("10.5.0.0/16"), sweepScope(cidr("10.0.0.0/8"), ip("8.8.8.8"), ip("10.5.1.1")))
    }

    @Test
    fun anOversizedSubnetWithNoAnchorIsLeftAloneForDiscoveryToRefuse() {
        assertEquals(cidr("10.0.0.0/8"), sweepScope(cidr("10.0.0.0/8"), null, null))
    }
}
