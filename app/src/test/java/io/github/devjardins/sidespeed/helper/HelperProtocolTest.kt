package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelperProtocolTest {
    @Test fun parseEndZero() = assertEquals(0, HelperProtocol.parseEnd("__END__:0"))

    @Test fun parseEndNotANumber() = assertNull(HelperProtocol.parseEnd("__END__:x"))

    @Test fun parseEndOrdinaryLine() = assertNull(HelperProtocol.parseEnd("Success"))

    @Test fun parseEndOtherCodes() {
        assertEquals(1, HelperProtocol.parseEnd("__END__:1"))
        assertEquals(HelperProtocol.EXIT_REFUSED, HelperProtocol.parseEnd(HelperProtocol.END + HelperProtocol.EXIT_REFUSED))
        assertNull(HelperProtocol.parseEnd("__END__:"))
        assertNull(HelperProtocol.parseEnd("x__END__:0"))
    }

    @Test fun ackDirSitsBesideThePortFile() {
        assertEquals("/data/local/tmp/sidespeed_helper.acks", HelperProtocol.ACK_DIR)
    }

    @Test fun portRangeIsTenPorts() {
        assertEquals(47_310, HELPER_PORT_FIRST)
        assertEquals(47_319, HELPER_PORT_LAST)
        assertEquals(2, HELPER_PROTOCOL_VERSION)
    }

    @Test fun provenIsTheFirstLineOfEveryProvenReply() {
        assertEquals("__PROVEN__", HelperProtocol.PROVEN)
    }

    @Test fun requestLineFitsNoncePlusSpacePlusCommand() {
        assertEquals(
            HelperProtocol.MAX_REQUEST_CHARS + 64 + 1,
            HelperProtocol.MAX_REQUEST_LINE_CHARS,
        )
    }

    @Test fun splitNonceTakesNonceAndRest() {
        val nonce = "abcdefghijklmnop1234"
        assertEquals(nonce to "ping", HelperProtocol.splitNonce("$nonce ping"))
        assertEquals(nonce to "dumpsys package dexopt", HelperProtocol.splitNonce("$nonce dumpsys package dexopt"))
        assertEquals("A".repeat(16) to "ping", HelperProtocol.splitNonce("${"A".repeat(16)} ping"))
        assertEquals("z9".repeat(32) to "ping", HelperProtocol.splitNonce("${"z9".repeat(32)} ping"))
    }

    @Test fun splitNonceRefusesMissingOrBadNonce() {
        listOf(
            "ping",
            "ping ",
            "short ping",
            "abcdefghijklmnop1234",
            "abcdefghijklmnop1234 ",
            " ping",
            "short ping extra",
            "a".repeat(65) + " ping",
            "a".repeat(15) + " ping",
            "abcdefghijklmnop_23 ping",
            "abcdefghijklmnop-23 ping",
        ).forEach { assertNull(it, HelperProtocol.splitNonce(it)) }
    }
}
