package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SweepOrderTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun cidr(s: String) = Cidr.parse(s)!!

    @Test fun startsWithOwnSlash24ThenGatewaySlash24ThenOutward() {
        val order = SweepOrder.of(cidr("192.168.4.0/22"), own = ip("192.168.5.140"), gateway = ip("192.168.4.1"))
        val third = order.map { it.toString().split('.')[2] }
        assertEquals(List(255) { "5" }, third.take(255)) // own /24 minus own address
        assertEquals(List(255) { "4" }, third.drop(255).take(255))
        // Then outward: the remaining /24s (6 and 7) are ordered by distance from the own address.
        assertEquals("192.168.6.0", order[255 + 255].toString())
    }

    @Test fun unknownOwnFallsBackToTheGateway() {
        val order = SweepOrder.of(cidr("10.0.0.0/24"), own = null, gateway = ip("10.0.0.1"))
        assertEquals(254, order.size)
        assertEquals("10.0.0.1", order.first().toString())
    }

    @Test fun neverYieldsOwnNetworkOrBroadcastAndCapsRespectLimit() {
        val c = cidr("10.2.0.0/16")
        val order = SweepOrder.of(c, own = ip("10.2.9.9"), gateway = ip("10.2.0.1"), limit = 4096)
        assertEquals(4096, order.size)
        assertTrue(ip("10.2.9.9") !in order && c.network !in order && c.broadcast !in order)
    }

    @Test fun skipSetResumesWithoutRepeats() {
        val c = cidr("10.2.0.0/16")
        val first = SweepOrder.of(c, ip("10.2.9.9"), ip("10.2.0.1"), limit = 4096)
        val next = SweepOrder.of(c, ip("10.2.9.9"), ip("10.2.0.1"), limit = 4096, skip = first.toSet())
        assertEquals(4096, next.size)
        assertTrue(first.toSet().intersect(next.toSet()).isEmpty())
    }

    @Test fun seededPropertiesHoldOnRandomPrivateNetworks() {
        val rnd = Random(77)
        repeat(100) {
            val base = when (rnd.nextInt(3)) {
                0 -> 0x0A000000L + rnd.nextLong(0, 1L shl 24)
                1 -> 0xAC100000L + rnd.nextLong(0, 1L shl 20)
                else -> 0xC0A80000L + rnd.nextLong(0, 1L shl 16)
            }
            val c = Cidr.parse("${Ipv4(base)}/${16 + rnd.nextInt(15)}")!!
            if (!AddressPolicy.sweepable(c)) return@repeat
            val own = if (rnd.nextBoolean()) Ipv4(c.network.value + 1 + rnd.nextLong(0, maxOf(1L, c.hostCount))) else null
            val gw = Ipv4(c.network.value + 1)
            val limit = 1 + rnd.nextInt(5000)
            val order = SweepOrder.of(c, own, gw, limit)
            assertEquals(order.size, order.toSet().size, "duplicate-free in $c")
            assertTrue(order.size <= limit && order.size <= c.hostCount)
            assertEquals(minOf(limit.toLong(), c.hostCount - (if (own != null && c.contains(own) && own != c.network && own != c.broadcast) 1 else 0)), order.size.toLong())
            order.forEach {
                assertTrue(c.contains(it) && it != own && it != c.network && it != c.broadcast, "$it in $c")
                assertTrue(AddressPolicy.isPrivate(it))
            }
        }
    }
}
