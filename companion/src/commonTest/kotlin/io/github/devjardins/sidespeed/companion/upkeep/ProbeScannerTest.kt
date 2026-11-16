package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.discovery.FakeLan
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProbeScannerTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    @Test fun returnsOnlyClassicAdbHosts() = runTest {
        val lan = FakeLan(
            mapOf(
                ip("192.168.5.20") to listOf(ProbeResult.AdbAuthRequired),
                ip("192.168.5.21") to listOf(ProbeResult.AdbOpen),
                ip("192.168.5.22") to listOf(ProbeResult.AdbTls),
                ip("192.168.5.23") to listOf(ProbeResult.NotAdb),
            ),
        )
        assertEquals(
            setOf("192.168.5.20", "192.168.5.21"),
            ProbeScanner(lan).scan("192.168.5.0/24", 5555, null).toSet(),
        )
    }

    @Test fun startsNearTheGivenAddress() = runTest {
        val lan = FakeLan()
        ProbeScanner(lan).scan("192.168.4.0/22", 5555, "192.168.6.50")
        assertTrue(lan.calls.first().address.toString().startsWith("192.168.6."))
    }

    @Test fun neverProbesOutsideTheSubnetOrOffPort() = runTest {
        val lan = FakeLan(
            mapOf(ip("192.168.5.20") to listOf(ProbeResult.AdbOpen)),
        )
        assertTrue(ProbeScanner(lan).scan("100.64.0.0/16").isEmpty())
        assertTrue(ProbeScanner(lan).scan("192.168.5.0/24", port = 22).isEmpty())
        assertTrue(ProbeScanner(lan).scan("garbage").isEmpty())
        assertTrue(lan.calls.isEmpty())
    }

    @Test fun aNearOutsideTheSubnetIsIgnored() = runTest {
        val lan = FakeLan()
        ProbeScanner(lan).scan("192.168.5.0/24", 5555, "10.0.0.1")
        assertTrue(lan.calls.isNotEmpty())
        assertTrue(lan.calls.all { it.address.toString().startsWith("192.168.5.") })
    }
}
