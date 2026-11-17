package io.github.devjardins.sidespeed.companion.tv

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardedLinkTest {
    /** Models Kadb 2.1.4: close() only closes an established connection; a finished handshake establishes one. */
    private class FakeClient : AdbClient {
        val inHandshake = CountDownLatch(1)
        val allow = CountDownLatch(1)
        @Volatile var open = false
        @Volatile var closeCalls = 0
        val shells = mutableListOf<String>()

        override fun handshake() {
            inHandshake.countDown()
            check(allow.await(5, TimeUnit.SECONDS))
            open = true
        }

        override fun shell(command: String): String {
            shells += command
            return "out"
        }

        override val isOpen get() = open
        override fun close() {
            closeCalls++
            open = false
        }
    }

    @Test
    fun handshakeCompletingAfterCloseIsClosedAgain() {
        val client = FakeClient()
        val link = GuardedLink(client)
        var failure: Throwable? = null
        val t = thread { failure = runCatching { link.connect() }.exceptionOrNull() }
        assertTrue(client.inHandshake.await(5, TimeUnit.SECONDS))
        link.close() // TvAuthPending or cancellation: nothing to close yet
        assertFalse(client.open)
        client.allow.countDown() // the user taps "Allow" afterwards
        t.join(5_000)
        assertTrue(failure is IOException, "connect fails: $failure")
        assertFalse(client.open, "the late connection is closed")
        assertEquals(2, client.closeCalls)
        assertFalse(link.isConnected)
    }

    @Test
    fun shellAfterCloseIsRefusedWithoutTouchingKadb() {
        val client = FakeClient().apply { allow.countDown() }
        val link = GuardedLink(client)
        link.connect()
        assertTrue(link.isConnected)
        assertEquals("out", link.shell("getprop ro.serialno"))
        link.close()
        assertFailsWith<IOException> { link.shell("getprop ro.serialno") }
        assertEquals(listOf("getprop ro.serialno"), client.shells, "no silent reconnect via Kadb")
    }
}
