package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun skipsSharedInNodesAndPeersThatAreNotAndroid() {
        val json = """
            {"Peer":{
              "a":{"TailscaleIPs":["100.101.0.1"],"OS":"android","Online":true},
              "b":{"TailscaleIPs":["100.101.0.2"],"OS":"Android","Online":true},
              "c":{"TailscaleIPs":["100.101.0.3"],"OS":"macOS","Online":true},
              "d":{"TailscaleIPs":["100.101.0.4"],"OS":"android","Online":true,"ShareeNode":true},
              "e":{"TailscaleIPs":["100.101.0.5"],"OS":"","Online":true},
              "f":{"TailscaleIPs":["100.101.0.6"],"Online":true,"ShareeNode":false}
            }}
        """.trimIndent()
        assertEquals(
            listOf(ip("100.101.0.1"), ip("100.101.0.2"), ip("100.101.0.5"), ip("100.101.0.6")),
            TailscaleStatus.peerAddresses(json),
        )
    }

    @Test
    fun capsHowManyPeersAreListed() {
        val peers = (1..400).joinToString(",") { """"n$it":{"TailscaleIPs":["100.102.${it / 250}.${it % 250 + 1}"],"OS":"android"}""" }
        assertEquals(TailscaleStatus.MAX_PEERS, TailscaleStatus.peerAddresses("""{"Peer":{$peers}}""").size)
    }
}

class TailscaleSnapshotTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    private val running = """
        {"Version":"1.80.0","BackendState":"Running","TUN":true,
         "CurrentTailnet":{"Name":"me@example.com","MagicDNSSuffix":"tail1234.ts.net"},
         "Self":{"ID":"nSELF","HostName":"mac","TailscaleIPs":["100.100.1.1","fd7a:115c:a1e0::1"],"Online":true},
         "Peer":{
           "nodekey:aaa":{"ID":"nCUBE","HostName":"cube","OS":"android","TailscaleIPs":["100.101.102.103","fd7a:115c:a1e0::2"],"Online":true},
           "nodekey:bbb":{"ID":"nSHARED","HostName":"friend","OS":"android","TailscaleIPs":["100.101.102.104"],"Online":true,"ShareeNode":true},
           "nodekey:ccc":{"HostName":"no-id","TailscaleIPs":["100.101.102.105"]},
           "nodekey:ddd":{"ID":"nASLEEP","HostName":"tv2","TailscaleIPs":["100.101.102.106"],"Online":false}
         }}
    """.trimIndent()

    @Test fun readsTheTailnetSelfAndPeersKeepingOnlyIpv4() {
        val s = assertNotNull(TailscaleStatus.snapshot(running))
        assertEquals("me@example.com", s.tailnet)
        assertEquals("nSELF", s.selfId)
        assertEquals(listOf(ip("100.100.1.1")), s.selfIps)
        assertTrue(s.running && s.tun)
        val cube = s.peers.first { it.id == "nCUBE" }
        assertEquals(listOf(ip("100.101.102.103")), cube.ips)
        assertEquals("android", cube.os)
        assertTrue(cube.online && !cube.shared)
        assertTrue(s.peers.first { it.id == "nSHARED" }.shared)
        assertFalse(s.peers.first { it.id == "nASLEEP" }.online)
    }

    @Test fun peersWithoutAnIdAreDropped() {
        assertEquals(setOf("nCUBE", "nSHARED", "nASLEEP"), TailscaleStatus.snapshot(running)!!.peers.map { it.id }.toSet())
    }

    @Test fun notRunningOrUserspaceIsReportedNotHidden() {
        val stopped = TailscaleStatus.snapshot(running.replace("\"Running\"", "\"Stopped\""))!!
        assertFalse(stopped.running)
        val userspace = TailscaleStatus.snapshot(running.replace("\"TUN\":true", "\"TUN\":false"))!!
        assertFalse(userspace.tun)
        val missingTun = TailscaleStatus.snapshot(running.replace("\"TUN\":true,", ""))!!
        assertFalse(missingTun.tun, "an unreported TUN is not trusted")
    }

    @Test fun missingIdentityOrGarbageGivesNull() {
        assertNull(TailscaleStatus.snapshot(running.replace("\"CurrentTailnet\"", "\"Other\"")))
        assertNull(TailscaleStatus.snapshot(running.replace("\"ID\":\"nSELF\",", "")))
        for (bad in listOf("", "not json", "[]", "{}", """{"Self":5}""")) assertNull(TailscaleStatus.snapshot(bad), bad)
    }
}
