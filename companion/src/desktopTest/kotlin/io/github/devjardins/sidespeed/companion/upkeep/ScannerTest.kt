package io.github.devjardins.sidespeed.companion.upkeep

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerTest {
    @Test fun findsLocalListener() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val found = TcpScanner(connectTimeoutMs = 200).scan("127.0.0.0/24", server.localPort)
            assertTrue("127.0.0.1" in found, "found: $found")
        }
    }

    @Test fun unnormalisedSlash24IsAccepted() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val found = TcpScanner(connectTimeoutMs = 200).scan("127.0.0.9/24", server.localPort)
            assertTrue("127.0.0.1" in found, "found: $found")
        }
    }

    @Test fun scansEveryHostOfASlash22() = runBlocking {
        // 127.0.2.1 is outside the /24 of 127.0.0.0 but inside 127.0.0.0/22.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val found = TcpScanner(connectTimeoutMs = 200).scan("127.0.0.0/22", server.localPort)
            assertTrue("127.0.0.1" in found, "found: $found")
        }
    }

    @Test fun refusesSubnetsOutsideSlash16To30OrMalformed() = runBlocking {
        assertEquals(emptyList(), TcpScanner().scan("10.0.0.0/15"))
        assertEquals(emptyList(), TcpScanner().scan("10.0.0.0/8"))
        assertEquals(emptyList(), TcpScanner().scan("192.168.1.4/31"))
        assertEquals(emptyList(), TcpScanner().scan("192.168.1.4/32"))
        assertEquals(emptyList(), TcpScanner().scan("nonsense"))
    }

    @Test fun acceptsSlash16AndFindsLocalListener() = runBlocking {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val found = TcpScanner(connectTimeoutMs = 200).scan("127.0.0.0/16", server.localPort)
            assertTrue("127.0.0.1" in found, "found: $found")
        }
    }

    @Test fun probeTargetsAreAscendingUsableHostsCappedAt4096() {
        assertEquals(254, TcpScanner.probeTargets("10.1.2.0/24").size)
        assertEquals(1022, TcpScanner.probeTargets("10.1.0.0/22").size)
        assertEquals(4094, TcpScanner.probeTargets("10.1.0.0/20").size)
        assertEquals(4096, TcpScanner.probeTargets("10.0.0.0/16").size)
        assertEquals(2, TcpScanner.probeTargets("192.168.1.0/30").size)
        val slash16 = TcpScanner.probeTargets("10.0.0.0/16")
        assertEquals("10.0.0.1", slash16.first())
        assertEquals(slash16.sortedBy { it.split('.').fold(0L) { acc, o -> (acc shl 8) or o.toLong() } }, slash16)
        for (bad in listOf("10.0.0.0/15", "10.0.0.0/8", "192.168.1.4/31", "192.168.1.4/32", "nonsense")) {
            assertTrue(TcpScanner.probeTargets(bad).isEmpty(), bad)
        }
    }
}
