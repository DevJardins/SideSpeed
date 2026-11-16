package io.github.devjardins.sidespeed.companion.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EgressParsersTest {
    @Test fun macInterface() {
        val out = """
           route to: 100.101.1.1
        destination: 100.101.1.1
            gateway: link#17
          interface: utun4
              flags: <UP,HOST,DONE,LLINFO,WASCLONED,IFSCOPE,IFREF>
        """.trimIndent()
        assertEquals("utun4", EgressParsers.macInterface(out))
        assertNull(EgressParsers.macInterface("route: writing to routing socket: not in table"))
        assertNull(EgressParsers.macInterface("  interface:   "))
    }

    @Test fun linuxInterface() {
        assertEquals("tailscale0", EgressParsers.linuxInterface("100.101.1.1 dev tailscale0 table 52 src 100.64.0.5 uid 1000\n    cache"))
        assertEquals("eth0", EgressParsers.linuxInterface("100.101.1.1 via 192.168.1.1 dev eth0 src 192.168.1.9 uid 1000"))
        assertNull(EgressParsers.linuxInterface("RTNETLINK answers: Network is unreachable"))
        assertNull(EgressParsers.linuxInterface(""))
    }

    @Test fun windowsInterface() {
        assertEquals("Tailscale", EgressParsers.windowsInterface("Tailscale\r\n"))
        assertEquals("Ethernet 2", EgressParsers.windowsInterface("\r\nEthernet 2\r\n"))
        assertNull(EgressParsers.windowsInterface("Find-NetRoute : No matching MSFT_NetRoute objects found"))
        assertNull(EgressParsers.windowsInterface(""))
    }

    @Test fun onlyIpv4LiteralsReachACommandLine() {
        assertEquals("100.101.1.1", EgressParsers.safeIpv4("100.101.1.1"))
        assertNull(EgressParsers.safeIpv4("100.101.1.1; rm -rf /"))
        assertNull(EgressParsers.safeIpv4("-n"))
        assertNull(EgressParsers.safeIpv4("100.101.1"))
        assertNull(EgressParsers.safeIpv4("999.1.1.1"))
    }
}
