package io.github.devjardins.sidespeed.maintenance

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMaintenanceStoreTest {
    @Test fun defaultsAreOffAndEmpty() = runTest {
        val s: MaintenanceStore = FakeMaintenanceStore()
        assertTrue(s.maintained.first().isEmpty())
        assertFalse(s.optimizeSideloaded.first())
        assertTrue(s.history.first().isEmpty())
    }

    @Test fun setMaintainedAddsAndRemoves() = runTest {
        val s: MaintenanceStore = FakeMaintenanceStore()
        s.setMaintained("a.b", true)
        s.setMaintained("c.d", true)
        s.setMaintained("a.b", false)
        assertEquals(setOf("c.d"), s.maintained.first())
    }
}
