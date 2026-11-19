package io.github.devjardins.sidespeed.status

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CompileStatusProviderTest {
    @Test fun serves_idle_row_before_first_refresh() {
        val rows = CompileStatusProvider().currentRows().toMap()
        assertEquals("1", rows["v"])
        assertEquals("idle", rows["state"])
        assertEquals("", rows["pkg"])
        assertEquals("0", rows["queue"])
    }

    @Test fun refresh_updates_the_served_snapshot() = runTest {
        val provider = CompileStatusProvider()
        provider.refreshSource = {
            StatusRow(
                phase = CompilePhase.COMPILING,
                activePkg = "com.example.app",
                activeLabel = "Example",
                activeMode = "speed",
                activeStartedMs = 1L,
                queueWaiting = 0,
                last = null,
                self = SelfState.PENDING,
                version = "0.5.1",
            ).toWireMap()
        }
        provider.refreshOnce()
        val rows = provider.currentRows().toMap()
        assertEquals("compiling", rows["state"])
        assertEquals("com.example.app", rows["pkg"])
    }

    @Test fun serving_never_waits_for_the_source() = runTest {
        val provider = CompileStatusProvider()
        provider.refreshSource = { awaitCancellation() }
        // The serve path must not touch the (hung) collector source at all.
        val rows = withTimeout(5_000) { provider.currentRows() }
        assertEquals("idle", rows.toMap()["state"])
    }
}
