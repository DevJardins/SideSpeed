package io.github.devjardins.sidespeed.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class AdbErrorsTest {
    @Test
    fun runCatchingCancellableCapturesOrdinaryFailures() {
        val e = IOException("x")
        assertSame(e, runCatchingCancellable { throw e }.exceptionOrNull())
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    }

    @Test
    fun runCatchingCancellableRethrowsCancellation() {
        try {
            runCatchingCancellable { throw CancellationException("c") }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun asConnectionLostMapsIoInterruptedAndIllegalState() {
        listOf(IOException(), InterruptedException(), IllegalStateException()).forEach {
            val mapped = it.asConnectionLost()
            assertTrue(mapped is AdbConnectionLost)
            assertSame(it, mapped.cause)
        }
    }

    @Test
    fun asConnectionLostKeepsCancellationAndOthers() {
        val c = CancellationException("c")
        assertSame(c, c.asConnectionLost())
        val o = RuntimeException()
        assertSame(o, o.asConnectionLost())
    }
}
