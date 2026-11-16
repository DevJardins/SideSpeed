package io.github.devjardins.sidespeed.companion.net

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CidrTest {
    private fun ip(s: String) = assertNotNull(Ipv4.parse(s), s)
    private fun cidr(s: String) = assertNotNull(Cidr.parse(s), s)

    @Test fun ipv4RoundTripsAndRejectsMalformed() {
        assertEquals("192.168.5.140", ip("192.168.5.140").toString())
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "a.b.c.d", "1..2.3", "01.2.3.4x", "1.2.3.-4", " 1.2.3.4")) {
            assertNull(Ipv4.parse(bad), bad)
        }
    }

    @Test fun cidrNormalisesToItsNetworkAddress() {
        val c = cidr("192.168.5.140/22")
        assertEquals("192.168.4.0", c.network.toString())
        assertEquals("192.168.4.0/22", c.toString())
        assertEquals("192.168.7.255", c.broadcast.toString())
    }

    @Test fun cidrRejectsMalformed() {
        for (bad in listOf("1.2.3.4", "1.2.3.4/33", "1.2.3.4/-1", "1.2.3.4/", "x/24", "1.2.3.4/2/4")) assertNull(Cidr.parse(bad), bad)
    }

    @Test fun hostCountsAcrossSlash16To32() {
        val expected = mapOf(16 to 65534L, 20 to 4094L, 22 to 1022L, 24 to 254L, 29 to 6L, 30 to 2L, 31 to 2L, 32 to 1L)
        for ((p, n) in expected) {
            val c = cidr("10.1.0.0/$p")
            assertEquals(1L shl (32 - p), c.size, "size /$p")
            assertEquals(n, c.hostCount, "hostCount /$p")
        }
        assertEquals(254, cidr("10.1.2.0/24").hosts().count())
    }

    @Test fun hostsExcludeNetworkAndBroadcastAndAreAscending() {
        val hosts = cidr("10.1.2.0/24").hosts().toList()
        assertEquals("10.1.2.1", hosts.first().toString())
        assertEquals("10.1.2.254", hosts.last().toString())
        assertEquals(hosts.sortedBy { it.value }, hosts)
    }

    @Test fun containsAgreesWithHostsOnSeededRandomCidrs() {
        val rnd = Random(20260101)
        repeat(200) {
            val prefix = 22 + rnd.nextInt(9) // /22../30 keeps enumeration small
            val c = Cidr.parse("${Ipv4(rnd.nextLong(0, 1L shl 32))}/$prefix")!!
            val hosts = c.hosts().toSet()
            assertEquals(c.hostCount, hosts.size.toLong())
            hosts.forEach { assertTrue(c.contains(it), "$it in $c") }
            assertTrue(c.contains(c.network) && c.contains(c.broadcast) || prefix >= 31)
            // Neighbours just outside the block are not contained.
            assertFalse(c.contains(Ipv4(c.network.value - 1)) && c.network.value > 0)
            assertFalse(c.contains(Ipv4(c.broadcast.value + 1)) && c.broadcast.value < 0xFFFFFFFFL)
            repeat(50) {
                val probe = Ipv4(rnd.nextLong(0, 1L shl 32))
                if (c.contains(probe) && probe != c.network && probe != c.broadcast) assertTrue(probe in hosts)
                if (probe in hosts) assertTrue(c.contains(probe))
            }
        }
    }
}

class AddressPolicyTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    @Test fun privateIsExactAtEveryRfc1918Boundary() {
        val inside = listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255")
        val outside = listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "8.8.8.8", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "255.255.255.255")
        inside.forEach { assertTrue(AddressPolicy.isPrivate(ip(it)), it) }
        outside.forEach { assertFalse(AddressPolicy.isPrivate(ip(it)), it) }
    }

    @Test fun overlayIsTheCgnatBlockExactAtBothBoundaries() {
        listOf("100.64.0.0", "100.101.102.103", "100.127.255.255").forEach { assertTrue(AddressPolicy.isOverlay(ip(it)), it) }
        listOf("100.63.255.255", "100.128.0.0", "10.0.0.1", "8.8.8.8", "192.168.1.1").forEach { assertFalse(AddressPolicy.isOverlay(ip(it)), it) }
    }

    @Test fun contactableIsPrivateOrOverlayAndNothingElse() {
        listOf("10.1.2.3", "172.16.0.1", "192.168.1.1", "100.64.0.1", "100.127.255.254").forEach { assertTrue(AddressPolicy.contactable(ip(it)), it) }
        listOf("8.8.8.8", "100.128.0.1", "127.0.0.1", "169.254.1.1", "172.32.0.1", "0.0.0.0").forEach { assertFalse(AddressPolicy.contactable(ip(it)), it) }
    }

    @Test fun sweepableNeedsPrivateBlockAndPrefix16To30() {
        fun s(c: String) = AddressPolicy.sweepable(Cidr.parse(c)!!)
        assertTrue(s("192.168.0.0/16")); assertTrue(s("10.4.0.0/22")); assertTrue(s("172.16.5.0/30")); assertTrue(s("172.20.0.0/16"))
        assertFalse(s("192.168.1.0/15")); assertFalse(s("10.0.0.0/8")); assertFalse(s("192.168.1.4/31")); assertFalse(s("192.168.1.4/32"))
        assertFalse(s("8.8.8.0/24")); assertFalse(s("172.32.0.0/16")); assertFalse(s("172.0.0.0/16")); assertFalse(s("100.64.0.0/16"))
    }
}
