package io.github.devjardins.sidespeed.ui.setup

import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs(acked: Boolean) : Prefs {
        val ack = MutableStateFlow(acked)
        val tls = MutableStateFlow(false)
        override val lanWarningAcked: Flow<Boolean> = ack
        override suspend fun setLanWarningAcked(v: Boolean) { ack.value = v }
        override val pairedTls: Flow<Boolean> = tls
        override suspend fun setPairedTls(v: Boolean) { tls.value = v }
        override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(null)
        override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
    }

    private class FakeReadier(val first: Readiness, val restarted: Readiness = Readiness.HelperReady) : Readier {
        var ensureCalls = 0
        var restartCalls = 0
        override suspend fun ensureReady(): Readiness { ensureCalls++; return first }
        override suspend fun restartHelper(): Readiness { restartCalls++; return restarted }
    }

    private fun vm(
        readier: Readier,
        adb: FakeAdbBackend = FakeAdbBackend(),
        prefs: FakePrefs = FakePrefs(true),
        fire: Boolean = false,
        ip: String? = "10.0.0.5",
    ) = SetupViewModel(readier, adb, prefs, fire, { ip }, dispatcher)

    private val needsStart = Readiness.NeedsHelperStart("x")

    @Test fun unackedWarningBlocksCheck() = runTest(timeout = 10.seconds) {
        val r = FakeReadier(Readiness.HelperReady)
        val v = vm(r, prefs = FakePrefs(false))
        v.start(); advanceUntilIdle(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SetupUiState.NeedsWarningAck, v.state.value)
        assertEquals(0, r.ensureCalls)
    }

    @Test fun ackThenChecks() = runTest(timeout = 10.seconds) {
        val r = FakeReadier(Readiness.HelperReady)
        val prefs = FakePrefs(false)
        val v = vm(r, prefs = prefs)
        v.acknowledgeWarning(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(prefs.ack.value)
        assertEquals(1, r.ensureCalls)
        assertEquals(SetupUiState.Ready, v.state.value)
    }

    @Test fun nonReadyMapsToResult() = runTest(timeout = 10.seconds) {
        val v = vm(FakeReadier(needsStart))
        v.start(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SetupUiState.Result(needsStart), v.state.value)
    }

    @Test fun staleRestartsOnGoogleTvOnly() = runTest(timeout = 10.seconds) {
        val tv = FakeReadier(Readiness.HelperNeedsRestart)
        val v = vm(tv)
        v.start(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, tv.restartCalls)
        assertEquals(SetupUiState.Ready, v.state.value)

        val fire = FakeReadier(Readiness.HelperNeedsRestart)
        val f = vm(fire, fire = true)
        f.start(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, fire.restartCalls)
        assertEquals(SetupUiState.Result(Readiness.HelperNeedsRestart), f.state.value)
    }

    private fun TestScope.resultVm(adb: FakeAdbBackend, prefs: FakePrefs, ip: String? = "10.0.0.5"): SetupViewModel {
        val v = vm(FakeReadier(needsStart), adb, prefs, ip = ip)
        v.start(); dispatcher.scheduler.advanceUntilIdle()
        return v
    }

    @Test fun pairValidation() = runTest(timeout = 10.seconds) {
        val prefs = FakePrefs(true)
        val v = resultVm(FakeAdbBackend(), prefs)
        v.pair("123", "4000"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Enter the 6-digit code and the pairing port.", (v.state.value as SetupUiState.Result).pairMessage)
        v.pair("123456", ""); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Enter the 6-digit code and the pairing port.", (v.state.value as SetupUiState.Result).pairMessage)
        assertTrue(!prefs.tls.value)
    }

    @Test fun pairSuccessSetsTlsAndRechecks() = runTest(timeout = 10.seconds) {
        val prefs = FakePrefs(true)
        val readier = FakeReadier(needsStart)
        val v = vm(readier, prefs = prefs)
        v.start(); dispatcher.scheduler.advanceUntilIdle()
        v.pair("123456", "37000"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(prefs.tls.value)
        assertEquals(2, readier.ensureCalls)
    }

    @Test fun pairFailureDoesNotSetTls() = runTest(timeout = 10.seconds) {
        val prefs = FakePrefs(true)
        val adb = FakeAdbBackend().apply { pairResult = Result.failure(RuntimeException("bad code")) }
        val v = resultVm(adb, prefs)
        v.pair("123456", "37000"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(!prefs.tls.value)
        assertEquals("Pairing failed: bad code", (v.state.value as SetupUiState.Result).pairMessage)
    }

    @Test fun pairFallsBackToWifiIp() = runTest(timeout = 10.seconds) {
        val prefs = FakePrefs(true)
        val adb = FakeAdbBackend().apply {
            pairResult = Result.failure(RuntimeException("loopback refused"))
            pairResultFor["10.0.0.5"] = Result.success(Unit)
        }
        val v = resultVm(adb, prefs)
        v.pair("123456", "37000"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("127.0.0.1", "10.0.0.5"), adb.pairHosts)
        assertTrue(prefs.tls.value)
    }

    @Test fun pairWithoutWifiIpFailsAfterLoopback() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { pairResult = Result.failure(RuntimeException("no")) }
        val prefs = FakePrefs(true)
        val v = resultVm(adb, prefs, ip = null)
        v.pair("123456", "37000"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("127.0.0.1"), adb.pairHosts)
        assertTrue(!prefs.tls.value)
    }

    @Test fun hangingPairTimesOut() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { hangPair = true }
        val prefs = FakePrefs(true)
        val v = resultVm(adb, prefs)
        v.pair("123456", "37000"); dispatcher.scheduler.advanceUntilIdle()
        val s = v.state.value as SetupUiState.Result
        assertEquals("Pairing timed out", s.pairMessage)
        assertEquals(false, s.pairing)
        assertTrue(!prefs.tls.value)
        assertEquals(SetupViewModel.PAIR_TIMEOUT_MS, dispatcher.scheduler.currentTime)
    }

    @Test fun checkAgainLeavesReadyImmediately() = runTest(timeout = 10.seconds) {
        val v = vm(FakeReadier(Readiness.HelperReady))
        v.start(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SetupUiState.Ready, v.state.value)
        v.checkAgain()
        assertEquals(SetupUiState.Checking, v.state.value)
    }
}
