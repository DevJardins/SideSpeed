package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlin.test.Test
import kotlin.test.assertEquals

class TailscaleStatusTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    private val status = """
        {"Version":"1.80.0","BackendState":"Running",
         "Self":{"HostName":"mac","TailscaleIPs":["100.100.1.1","fd7a:115c:a1e0::1"],"Online":true},
         "Peer":{
           "nodekey:aaa":{"HostName":"cube","TailscaleIPs":["100.101.102.103","fd7a:115c:a1e0::2"],"Online":true},
           "nodekey:bbb":{"HostName":"onn","TailscaleIPs":["100.101.102.104"],"Online":true},
           "nodekey:ccc":{"HostName":"asleep","TailscaleIPs":["100.101.102.105"],"Online":false},
           "nodekey:ddd":{"HostName":"unknown-state","TailscaleIPs":["100.101.102.106"]},
           "nodekey:eee":{"HostName":"odd","TailscaleIPs":["8.8.8.8","not-an-ip"],"Online":true}
         }}
    """.trimIndent()

    @Test
    fun listsOnlinePeerIpv4AddressesAndNeverSelf() {
        // Reports what Tailscale says, valid IPv4 only (a Headscale tailnet may use other ranges); which of them
        // may be contacted is Discovery's policy filter, tested there.
        assertEquals(
            listOf(ip("100.101.102.103"), ip("100.101.102.104"), ip("100.101.102.106"), ip("8.8.8.8")),
            TailscaleStatus.peerAddresses(status),
        )
    }

    @Test
    fun malformedOrEmptyOutputGivesNoPeers() {
        for (bad in listOf("", "not json", "[]", "{}", """{"Peer":null}""", """{"Peer":{"x":5}}""")) {
            assertEquals(emptyList(), TailscaleStatus.peerAddresses(bad), bad)
        }
    }
}
