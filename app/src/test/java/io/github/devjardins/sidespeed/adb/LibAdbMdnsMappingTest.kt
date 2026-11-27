package io.github.devjardins.sidespeed.adb

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LibAdbMdnsMappingTest {
    @Test fun activeCoroutineInterruptedExceptionMapsToMdnsMiss() {
        val src = InterruptedException("Timed out while trying to find a valid host address and port")
        val mapped = mapTlsConnectFailure(src, coroutineActive = true)
        assertTrue(mapped is AdbMdnsMissException)
        assertSame(src, mapped.cause)
    }

    @Test fun cancelledCoroutineInterruptedExceptionIsPreserved() {
        val src = InterruptedException("cancelled")
        val mapped = mapTlsConnectFailure(src, coroutineActive = false)
        assertSame(src, mapped)
    }

    @Test fun validHostAddressIoExceptionMapsToMdnsMiss() {
        val src = IOException("Could not find any valid host address or port")
        val mapped = mapTlsConnectFailure(src, coroutineActive = true)
        assertTrue(mapped is AdbMdnsMissException)
        assertSame(src, mapped.cause)
    }

    @Test fun unrelatedIoExceptionIsUnchanged() {
        val src = IOException("handshake")
        val mapped = mapTlsConnectFailure(src, coroutineActive = true)
        assertSame(src, mapped)
    }
}
