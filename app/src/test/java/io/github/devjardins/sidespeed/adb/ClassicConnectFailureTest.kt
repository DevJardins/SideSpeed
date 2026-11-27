package io.github.devjardins.sidespeed.adb

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class ClassicConnectFailureTest {
    // libadb ends a handshake that adbd closed with a plain IOException("Connection failed") (Fire TV Cube, 2026-11-27).
    @Test fun closedHandshakeIsClosed() {
        assertTrue(classicConnectFailure(IOException("Connection failed")) is AdbClosedException)
    }

    @Test fun rejectedKeyIsClosed() {
        assertTrue(classicConnectFailure(AdbAuthenticationFailedException()) is AdbClosedException)
    }

    @Test fun closedPortStaysAsIs() {
        val e = ConnectException("Connection refused")
        assertSame(e, classicConnectFailure(e))
    }

    @Test fun timeoutStaysAsIs() {
        val e = SocketTimeoutException()
        assertSame(e, classicConnectFailure(e))
    }
}
