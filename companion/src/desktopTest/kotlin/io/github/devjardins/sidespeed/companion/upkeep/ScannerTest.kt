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

    @Test fun onlySlash24() = runBlocking {
        assertEquals(emptyList(), TcpScanner().scan("10.0.0.0/16"))
        assertEquals(emptyList(), TcpScanner().scan("10.0.0.0/25"))
        assertEquals(emptyList(), TcpScanner().scan("nonsense"))
    }
}
