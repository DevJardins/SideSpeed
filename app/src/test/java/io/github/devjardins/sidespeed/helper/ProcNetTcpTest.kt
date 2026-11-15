package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcNetTcpTest {
    // Kernel /proc/net/tcp format (header + rows), as on the Fire TV Cube / Onn.
    private val tcpHeader =
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"
    private val tcp6Header =
        "  sl  local_address                         remote_address                        st tx_queue " +
            "rx_queue tr tm->when retrnsmt   uid  timeout inode"

    // Client socket: 127.0.0.1:0xB8AE -> helper 127.0.0.1:0xB8CE (47310), owned by uid 10255, ESTABLISHED.
    private val v4Client =
        "   3: 0100007F:B8AE 0100007F:B8CE 01 00000000:00000000 00:00000000 00000000 10255        0 " +
            "4423171 1 0000000000000000 20 4 30 10 -1"

    // Helper side of the same connection (local port is the helper's), owned by shell.
    private val v4Server =
        "   4: 0100007F:B8CE 0100007F:B8AE 01 00000000:00000000 00:00000000 00000000  2000        0 " +
            "4423172 1 0000000000000000 20 4 30 10 -1"

    // Helper listener.
    private val v4Listen =
        "   0: 0100007F:B8CE 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 " +
            "4420001 1 0000000000000000 100 0 0 10 0"

    private val v6Client =
        "   0: 0000000000000000FFFF00000100007F:B8AE 0000000000000000FFFF00000100007F:B8CE 01 " +
            "00000000:00000000 00:00000000 00000000 10255        0 4423180 1 0000000000000000 20 4 31 10 -1"

    private fun file(header: String, vararg rows: String) = (listOf(header) + rows).joinToString("\n") + "\n"

    @Test fun ipv4Entry() =
        assertEquals(10255, ProcNetTcp.ownerUid(file(tcpHeader, v4Listen, v4Client, v4Server), file(tcp6Header), 0xB8AE))

    @Test fun ipv6MappedEntry() =
        assertEquals(10255, ProcNetTcp.ownerUid(file(tcpHeader), file(tcp6Header, v6Client), 0xB8AE))

    @Test fun missingPort() =
        assertNull(ProcNetTcp.ownerUid(file(tcpHeader, v4Listen, v4Client, v4Server), file(tcp6Header), 0xB8AF))

    @Test fun malformedLinesSkipped() {
        val tcp = file(
            tcpHeader,
            "garbage",
            "",
            "   1: 0100007F:ZZZZ 0100007F:B8CE 01 00000000:00000000 00:00000000 00000000 99999",
            "   2: 0100007F:B8AE 0100007F:B8CE 01 00000000:00000000 00:00000000 00000000 notanumber 0 1",
            "   2: 0100007F:B8AE",
            "   2: 0100007F 0100007F:B8CE 01 00000000:00000000 00:00000000 00000000 1234 0 1",
            v4Client,
        )
        assertEquals(10255, ProcNetTcp.ownerUid(tcp, "\u0000\u0001 junk ::: \n", 0xB8AE))
    }

    @Test fun remotePortMustMatchWhenGiven() {
        val tcp = file(tcpHeader, v4Client)
        assertEquals(10255, ProcNetTcp.ownerUid(tcp, "", 0xB8AE, remotePort = 0xB8CE))
        assertNull(ProcNetTcp.ownerUid(tcp, "", 0xB8AE, remotePort = 0xB8CF))
    }

    @Test fun nonLoopbackLocalAddressIgnored() {
        // Same port, but local address 192.168.1.10 (0A01A8C0) and ::1 in tcp6.
        val tcp = file(
            tcpHeader,
            "   3: 0A01A8C0:B8AE 0100007F:B8CE 01 00000000:00000000 00:00000000 00000000 10300        0 1 1 0 20 4 30 10 -1",
        )
        val tcp6 = file(
            tcp6Header,
            "   0: 00000000000000000000000001000000:B8AE 0000000000000000FFFF00000100007F:B8CE 01 " +
                "00000000:00000000 00:00000000 00000000 10301        0 2 1 0 20 4 31 10 -1",
        )
        assertNull(ProcNetTcp.ownerUid(tcp, tcp6, 0xB8AE))
    }

    @Test fun listenAndTimeWaitRowsIgnored() {
        val tcp = file(
            tcpHeader,
            // A listener on the client's port number (state 0A).
            "   0: 0100007F:B8AE 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10400        0 1 1 0 100 0 0 10 0",
            // A TIME_WAIT leftover (state 06, uid 0).
            "   1: 0100007F:B8AE 0100007F:B8CE 06 00000000:00000000 03:00000F9E 00000000     0        0 0 3 0",
        )
        assertNull(ProcNetTcp.ownerUid(tcp, "", 0xB8AE))
        assertEquals(10255, ProcNetTcp.ownerUid(tcp + v4Client + "\n", "", 0xB8AE))
    }

    @Test fun conflictingOwnersRefused() {
        val other = v4Client.replace(" 10255 ", " 10999 ")
        assertNull(ProcNetTcp.ownerUid(file(tcpHeader, v4Client), file(tcp6Header, v6Client.replace(" 10255 ", " 10999 ")), 0xB8AE))
        assertNull(ProcNetTcp.ownerUid(file(tcpHeader, v4Client, other), "", 0xB8AE))
    }

    @Test fun lowercaseHexAccepted() =
        assertEquals(10255, ProcNetTcp.ownerUid(file(tcpHeader, v4Client.lowercase()), "", 0xB8AE))
}
