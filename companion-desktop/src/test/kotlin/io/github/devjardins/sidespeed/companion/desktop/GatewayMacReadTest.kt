package io.github.devjardins.sidespeed.companion.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayMacReadTest {
    private val table = "? (192.168.4.1) at 6c:bf:2f:26:4f:12 on en0 ifscope [ethernet]\n"

    @Test fun fullArpTableSuppliesTheMacWhenThePerHostLookupMisses() {
        val commands = mutableListOf<List<String>>()
        val mac = readGatewayMac(mac = true, windows = false, gateway = "192.168.4.1") { args ->
            commands += args.toList()
            if (args.toList() == listOf("arp", "-n", "192.168.4.1")) {
                "192.168.4.1 (192.168.4.1) -- no entry\n"
            } else {
                table
            }
        }
        assertEquals("6c:bf:2f:26:4f:12", mac)
        assertEquals(listOf(listOf("arp", "-an")), commands.filter { it == listOf("arp", "-an") })
    }

    @Test fun perHostHitDoesNotReadTheWholeTable() {
        var calls = 0
        val mac = readGatewayMac(mac = true, windows = false, gateway = "192.168.4.1") {
            calls++
            table
        }
        assertEquals("6c:bf:2f:26:4f:12", mac)
        assertEquals(1, calls)
    }
}
