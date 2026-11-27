package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.adb.AdbTarget
import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.adb.StartCause
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.ui.setup.SettingsReader
import io.github.devjardins.sidespeed.ui.text.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RebootRecoveryTest {
    private class FakeStore(
        initialEnabled: Boolean = true,
        initialHashes: Set<String> = setOf(sha256HexLower(TRUSTED_BSSID)),
        initialDailyFailed: Boolean = false,
    ) : RebootRecoveryStore {
        val enabledFlow = MutableStateFlow(initialEnabled)
        val hashesFlow = MutableStateFlow(initialHashes)
        val pendingFlow = MutableStateFlow(false)
        val lastFlow = MutableStateFlow<RecoveryOutcome?>(null)
        val dailyFailedFlow = MutableStateFlow(initialDailyFailed)

        override val enabled: Flow<Boolean> = enabledFlow
        override suspend fun setEnabled(v: Boolean) { enabledFlow.value = v }
        override val allowedBssidHashes: Flow<Set<String>> = hashesFlow
        override suspend fun addAllowedBssidHashes(hashes: Set<String>) { hashesFlow.value += hashes }
        override suspend fun clearAllowedBssidHashes() { hashesFlow.value = emptySet() }
        override val pendingRevokes: Flow<Boolean> = pendingFlow
        override suspend fun setPendingRevokes(v: Boolean) { pendingFlow.value = v }
        override val lastRecovery: Flow<RecoveryOutcome?> = lastFlow
        override suspend fun setLastRecovery(v: RecoveryOutcome?) { lastFlow.value = v }
        override val dailyRefreshFailed: Flow<Boolean> = dailyFailedFlow
        override suspend fun setDailyRefreshFailed(v: Boolean) { dailyFailedFlow.value = v }
    }

    private class FakePrefs(tls: Boolean = true) : Prefs {
        val tlsState = MutableStateFlow(tls)
        override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setLanWarningAcked(v: Boolean) {}
        override val pairedTls: Flow<Boolean> = tlsState
        override suspend fun setPairedTls(v: Boolean) { tlsState.value = v }
        override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(null)
        override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
        override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
        override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
        override suspend fun setSelfCompile(v: SelfCompile) {}
        override val onBoxAdbRefusedBuild: Flow<String?> = MutableStateFlow(null)
        override suspend fun setOnBoxAdbRefusedBuild(build: String?) {}
    }

    private fun buildRecovery(
        store: FakeStore = FakeStore(),
        helperUp: suspend () -> Boolean = { false },
        bssid: BssidState = BssidState.Found(TRUSTED_BSSID),
        adbWifiSetting: () -> Int = { 0 },
        writeResult: () -> Boolean = { true },
        ensureReady: suspend () -> Readiness = { Readiness.HelperReady },
        onRecovered: () -> Unit = {},
        delays: MutableList<Long> = mutableListOf(),
        isFireOs: Boolean = false,
        nowMs: () -> Long = { NOW },
    ): RebootRecovery = RebootRecovery(
        store = store,
        helperUp = helperUp,
        bssidReader = BssidReader { bssid },
        settings = SettingsReader { name -> if (name == SettingsReader.ADB_WIFI_ENABLED) adbWifiSetting() else 0 },
        secureWriter = SecureSettingsWriter { writeResult() },
        ensureReady = ensureReady,
        onHelperRecovered = onRecovered,
        isFireOs = isFireOs,
        nowMs = nowMs,
        delayFn = { delays += it },
    )

    @Test fun switchOffAtExecutionReturnsNullWithZeroWrites() = runTest {
        val store = FakeStore(initialEnabled = false)
        var writes = 0
        val r = buildRecovery(store = store, writeResult = { writes++; true }).runBootRecovery()
        assertNull(r)
        assertEquals(0, writes)
        assertNull(store.lastRecovery.first())
    }

    @Test fun fireOsReturnsNullWithZeroWrites() = runTest {
        val store = FakeStore(initialEnabled = true)
        var writes = 0
        val r = buildRecovery(store = store, isFireOs = true, writeResult = { writes++; true }).runBootRecovery()
        assertNull(r)
        assertEquals(0, writes)
    }

    @Test fun helperAlreadyUpRecordsStartedAgainWithoutWrites() = runTest {
        val store = FakeStore(initialDailyFailed = true)
        var writes = 0
        var recovered = 0
        val r = buildRecovery(
            store = store,
            helperUp = { true },
            writeResult = { writes++; true },
            onRecovered = { recovered++ },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.StartedAgain(NOW), r)
        assertEquals(RecoveryOutcome.StartedAgain(NOW), store.lastRecovery.first())
        assertFalse(store.dailyRefreshFailed.first())
        assertEquals(0, writes)
        assertEquals(1, recovered)
    }

    @Test fun locationOffFailsClosedWithZeroWrites() = runTest {
        val store = FakeStore()
        var writes = 0
        val r = buildRecovery(
            store = store,
            bssid = BssidState.LocationOff,
            writeResult = { writes++; true },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.LOCATION_OFF, NOW), r)
        assertEquals(0, writes)
    }

    @Test fun noPermissionFailsClosedWithZeroWrites() = runTest {
        val store = FakeStore()
        var writes = 0
        val r = buildRecovery(
            store = store,
            bssid = BssidState.NoPermission,
            writeResult = { writes++; true },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.NO_PERMISSION, NOW), r)
        assertEquals(0, writes)
    }

    @Test fun notOnWifiFailsClosedWithZeroWrites() = runTest {
        val store = FakeStore()
        var writes = 0
        val r = buildRecovery(
            store = store,
            bssid = BssidState.NotOnWifi,
            writeResult = { writes++; true },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.NOT_ON_WIFI, NOW), r)
        assertEquals(0, writes)
    }

    @Test fun sentinelMacFailsClosedWithZeroWrites() = runTest {
        val store = FakeStore()
        var writes = 0
        val r = buildRecovery(
            store = store,
            bssid = BssidState.Found("02:00:00:00:00:00"),
            writeResult = { writes++; true },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.NEW_NETWORK, NOW), r)
        assertEquals(0, writes)
    }

    @Test fun meshRoamUntrustedBssidFailsClosedWithZeroWrites() = runTest {
        val store = FakeStore()
        var writes = 0
        val r = buildRecovery(
            store = store,
            bssid = BssidState.Found("aa:bb:cc:dd:ee:02"),
            writeResult = { writes++; true },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.NEW_NETWORK, NOW), r)
        assertEquals(0, writes)
    }

    @Test fun writeRefusedRecordsWriteRefusedWithoutEnsureReady() = runTest {
        val store = FakeStore()
        var ensureCalls = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { 0 },
            writeResult = { false },
            ensureReady = { ensureCalls++; Readiness.HelperReady },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.WRITE_REFUSED, NOW), r)
        assertEquals(0, ensureCalls)
    }

    @Test fun readBackZeroAfterTenSecondsRecordsPromptedWithNoEnsureReady() = runTest {
        val store = FakeStore()
        val delays = mutableListOf<Long>()
        var writes = 0
        var ensureCalls = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { 0 },
            writeResult = { writes++; true },
            ensureReady = { ensureCalls++; Readiness.HelperReady },
            delays = delays,
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.PROMPTED, NOW), r)
        assertEquals(1, writes)
        assertEquals(0, ensureCalls)
        assertEquals(listOf(RebootRecovery.WRITE_VERIFY_DELAY_MS), delays)
    }

    @Test fun adbWifiDropsToZeroOnMdnsMissRecordsPromptedWithoutRetry() = runTest {
        val store = FakeStore()
        val delays = mutableListOf<Long>()
        var writes = 0
        var ensureCalls = 0
        var wifiEnabled = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { wifiEnabled },
            writeResult = { writes++; wifiEnabled = 1; true },
            ensureReady = {
                ensureCalls++
                wifiEnabled = 0
                Readiness.NeedsHelperStart(UiText.Raw("unreachable"), StartCause.REFUSED, mdnsMiss = true)
            },
            delays = delays,
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.PROMPTED, NOW), r)
        assertEquals(1, writes)
        assertEquals(1, ensureCalls)
        assertEquals(listOf(RebootRecovery.WRITE_VERIFY_DELAY_MS), delays)
    }

    @Test fun waitingForAllowPromptRecordsPromptedWithoutRetry() = runTest {
        val store = FakeStore()
        var ensureCalls = 0
        var wifiEnabled = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { wifiEnabled },
            writeResult = { wifiEnabled = 1; true },
            ensureReady = { ensureCalls++; Readiness.WaitingForAllowPrompt },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.PROMPTED, NOW), r)
        assertEquals(1, ensureCalls)
    }

    @Test fun wirelessOffDuringNonMdnsFailureRecordsPrompted() = runTest {
        val store = FakeStore()
        var writes = 0
        var ensureCalls = 0
        var wifiEnabled = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { wifiEnabled },
            writeResult = { writes++; wifiEnabled = 1; true },
            ensureReady = {
                ensureCalls++
                wifiEnabled = 0
                Readiness.NeedsHelperStart(UiText.Raw("unreachable"), StartCause.REFUSED, mdnsMiss = false)
            },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.PROMPTED, NOW), r)
        assertEquals(1, writes)
        assertEquals(1, ensureCalls)
    }

    @Test fun mdnsMissRetriesOnceAfter60sWithoutSecondWriteAndRecovers() = runTest {
        val store = FakeStore(initialDailyFailed = true)
        val delays = mutableListOf<Long>()
        var writes = 0
        var ensureCalls = 0
        var recovered = 0
        var wifiEnabled = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { wifiEnabled },
            writeResult = { writes++; wifiEnabled = 1; true },
            ensureReady = {
                ensureCalls++
                if (ensureCalls == 1) {
                    Readiness.NeedsHelperStart(UiText.Raw("unreachable"), StartCause.REFUSED, mdnsMiss = true)
                } else {
                    Readiness.HelperReady
                }
            },
            onRecovered = { recovered++ },
            delays = delays,
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.StartedAgain(NOW), r)
        assertEquals(1, writes)
        assertEquals(2, ensureCalls)
        assertEquals(1, recovered)
        assertFalse(store.dailyRefreshFailed.first())
        assertEquals(listOf(RebootRecovery.WRITE_VERIFY_DELAY_MS, RebootRecovery.MDNS_RETRY_DELAY_MS), delays)
    }

    @Test fun mdnsMissTwiceRecordsAdbFailed() = runTest {
        val store = FakeStore()
        var writes = 0
        var ensureCalls = 0
        var wifiEnabled = 0
        val r = buildRecovery(
            store = store,
            adbWifiSetting = { wifiEnabled },
            writeResult = { writes++; wifiEnabled = 1; true },
            ensureReady = {
                ensureCalls++
                Readiness.NeedsHelperStart(UiText.Raw("unreachable"), StartCause.REFUSED, mdnsMiss = true)
            },
        ).runBootRecovery()
        assertEquals(RecoveryOutcome.Failed(RecoveryReason.ADB_FAILED, NOW), r)
        assertEquals(1, writes)
        assertEquals(2, ensureCalls)
    }

    @Test fun dailyKeyRefreshSuccessClearsFailureAndClosesAdb() = runTest {
        val store = FakeStore(initialDailyFailed = true)
        var writes = 0
        val adb = FakeAdbBackend()
        val recovery = buildRecovery(store = store, writeResult = { writes++; true })
        recovery.runDailyKeyRefresh(adb, Mutex(), FakePrefs(tls = true))
        assertFalse(store.dailyRefreshFailed.first())
        assertEquals(listOf<AdbTarget>(AdbTarget.WirelessTls), adb.connectAttempts)
        assertEquals(1, adb.closeCount)
        assertFalse(adb.isConnected)
        assertEquals(0, writes)
    }

    @Test fun dailyKeyRefreshFailureMarksFailedAndNeverWritesSetting() = runTest {
        val store = FakeStore(initialDailyFailed = false)
        var writes = 0
        val adb = FakeAdbBackend().apply { failConnect = IOException("refused") }
        val recovery = buildRecovery(store = store, writeResult = { writes++; true })
        recovery.runDailyKeyRefresh(adb, Mutex(), FakePrefs(tls = true))
        assertTrue(store.dailyRefreshFailed.first())
        assertEquals(1, adb.closeCount)
        assertEquals(0, writes)
    }

    @Test fun dailyKeyRefreshSkipsWhenDisabledFireOsOrUnpaired() = runTest {
        val adb = FakeAdbBackend()
        buildRecovery(store = FakeStore(initialEnabled = false)).runDailyKeyRefresh(adb, Mutex(), FakePrefs(tls = true))
        buildRecovery(store = FakeStore(initialEnabled = true), isFireOs = true).runDailyKeyRefresh(adb, Mutex(), FakePrefs(tls = true))
        buildRecovery(store = FakeStore(initialEnabled = true)).runDailyKeyRefresh(adb, Mutex(), FakePrefs(tls = false))
        assertTrue(adb.connectAttempts.isEmpty())
    }

    private companion object {
        const val TRUSTED_BSSID = "02:15:b2:00:00:01"
        const val NOW = 1_700_000_000_000L
    }
}
