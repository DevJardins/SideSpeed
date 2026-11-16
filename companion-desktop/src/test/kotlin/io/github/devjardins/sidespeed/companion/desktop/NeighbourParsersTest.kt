package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.net.Ipv4
import org.junit.Assert.assertEquals
import org.junit.Test

class NeighbourParsersTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    @Test fun macArp() {
        val out = """
            ? (192.168.5.140) at 3c:5a:b4:1:2:3 on en0 ifscope [ethernet]
            ? (192.168.5.9) at (incomplete) on en0 ifscope [ethernet]
            ? (192.168.5.255) at ff:ff:ff:ff:ff:ff on en0 ifscope [ethernet]
            ? (224.0.0.251) at 1:0:5e:0:0:fb on en0 ifscope permanent [ethernet]
        """.trimIndent()
        assertEquals(listOf(ip("192.168.5.140")), NeighbourParsers.mac(out))
    }

    @Test fun linuxNeigh() {
        val out = """
            192.168.5.140 dev wlan0 lladdr 3c:5a:b4:01:02:03 REACHABLE
            192.168.5.9 dev wlan0 FAILED
            192.168.5.10 dev wlan0  INCOMPLETE
        """.trimIndent()
        assertEquals(listOf(ip("192.168.5.140")), NeighbourParsers.linux(out))
    }

    @Test fun windowsArp() {
        val out = "  192.168.5.140         3c-5a-b4-01-02-03     dynamic\r\n" +
            "  192.168.5.255         ff-ff-ff-ff-ff-ff     static\r\n" +
            "  224.0.0.22            01-00-5e-00-00-16     static"
        assertEquals(listOf(ip("192.168.5.140")), NeighbourParsers.windows(out))
    }

    @Test fun garbageAndEmptyAreEmpty() {
        assertEquals(emptyList<Ipv4>(), NeighbourParsers.mac("garbage\n1.2.3.4 at nowhere"))
        assertEquals(emptyList<Ipv4>(), NeighbourParsers.mac(""))
        assertEquals(emptyList<Ipv4>(), NeighbourParsers.linux("garbage"))
        assertEquals(emptyList<Ipv4>(), NeighbourParsers.linux(""))
        assertEquals(emptyList<Ipv4>(), NeighbourParsers.windows("garbage"))
        assertEquals(emptyList<Ipv4>(), NeighbourParsers.windows(""))
    }
}
