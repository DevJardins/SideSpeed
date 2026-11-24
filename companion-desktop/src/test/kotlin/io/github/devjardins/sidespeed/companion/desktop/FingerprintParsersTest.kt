package io.github.devjardins.sidespeed.companion.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintParsersTest {
    private val macRoute = """
           route to: default
        destination: default
               mask: default
            gateway: 192.168.4.1
          interface: en0
              flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
         recvpipe  sendpipe  ssthresh  rtt,msec    rttvar  hopcount      mtu     expire
               0         0         0         0         0         0      1500         0
    """.trimIndent()

    @Test fun macGateway() {
        assertEquals("192.168.4.1", FingerprintParsers.macGateway(macRoute))
        assertNull(FingerprintParsers.macGateway("route: writing to routing socket: not in table"))
        assertNull(FingerprintParsers.macGateway("gateway: fe80::1%en0"))
    }

    private val scutil = """
        DNS configuration

        resolver #1
          nameserver[0] : 1.1.1.1
          nameserver[1] : 8.8.8.8
          nameserver[2] : fe80::1%en0
          flags    : Request A records, Request AAAA records

        resolver #2
          domain   : local
          nameserver[0] : 1.1.1.1
          options  : mdns
    """.trimIndent()

    @Test fun macDnsKeepsIpv4OnceInOrder() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), FingerprintParsers.macDns(scutil).toList())
    }

    /** Shape taken from `scutil --dns` with Tailscale's supplemental resolver present. */
    @Test fun macDnsKeepsSupplementalTailscaleResolver() {
        val tailscale = """
            DNS configuration

            resolver #1
              search domain[0] : example.ts.net
              nameserver[0] : 100.100.100.100
              nameserver[1] : fd7a:115c:a1e0::53
              if_index : 21 (utun5)
              flags    : Supplemental, Request A records, Request AAAA records
              reach    : 0x00000003 (Reachable,Transient Connection)
              order    : 101400

            resolver #2
              nameserver[0] : 1.1.1.1
              nameserver[1] : 8.8.8.8
              flags    : Request A records, Request AAAA records
              reach    : 0x00000002 (Reachable)
              order    : 200000

            resolver #3
              domain   : example.ts.net.
              nameserver[0] : 100.100.100.100
              nameserver[1] : fd7a:115c:a1e0::53
              if_index : 21 (utun5)
              flags    : Supplemental, Request A records, Request AAAA records
              reach    : 0x00000003 (Reachable,Transient Connection)
              order    : 101401
        """.trimIndent()
        assertEquals(
            listOf("100.100.100.100", "1.1.1.1", "8.8.8.8"),
            FingerprintParsers.macDns(tailscale).toList(),
        )
    }

    @Test fun macArp() {
        val out = "? (192.168.4.1) at 02:42:ac:11:00:02 on en0 ifscope [ethernet]"
        assertEquals("02:42:ac:11:00:02", FingerprintParsers.gatewayMac(out, "192.168.4.1"))
        assertEquals("0a:0b:0c:0d:0e:0f", FingerprintParsers.gatewayMac("? (192.168.4.1) at a:b:c:d:e:f on en0", "192.168.4.1"))
        assertNull(FingerprintParsers.gatewayMac("? (192.168.4.1) at (incomplete) on en0 ifscope [ethernet]", "192.168.4.1"))
        assertNull(FingerprintParsers.gatewayMac(out, "192.168.4.10"))
    }

    @Test fun linuxGatewayDnsAndNeighbour() {
        assertEquals("192.168.1.1", FingerprintParsers.linuxGateway("default via 192.168.1.1 dev wlan0 proto dhcp metric 600\n"))
        assertNull(FingerprintParsers.linuxGateway("192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.23\n"))
        assertNull(FingerprintParsers.linuxGateway("default via fe80::1 dev wlan0 metric 1024"))
        val resolv = "# Generated\nnameserver 192.168.1.1\nnameserver 2001:4860:4860::8888\nsearch lan\n"
        assertEquals(listOf("192.168.1.1"), FingerprintParsers.resolvConf(resolv).toList())
        val rc = "Global: 9.9.9.9\nLink 2 (wlan0): 192.168.1.1 fe80::1\n"
        assertEquals(listOf("9.9.9.9", "192.168.1.1"), FingerprintParsers.resolvectl(rc).toList())
        assertEquals(
            "a0:b1:c2:d3:e4:f5",
            FingerprintParsers.gatewayMac("192.168.1.1 dev wlan0 lladdr a0:b1:c2:d3:e4:f5 REACHABLE", "192.168.1.1"),
        )
    }

    private val winRoute = """
        IPv4 Route Table
        ===========================================================================
        Active Routes:
        Network Destination        Netmask          Gateway       Interface  Metric
                  0.0.0.0          0.0.0.0      192.168.1.1    192.168.1.23     35
                  0.0.0.0          0.0.0.0      10.8.0.1       10.8.0.2         10
                127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
    """.trimIndent()

    @Test fun windowsGatewayDnsAndArp() {
        assertEquals("10.8.0.1", FingerprintParsers.windowsGateway(winRoute))
        assertNull(FingerprintParsers.windowsGateway("0.0.0.0 0.0.0.0 On-link 192.168.1.23 281"))
        assertEquals(listOf("192.168.1.1", "8.8.8.8"), FingerprintParsers.windowsDns("192.168.1.1\r\n8.8.8.8\r\n\r\n").toList())
        val arp = "Interface: 192.168.1.23 --- 0x7\n  Internet Address      Physical Address      Type\n  192.168.1.1           a0-b1-c2-d3-e4-f5     dynamic\n"
        assertEquals("a0:b1:c2:d3:e4:f5", FingerprintParsers.gatewayMac(arp, "192.168.1.1"))
    }
}
