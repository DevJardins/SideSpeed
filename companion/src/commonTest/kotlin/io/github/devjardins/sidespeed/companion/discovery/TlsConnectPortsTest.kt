package io.github.devjardins.sidespeed.companion.discovery

import com.flyfishxu.kadb.mdns.KadbMdns
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.mdns.MdnsStatus
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun aSerialMatchesOnlyTheNameThatStartsWithAdbSerialAndAHyphen() {
        // ABC is listed first, so a prefix or substring match would hand AB the wrong TV.
        val services = listOf(
            TlsConnectService("192.168.5.11", 200, "adb-ABC-suffix"),
            TlsConnectService("192.168.5.10", 100, "adb-AB-suffix"),
            TlsConnectService("192.168.5.14", 400, "adb-AB"),
        )
        assertEquals("192.168.5.10", serviceForSerial("AB", services)?.host)
        assertEquals(100, serviceForSerial("AB", services)?.port)
        assertEquals("192.168.5.11", serviceForSerial("ABC", services)?.host)
        assertNull(serviceForSerial("A", services))
        assertNull(serviceForSerial("", services))
    }

    @Test fun severalServicesUseOnlyTheOneNamedForThatSerial() {
        val services = listOf(
            TlsConnectService("192.168.5.88", 38_001, "adb-SER-aaaaaa-cccccc"),
            TlsConnectService("10.0.0.8", 38_002, "adb-OTHER-aaaaaa"),
            TlsConnectService("192.168.5.13", 38_003, "not-a-connect-service"),
            TlsConnectService("192.168.5.77", 37_111, "adb-SER-a-bbbbbb"),
            TlsConnectService("192.168.5.78", 37_112, "adb-SER-a-bbbbbb"),
        )
        assertEquals("192.168.5.77", serviceForSerial("SER-a", services)?.host)
        assertEquals(37_111, serviceForSerial("SER-a", services)?.port)
        assertEquals("192.168.5.88", serviceForSerial("SER-aaaaaa", services)?.host)
        assertEquals("10.0.0.8", serviceForSerial("OTHER", services)?.host)
        assertNull(serviceForSerial("nope", services))
    }

    @Test fun theBrowserKeepsTheInstanceName() = runBlocking {
        val endpoint = MdnsEndpoint("adb-SER-1-abcdef", "192.168.5.20", 37_001, MdnsServiceType.TLS_CONNECT)
        val ports = KadbMdnsConnectPorts(open = { ScriptedMdns(listOf(endpoint)) }, timeoutMs = 0)
        val seen = ports.servicesFor("192.168.5.99")
        assertEquals(listOf(TlsConnectService("192.168.5.20", 37_001, "adb-SER-1-abcdef")), seen)
        assertEquals("192.168.5.20", serviceForSerial("SER-1", seen ?: emptyList())?.host)
    }

    private class ScriptedMdns(endpoints: List<MdnsEndpoint>) : KadbMdns {
        override val state = MutableStateFlow(
            MdnsDiscoveryState(MdnsStatus.STARTED, false, endpoints, emptyList()),
        )
        override fun start() {}
        override fun stop() {}
    }
}
