package io.github.devjardins.sidespeed.maintenance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RebootRecoveryStoreTest {
    @Rule @JvmField
    val tempDir = TemporaryFolder()

    @Test fun defaultsAreSafeOff() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { tempDir.newFile("rr-defaults.preferences_pb") }
        val store = DataStoreRebootRecoveryStore(ds)
        assertFalse(store.enabled.first())
        assertEquals(emptySet<String>(), store.allowedBssidHashes.first())
        assertFalse(store.pendingRevokes.first())
        assertNull(store.lastRecovery.first())
        assertFalse(store.dailyRefreshFailed.first())
    }

    @Test fun roundTripsAllFields() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { tempDir.newFile("rr-rt.preferences_pb") }
        val store = DataStoreRebootRecoveryStore(ds)

        store.setEnabled(true)
        assertTrue(store.enabled.first())

        val h1 = sha256HexLower("02:15:b2:00:00:01")
        val h2 = sha256HexLower("AA:BB:CC:DD:EE:FF")
        store.addAllowedBssidHashes(setOf(h1))
        store.addAllowedBssidHashes(setOf(h2))
        assertEquals(setOf(h1, h2), store.allowedBssidHashes.first())

        store.clearAllowedBssidHashes()
        assertEquals(emptySet<String>(), store.allowedBssidHashes.first())

        store.setPendingRevokes(true)
        assertTrue(store.pendingRevokes.first())
        store.setPendingRevokes(false)
        assertFalse(store.pendingRevokes.first())

        val ok = RecoveryOutcome.StartedAgain(1_700_000_000_000L)
        store.setLastRecovery(ok)
        assertEquals(ok, store.lastRecovery.first())

        val fail = RecoveryOutcome.Failed(RecoveryReason.NEW_NETWORK, 1_700_000_123_456L)
        store.setLastRecovery(fail)
        assertEquals(fail, store.lastRecovery.first())

        store.setLastRecovery(null)
        assertNull(store.lastRecovery.first())

        store.setDailyRefreshFailed(true)
        assertTrue(store.dailyRefreshFailed.first())
        store.setDailyRefreshFailed(false)
        assertFalse(store.dailyRefreshFailed.first())
    }

    @Test fun parseRecoveryOutcomeHandlesValidAndMalformed() {
        val ok = RecoveryOutcome.StartedAgain(42L)
        assertEquals(ok, parseRecoveryOutcome(encodeRecoveryOutcome(ok)))

        for (reason in RecoveryReason.entries) {
            val f = RecoveryOutcome.Failed(reason, 999L)
            assertEquals(f, parseRecoveryOutcome(encodeRecoveryOutcome(f)))
        }

        listOf(
            null,
            "",
            "ok",
            "ok|notanumber",
            "fail|UNKNOWN_REASON|100",
            "fail|NEW_NETWORK",
            "fail|NEW_NETWORK|abc",
            "other|100",
        ).forEach { assertNull("should return null for $it", parseRecoveryOutcome(it)) }
    }

    @Test fun sha256HexLowerNormalizesCaseAndProduces64HexChars() {
        val lower = sha256HexLower("aa:bb:cc:dd:ee:ff")
        val upper = sha256HexLower("AA:BB:CC:DD:EE:FF")
        assertEquals(lower, upper)
        assertEquals(64, lower.length)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(lower))
    }

    @Test fun sha256HexLowerRejectsSentinelsAndMalformedMacs() {
        listOf(
            "02:00:00:00:00:00",
            "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff",
            "FF:FF:FF:FF:FF:FF",
            "",
            "not-a-mac",
            "aa:bb:cc:dd:ee",
        ).forEach { bad ->
            var threw = false
            try {
                sha256HexLower(bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("expected IllegalArgumentException for $bad", threw)
        }
    }
}
