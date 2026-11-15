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

    @Test fun ackFileSitsBesideThePortFile() {
        assertEquals("/data/local/tmp/sidespeed_helper.ack", HelperProtocol.ACK_FILE)
    }

    @Test fun portRangeIsTenPorts() {
        assertEquals(47_310, HELPER_PORT_FIRST)
        assertEquals(47_319, HELPER_PORT_LAST)
        assertEquals(1, HELPER_PROTOCOL_VERSION)
    }
}
