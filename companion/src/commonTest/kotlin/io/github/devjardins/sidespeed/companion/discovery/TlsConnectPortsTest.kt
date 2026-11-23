package io.github.devjardins.sidespeed.companion.discovery

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TlsConnectPortsTest {
    private val services = listOf(
        TlsConnectService("192.168.5.20", 37_001),
        TlsConnectService("192.168.5.21", 37_002),
        TlsConnectService("192.168.5.20", 37_009),
    )

    @Test fun theMatchingHostIsTheOnlyPortReturned() {
        assertEquals(37_001, portForHost("192.168.5.20", services))
        assertEquals(37_002, portForHost("192.168.5.21", services))
        assertNull(portForHost("192.168.5.99", services))
    }

    @Test fun theFinderAsksForThreeSecondsAndIgnoresOtherHosts() = runBlocking {
        var asked = 0L
        val finder = MdnsConnectPortFinder(
            browse = { timeout ->
                asked = timeout
                services
            },
        )
        assertEquals(37_001, finder.find("192.168.5.20"))
        assertEquals(MdnsConnectPortFinder.BROWSE_MS, asked)
        assertNull(finder.find("10.0.0.9"))
    }

    @Test fun noServicesMeansNoPort() = runBlocking {
        val finder = MdnsConnectPortFinder(browse = { emptyList() })
        assertNull(finder.find("192.168.5.20"))
    }
}
