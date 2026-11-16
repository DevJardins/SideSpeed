package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanHintsTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun plan(subnet: String) = DiscoveryPlan(Cidr.parse(subnet)!!)

    @Test fun lanOnlyKeepsPrivateHostsInsideThePlan() {
        assertEquals(
            listOf(ip("192.168.5.9")),
            plan("192.168.5.0/24").lanOnly(
                listOf("192.168.5.9", "192.168.6.9", "100.101.1.1", "8.8.8.8", "192.168.5.9").map { ip(it) },
            ),
        )
    }

    @Test fun mdnsQueryIsOnePtrQuestionForServiceEnumeration() {
        val q = Mdns.servicesQuery()
        assertEquals(0x0001, ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF))
        assertEquals(0x0000, ((q[2].toInt() and 0xFF) shl 8) or (q[3].toInt() and 0xFF))
        var i = 12
        val labels = mutableListOf<String>()
        while (q[i] != 0.toByte()) {
            val len = q[i].toInt() and 0xFF
            labels += q.copyOfRange(i + 1, i + 1 + len).toString(Charsets.US_ASCII)
            i += 1 + len
        }
        assertEquals(listOf("_services", "_dns-sd", "_udp", "local"), labels)
        assertContentEquals(byteArrayOf(0x00, 0x0C, 0x00, 0x01), q.copyOfRange(q.size - 4, q.size))
    }

    @Test fun mdnsResponseNeedsQrBitAndAnAnswer() {
        fun header(flagsHi: Byte, flagsLo: Byte, ancount: Int): ByteArray {
            val h = ByteArray(12)
            h[2] = flagsHi
            h[3] = flagsLo
            h[6] = (ancount shr 8).toByte()
            h[7] = ancount.toByte()
            return h
        }
        assertTrue(Mdns.isResponse(header(0x84.toByte(), 0x00, 1)))
        assertFalse(Mdns.isResponse(header(0x00, 0x00, 1)))
        assertFalse(Mdns.isResponse(header(0x84.toByte(), 0x00, 0)))
        assertFalse(Mdns.isResponse(ByteArray(5)))
    }

    @Test fun ssdpSearchIsTheStandardMSearch() {
        assertEquals(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n",
            Ssdp.mSearch().toString(Charsets.US_ASCII),
        )
    }

    @Test fun ssdpResponseStartsWithHttp200() {
        assertTrue(Ssdp.isResponse("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n".toByteArray()))
        assertFalse(Ssdp.isResponse("NOTIFY * HTTP/1.1\r\n".toByteArray()))
        assertFalse(Ssdp.isResponse(ByteArray(0)))
    }

    private class ScriptedQuery(var answers: List<Datagram> = emptyList()) : DatagramQuery {
        val calls = mutableListOf<Pair<Ipv4, Int>>()
        override suspend fun query(
            target: Ipv4,
            port: Int,
            payload: ByteArray,
            listenMs: Long,
            via: Ipv4?,
        ): List<Datagram> {
            calls += target to port
            return answers
        }
    }

    private fun mdnsResponse(from: String): Datagram {
        val h = ByteArray(12)
        h[2] = 0x84.toByte()
        h[7] = 1
        return Datagram(ip(from), h)
    }

    private fun mdnsQuery(from: String) = Datagram(ip(from), ByteArray(12))

    @Test fun mdnsSourceAsksTheMdnsGroupAndKeepsResponders() = runTest {
        val net = ScriptedQuery(listOf(mdnsResponse("192.168.5.140"), mdnsQuery("192.168.5.7")))
        assertEquals(
            listOf(ip("192.168.5.140")),
            MdnsSource(net).hosts(plan("192.168.5.0/24")),
        )
        assertEquals(listOf(ip("224.0.0.251") to 5353), net.calls)
    }

    @Test fun ssdpSourceAsksTheSsdpGroup() = runTest {
        val net = ScriptedQuery(
            listOf(Datagram(ip("192.168.5.140"), "HTTP/1.1 200 OK\r\n\r\n".toByteArray(Charsets.US_ASCII))),
        )
        assertEquals(
            listOf(ip("192.168.5.140")),
            SsdpSource(net).hosts(plan("192.168.5.0/24")),
        )
        assertEquals(listOf(ip("239.255.255.250") to 1900), net.calls)
    }

    @Test fun hintSourcesCapTheirReplies() = runTest {
        val answers = (1..400).map { n ->
            mdnsResponse("10.0.${n / 256}.${n % 256}")
        }
        assertEquals(256, MdnsSource(ScriptedQuery(answers)).hosts(plan("10.0.0.0/16")).size)
    }

    @Test fun aFailedQueryGivesNoHints() = runTest {
        assertTrue(MdnsSource(ScriptedQuery()).hosts(plan("192.168.5.0/24")).isEmpty())
        assertTrue(SsdpSource(ScriptedQuery()).hosts(plan("192.168.5.0/24")).isEmpty())
    }
}
