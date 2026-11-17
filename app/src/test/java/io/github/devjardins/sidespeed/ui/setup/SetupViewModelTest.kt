package io.github.devjardins.sidespeed.ui.setup

import androidx.lifecycle.ViewModelStore
import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.adb.StartCause
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val created = mutableListOf<SetupViewModel>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        created.forEach { it.stopRetrying() }
        Dispatchers.resetMain()
    }

    private class FakePrefs(acked: Boolean) : Prefs {
        val ack = MutableStateFlow(acked)
        val tls = MutableStateFlow(false)
        override val lanWarningAcked: Flow<Boolean> = ack
        override suspend fun setLanWarningAcked(v: Boolean) { ack.value = v }
        override val pairedTls: Flow<Boolean> = tls
        override suspend fun setPairedTls(v: Boolean) { tls.value = v }
        override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(null)
        override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
        override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
        override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
        override suspend fun setSelfCompile(v: SelfCompile) {}
    }

    /**
     * Returns [results] in order (the last one repeats). Each call takes [attemptMs] of virtual time and
     * cannot be cancelled while it runs, like libadb's blocking connect; it tracks overlapping calls.
     */
    private class FakeReadier(
        vararg results: Readiness,
        val restarted: Readiness = Readiness.HelperReady,
        val attemptMs: Long = 0,
        val clock: () -> Long = { 0 },
        val launchDuringAttempt: Boolean = false,
    ) : Readier {
        private val queue = results.toList()
        var ensureCalls = 0
        var restartCalls = 0
        var inFlight = 0
        var maxInFlight = 0
        val startTimes = mutableListOf<Long>()

        private suspend fun attempt(onLaunch: () -> Unit, result: () -> Readiness): Readiness {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            startTimes += clock()
            try {
                if (launchDuringAttempt) onLaunch()
                if (attemptMs > 0) withContext(NonCancellable) { delay(attemptMs) }
                return result()
            } finally {
                inFlight--
            }
        }

        override suspend fun ensureReady(onLaunch: () -> Unit): Readiness = attempt(onLaunch) {
            queue[minOf(ensureCalls++, queue.lastIndex)]
        }

        override suspend fun restartHelper(onLaunch: () -> Unit): Readiness = attempt(onLaunch) {
            restartCalls++; restarted
        }
    }

    private class FakeSettings(val values: MutableMap<String, Int> = mutableMapOf()) : SettingsReader {
        val reads = mutableListOf<String>()
        override fun getInt(name: String): Int { reads += name; return values[name] ?: 0 }
    }

    private fun vm(
        readier: Readier,
        adb: FakeAdbBackend = FakeAdbBackend(),
        prefs: FakePrefs = FakePrefs(true),
        fire: Boolean = false,
        ip: String? = "10.0.0.5",
        settings: SettingsReader = FakeSettings(),
    ) = SetupViewModel(readier, adb, prefs, fire, { ip }, settings, dispatcher).also { created += it }

    private val needsStart = Readiness.NeedsHelperStart("x")
    private val refused = Readiness.NeedsHelperStart("ADB not reachable", StartCause.REFUSED)
    private val wifiOn = mutableMapOf(SettingsReader.ADB_WIFI_ENABLED to 1)

    private fun connecting(v: SetupViewModel) = v.state.value as SetupUiState.Connecting

    /**
     * runTest drains the scheduler after the body, which never ends while a retry loop is alive, so loops
     * are stopped inside the body (an @After would run too late).
     */
    private fun runSetup(block: suspend TestScope.() -> Unit) = runTest(timeout = 10.seconds) {
        try {
            block()
        } finally {
            created.forEach { it.stopRetrying() }
        }
    }

    /** Runs what is due now, without advancing virtual time (the retry loop never goes idle). */
    private fun settle() = scheduler.runCurrent()

    @Test fun unackedWarningBlocksCheck() = runSetup {
        val r = FakeReadier(Readiness.HelperReady)
        val v = vm(r, prefs = FakePrefs(false))
        v.start(); settle()
        assertEquals(SetupUiState.NeedsWarningAck, v.state.value)
        assertEquals(0, r.ensureCalls)
    }

    @Test fun ackThenChecks() = runSetup {
        val r = FakeReadier(Readiness.HelperReady)
        val prefs = FakePrefs(false)
        val v = vm(r, prefs = prefs)
        v.acknowledgeWarning(); settle()
        assertTrue(prefs.ack.value)
        assertEquals(1, r.ensureCalls)
        assertEquals(SetupUiState.Ready, v.state.value)
    }

    @Test fun nonReadyShowsConnectingWithLastResult() = runSetup {
        val v = vm(FakeReadier(needsStart))
        v.start(); settle()
        val s = connecting(v)
        assertEquals(needsStart, s.readiness)
        assertTrue(s.waiting)
    }

    @Test fun staleRestartsOnGoogleTvOnly() = runSetup {
        val tv = FakeReadier(Readiness.HelperNeedsRestart)
        val v = vm(tv)
        v.start(); settle()
        assertEquals(1, tv.restartCalls)
        assertEquals(SetupUiState.Ready, v.state.value)

        val fire = FakeReadier(Readiness.HelperNeedsRestart)
        val f = vm(fire, fire = true)
        f.start(); settle()
        assertEquals(0, fire.restartCalls)
        assertEquals(Readiness.HelperNeedsRestart, connecting(f).readiness)
    }

    // --- retry loop ---

    @Test fun loopRetriesUntilReadyWithTwoSecondPauses() = runSetup {
        val r = FakeReadier(
            Readiness.WaitingForAllowPrompt, Readiness.WaitingForAllowPrompt, Readiness.HelperReady,
            clock = { scheduler.currentTime },
        )
        val v = vm(r)
        v.start(); settle()
        assertEquals(1, r.ensureCalls)
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS - 1); settle()
        assertEquals(1, r.ensureCalls)
        scheduler.advanceTimeBy(1); settle()
        assertEquals(2, r.ensureCalls)
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertEquals(3, r.ensureCalls)
        assertEquals(SetupUiState.Ready, v.state.value)
        assertEquals(listOf(0L, 2_000L, 4_000L), r.startTimes)
    }

    @Test fun loopStopsWhenReady() = runSetup {
        val r = FakeReadier(Readiness.WaitingForAllowPrompt, Readiness.HelperReady)
        val v = vm(r)
        v.start()
        scheduler.advanceUntilIdle() // terminates only because the loop ends
        assertEquals(SetupUiState.Ready, v.state.value)
        assertEquals(2, r.ensureCalls)
    }

    @Test fun pendingAttemptIsNeverOverlapped() = runSetup {
        // Each attempt blocks 30 s (a pending "Allow debugging?" prompt) and ignores cancellation.
        val r = FakeReadier(Readiness.WaitingForAllowPrompt, attemptMs = 30_000, clock = { scheduler.currentTime })
        val v = vm(r)
        v.start(); settle()
        assertEquals(1, r.inFlight)
        // Restarts while the attempt is pending must wait for it rather than start a second connect.
        scheduler.advanceTimeBy(5_000); v.checkAgain(); settle()
        scheduler.advanceTimeBy(5_000); v.start(); settle()
        scheduler.advanceTimeBy(5_000); v.showPairing(); v.pair("123456", "37000"); settle()
        scheduler.advanceTimeBy(5 * 60_000L); settle()
        assertEquals(1, r.maxInFlight)
        assertTrue(r.ensureCalls >= 3)
        // Sequential: every attempt starts at least attemptMs after the previous one started.
        r.startTimes.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b - a >= 30_000) }
    }

    @Test fun jobCancelledBeforeItStartsStillWaitsForPendingAttempt() = runSetup {
        val r = FakeReadier(Readiness.WaitingForAllowPrompt, attemptMs = 30_000, clock = { scheduler.currentTime })
        val v = vm(r)
        v.start(); settle()
        scheduler.advanceTimeBy(5_000)
        // checkAgain's job is replaced before it is ever dispatched; the chain must still hold.
        v.checkAgain(); v.start(); settle()
        scheduler.advanceTimeBy(2 * 60_000L); settle()
        assertEquals(1, r.maxInFlight)
        r.startTimes.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b - a >= 30_000) }
    }

    @Test fun pairingCancelledByLeavingIsResetOnReturn() = runSetup {
        val r = FakeReadier(Readiness.WaitingForAllowPrompt, attemptMs = 30_000)
        val v = vm(r)
        v.start(); settle()
        v.showPairing()
        scheduler.advanceTimeBy(5_000)
        v.pair("123456", "37000"); settle()
        assertTrue(connecting(v).pairing)
        assertEquals(SetupViewModel.WAITING_FOR_CHECK, connecting(v).pairMessage)
        v.stopRetrying() // Home, or Settings to look at Paired devices
        scheduler.advanceTimeBy(60_000); settle()
        v.start(); settle()
        assertFalse(connecting(v).pairing)
        assertTrue(connecting(v).pairMessage == null)
    }

    @Test fun pairingCancelledMidPairIsReset() = runSetup {
        val adb = FakeAdbBackend().apply { hangPair = true }
        val v = vm(FakeReadier(needsStart), adb)
        v.start(); settle()
        v.pair("123456", "37000"); settle()
        assertEquals("Pairing…", connecting(v).pairMessage)
        v.stopRetrying(); settle()
        assertFalse(connecting(v).pairing)
    }

    @Test fun pairTimeoutStartsWhenPairingBegins() = runSetup {
        val adb = FakeAdbBackend().apply { hangPair = true }
        val r = FakeReadier(Readiness.WaitingForAllowPrompt, attemptMs = 20_000)
        val v = vm(r, adb)
        v.start(); settle()
        v.pair("123456", "37000"); settle()
        assertEquals(SetupViewModel.WAITING_FOR_CHECK, connecting(v).pairMessage)
        scheduler.advanceTimeBy(20_000); settle()
        assertEquals("Pairing…", connecting(v).pairMessage)
        scheduler.advanceTimeBy(SetupViewModel.PAIR_TIMEOUT_MS - 1); settle()
        assertTrue(connecting(v).pairing)
        scheduler.advanceTimeBy(1); settle()
        assertEquals("Pairing timed out", connecting(v).pairMessage)
    }

    @Test fun startResetsRefusalCount() = runSetup {
        val r = FakeReadier(refused)
        val v = vm(r, settings = FakeSettings(wifiOn))
        v.start(); settle()
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle() // 2 refusals
        v.stopRetrying(); v.start(); settle() // 1 since restart
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle() // 2
        assertFalse(connecting(v).refusedHint)
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle() // 3
        assertTrue(connecting(v).refusedHint)
    }

    @Test fun loopStopsWhenScreenIsLeft() = runSetup {
        val r = FakeReadier(Readiness.WaitingForAllowPrompt)
        val v = vm(r)
        v.start(); settle()
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertEquals(2, r.ensureCalls)
        v.stopRetrying()
        scheduler.advanceTimeBy(60_000); settle()
        assertEquals(2, r.ensureCalls)
        // Coming back restarts it.
        v.start(); settle()
        assertEquals(3, r.ensureCalls)
    }

    @Test fun loopStopsWhenViewModelIsCleared() = runSetup {
        val r = FakeReadier(Readiness.WaitingForAllowPrompt)
        val v = vm(r)
        val store = ViewModelStore().apply { put("setup", v) }
        v.start(); settle()
        assertEquals(1, r.ensureCalls)
        store.clear()
        scheduler.advanceTimeBy(60_000); settle()
        assertEquals(1, r.ensureCalls)
    }

    @Test fun launchingIsShownWhileHelperStarts() = runSetup {
        val r = FakeReadier(needsStart, attemptMs = 1_000, launchDuringAttempt = true)
        val v = vm(r)
        v.start(); settle()
        assertTrue(connecting(v).launching)
        scheduler.advanceTimeBy(1_000); settle()
        assertFalse(connecting(v).launching)
    }

    // --- refused hint ---

    @Test fun refusedHintOnlyAfterThreeConsecutiveRefusals() = runSetup {
        val r = FakeReadier(refused, refused, Readiness.WaitingForAllowPrompt, refused, refused, refused)
        val v = vm(r, settings = FakeSettings(wifiOn))
        v.start(); settle()
        repeat(4) {
            assertFalse("attempt ${r.ensureCalls}", connecting(v).refusedHint)
            scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        }
        assertEquals(5, r.ensureCalls)
        assertFalse(connecting(v).refusedHint) // only 2 since the reset
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertEquals(6, r.ensureCalls)
        assertTrue(connecting(v).refusedHint)
    }

    @Test fun refusedHintNeedsWirelessDebuggingOn() = runSetup {
        val r = FakeReadier(refused)
        val v = vm(r, settings = FakeSettings())
        v.start(); settle()
        repeat(5) { scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle() }
        assertFalse(connecting(v).refusedHint)
    }

    @Test fun nonRefusedFailureDoesNotCount() = runSetup {
        val r = FakeReadier(Readiness.NeedsHelperStart("ADB not reachable", StartCause.OTHER))
        val v = vm(r, settings = FakeSettings(wifiOn))
        v.start(); settle()
        repeat(5) { scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle() }
        assertFalse(connecting(v).refusedHint)
    }

    // --- pads ---

    @Test fun padsHiddenByDefaultAndShownOnRequest() = runSetup {
        val r = FakeReadier(refused)
        val v = vm(r, settings = FakeSettings(wifiOn))
        v.start(); settle()
        repeat(5) { scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle() }
        assertTrue(connecting(v).refusedHint)
        assertFalse(connecting(v).showPairing) // never shown automatically
        v.showPairing()
        assertTrue(connecting(v).showPairing)
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertTrue(connecting(v).showPairing) // the loop keeps it
        v.hidePairing()
        assertFalse(connecting(v).showPairing)
    }

    // --- step detection ---

    @Test fun stepOneReadsWifiOrAdbOnGoogleTv() = runSetup {
        val settings = FakeSettings()
        val v = vm(FakeReadier(needsStart), settings = settings)
        v.start(); settle()
        assertFalse(connecting(v).detected)
        settings.values[SettingsReader.ADB_WIFI_ENABLED] = 1
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertTrue(connecting(v).detected)
        settings.values[SettingsReader.ADB_WIFI_ENABLED] = 0
        settings.values[SettingsReader.ADB_ENABLED] = 1
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertTrue(connecting(v).detected)
    }

    @Test fun stepOneUsesAdbEnabledOnFireOs() = runSetup {
        val settings = FakeSettings(mutableMapOf(SettingsReader.ADB_WIFI_ENABLED to 1))
        val v = vm(FakeReadier(needsStart), fire = true, settings = settings)
        v.start(); settle()
        assertFalse(connecting(v).detected)
        settings.values[SettingsReader.ADB_ENABLED] = 1
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertTrue(connecting(v).detected)
        assertTrue(settings.reads.none { it == SettingsReader.ADB_WIFI_ENABLED })
    }

    // --- pairing ---

    private fun TestScope.resultVm(adb: FakeAdbBackend, prefs: FakePrefs, ip: String? = "10.0.0.5"): SetupViewModel {
        val v = vm(FakeReadier(needsStart), adb, prefs, ip = ip)
        v.start(); settle()
        v.showPairing()
        return v
    }

    @Test fun pairValidation() = runSetup {
        val prefs = FakePrefs(true)
        val v = resultVm(FakeAdbBackend(), prefs)
        v.pair("123", "4000"); settle()
        assertEquals("Enter the 6-digit code and the pairing port.", connecting(v).pairMessage)
        v.pair("123456", ""); settle()
        assertEquals("Enter the 6-digit code and the pairing port.", connecting(v).pairMessage)
        assertTrue(!prefs.tls.value)
    }

    @Test fun pairSuccessSetsTlsAndRechecks() = runSetup {
        val prefs = FakePrefs(true)
        val readier = FakeReadier(needsStart)
        val v = vm(readier, prefs = prefs)
        v.start(); settle()
        v.pair("123456", "37000"); settle()
        assertTrue(prefs.tls.value)
        assertEquals(2, readier.ensureCalls)
    }

    @Test fun pairFailureDoesNotSetTls() = runSetup {
        val prefs = FakePrefs(true)
        val adb = FakeAdbBackend().apply { pairResult = Result.failure(RuntimeException("bad code")) }
        val v = resultVm(adb, prefs)
        v.pair("123456", "37000"); settle()
        assertTrue(!prefs.tls.value)
        assertEquals("Pairing failed: bad code", connecting(v).pairMessage)
        // The message survives the next retry.
        scheduler.advanceTimeBy(SetupViewModel.RETRY_DELAY_MS); settle()
        assertEquals("Pairing failed: bad code", connecting(v).pairMessage)
        assertTrue(connecting(v).showPairing)
    }

    @Test fun pairFallsBackToWifiIp() = runSetup {
        val prefs = FakePrefs(true)
        val adb = FakeAdbBackend().apply {
            pairResult = Result.failure(RuntimeException("loopback refused"))
            pairResultFor["10.0.0.5"] = Result.success(Unit)
        }
        val v = resultVm(adb, prefs)
        v.pair("123456", "37000"); settle()
        assertEquals(listOf("127.0.0.1", "10.0.0.5"), adb.pairHosts)
        assertTrue(prefs.tls.value)
    }

    @Test fun pairWithoutWifiIpFailsAfterLoopback() = runSetup {
        val adb = FakeAdbBackend().apply { pairResult = Result.failure(RuntimeException("no")) }
        val prefs = FakePrefs(true)
        val v = resultVm(adb, prefs, ip = null)
        v.pair("123456", "37000"); settle()
        assertEquals(listOf("127.0.0.1"), adb.pairHosts)
        assertTrue(!prefs.tls.value)
    }

    @Test fun hangingPairTimesOut() = runSetup {
        val adb = FakeAdbBackend().apply { hangPair = true }
        val prefs = FakePrefs(true)
        val v = resultVm(adb, prefs)
        val t0 = scheduler.currentTime
        v.pair("123456", "37000"); settle()
        assertTrue(connecting(v).pairing)
        scheduler.advanceTimeBy(SetupViewModel.PAIR_TIMEOUT_MS - 1); settle()
        assertTrue(connecting(v).pairing)
        scheduler.advanceTimeBy(1); settle()
        val s = connecting(v)
        assertEquals("Pairing timed out", s.pairMessage)
        assertEquals(false, s.pairing)
        assertTrue(!prefs.tls.value)
        assertEquals(t0 + SetupViewModel.PAIR_TIMEOUT_MS, scheduler.currentTime)
    }

    @Test fun checkAgainLeavesReadyImmediately() = runSetup {
        val v = vm(FakeReadier(Readiness.HelperReady))
        v.start(); settle()
        assertEquals(SetupUiState.Ready, v.state.value)
        v.checkAgain()
        assertNotEquals(SetupUiState.Ready, v.state.value)
    }

    @Test fun startLeavesStaleReadyImmediately() = runSetup {
        val v = vm(FakeReadier(Readiness.HelperReady))
        v.start(); settle()
        assertEquals(SetupUiState.Ready, v.state.value)
        v.start()
        assertNotEquals(SetupUiState.Ready, v.state.value)
    }
}
