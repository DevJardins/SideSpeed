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

    @Test fun refusesSubnetsLargerThanSlash22OrMalformed() = runBlocking {
        assertEquals(emptyList(), TcpScanner().scan("10.0.0.0/16"))
        assertEquals(emptyList(), TcpScanner().scan("10.0.0.0/21"))
        assertEquals(emptyList(), TcpScanner().scan("nonsense"))
    }
}
