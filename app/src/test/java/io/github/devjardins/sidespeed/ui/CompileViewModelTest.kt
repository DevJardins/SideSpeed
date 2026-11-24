package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.adb.ShellEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileOutcome
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.status.FakeCompileStatusStore
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.testing.english
import io.github.devjardins.sidespeed.ui.compile.CompileUiState
import io.github.devjardins.sidespeed.ui.compile.CompileViewModel
import io.github.devjardins.sidespeed.ui.compile.formatDuration
import io.github.devjardins.sidespeed.ui.compile.resultBanner
import io.github.devjardins.sidespeed.ui.compile.successDetail
import io.github.devjardins.sidespeed.ui.compile.successTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CompileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pkg = PackageName.parse("com.nuvio.tv")!!
    private val cmd = "pm compile -m speed -f com.nuvio.tv"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs : Prefs {
        val saved = mutableMapOf<String, LastCompile>()
        override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setLanWarningAcked(v: Boolean) {}
        override val pairedTls: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setPairedTls(v: Boolean) {}
        override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(saved[pkg])
        override suspend fun setLastCompile(pkg: String, value: LastCompile) { saved[pkg] = value }
        override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
        override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
        override suspend fun setSelfCompile(v: SelfCompile) {}
    }

    private fun fake() = FakeAdbBackend().apply {
        on(cmd) { listOf("Success") to 0 }
        on("dumpsys package dexopt") { listOf("  [com.nuvio.tv]", "      arm64: [status=speed]") to 0 }
    }

    private fun vm(adb: FakeAdbBackend, prefs: FakePrefs = FakePrefs()) =
        CompileViewModel(CompileRunner(adb), StatusChecker(adb), prefs, dispatcher, clock = { 1234L })

    @Test fun successLogsSavesAndShowsBadge() = runTest(timeout = 10.seconds) {
        val prefs = FakePrefs()
        val v = vm(fake(), prefs)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); advanceUntilIdle(); dispatcher.scheduler.advanceUntilIdle()
        val s = v.state.value
        assertFalse(s.running)
        assertEquals(true, s.result?.success)
        assertTrue("$ $cmd" in s.log)
        assertTrue("Success" in s.log)
        assertEquals(LastCompile(1234L, CompileMode.SPEED), prefs.saved["com.nuvio.tv"])
        assertEquals(StatusBadge.SPEED_OK, s.badge)
    }

    @Test fun secondStartWhileRunningIsIgnored() = runTest(timeout = 10.seconds) {
        val adb = fake()
        val v = vm(adb)
        v.start(pkg, CompileMode.SPEED, "Nuvio")
        assertTrue(v.state.value.running)
        v.start(pkg, CompileMode.SPEED, "Nuvio")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, adb.commands.count { it == cmd })
    }

    @Test fun failureDoesNotSaveOrBadge() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(cmd) { listOf("Failure: nope") to 1 }
        val prefs = FakePrefs()
        val v = vm(adb, prefs)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, v.state.value.result?.success)
        assertTrue(prefs.saved.isEmpty())
        assertEquals(null, v.state.value.badge)
    }

    @Test fun connectionLostIsReported() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.dropAfterLines(cmd, 0)
        val v = vm(adb)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CompileOutcome.ConnectionLost, v.state.value.result?.outcome)
        assertNotNull(v.state.value.result)
    }

    @Test fun resetClearsPreviousRun() = runTest(timeout = 10.seconds) {
        val v = vm(fake())
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.log.isNotEmpty())
        v.reset()
        assertEquals(CompileUiState(), v.state.value)
    }

    @Test fun resetIgnoredWhileRunning() = runTest(timeout = 10.seconds) {
        val v = vm(fake())
        v.start(pkg, CompileMode.SPEED, "Nuvio")
        v.reset()
        assertTrue(v.state.value.running)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, v.state.value.result?.success)
    }

    @Test fun prefsFailureDoesNotLeaveRunning() = runTest(timeout = 10.seconds) {
        val prefs = object : Prefs by FakePrefs() {
            override suspend fun setLastCompile(pkg: String, value: LastCompile) { throw RuntimeException("disk") }
        }
        val v = CompileViewModel(CompileRunner(fake()), StatusChecker(fake()), prefs, dispatcher, clock = { 1L })
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.running)
        assertEquals(true, v.state.value.result?.success)
    }

    @Test fun durationIsRecorded() = runTest(timeout = 10.seconds) {
        var now = 10_000L
        val v = CompileViewModel(
            CompileRunner(fake()), StatusChecker(fake()), FakePrefs(), dispatcher,
            clock = { now.also { now += 89_000L } },
        )
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        val s = v.state.value
        // Re-stamped when the compile run begins (start() stamps 10_000 first); sequences
        // only get the run stamp, so the shown duration is the compile, not wall time.
        assertEquals(99_000L, s.startedAtMs)
        assertTrue(s.finishedAtMs > s.startedAtMs)
        assertEquals((s.finishedAtMs - s.startedAtMs) / 1000, s.durationSeconds)
    }

    @Test fun logIsCapped() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(cmd) { (List(3000) { "l$it" } + "Success") to 0 }
        val v = vm(adb)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2000, v.state.value.log.size)
        assertEquals("Success", v.state.value.log.last())
    }

    private fun vmWithStore(adb: FakeAdbBackend, store: io.github.devjardins.sidespeed.maintenance.MaintenanceStore?) =
        CompileViewModel(CompileRunner(adb), StatusChecker(adb), FakePrefs(), dispatcher, store, clock = { 1234L })

    @Test fun successOffersKeepOptimizedWhenNotMaintained() = runTest(timeout = 10.seconds) {
        val v = vmWithStore(fake(), io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore())
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.offerKeepOptimized)
    }

    @Test fun noOfferWhenAlreadyMaintained() = runTest(timeout = 10.seconds) {
        val store = io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore().apply { setMaintained("com.nuvio.tv", true) }
        val v = vmWithStore(fake(), store)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.offerKeepOptimized)
    }

    @Test fun failureNeverOffers() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { on(cmd) { listOf("Failure: nope") to 1 } }
        val v = vmWithStore(adb, io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore())
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.offerKeepOptimized)
    }

    @Test fun keepOptimizedTicksPackageAndClearsOffer() = runTest(timeout = 10.seconds) {
        val store = io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore()
        val v = vmWithStore(fake(), store)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        v.keepOptimized(pkg); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf("com.nuvio.tv"), store.maintainedFlow.value)
        assertFalse(v.state.value.offerKeepOptimized)
    }

    @Test fun keepOptimizedCallsBackOnlyAfterTheTickIsStored() = runTest(timeout = 10.seconds) {
        val store = io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore()
        val v = vmWithStore(fake(), store)
        var seen: Set<String>? = null
        v.keepOptimized(pkg) { seen = store.maintainedFlow.value }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf("com.nuvio.tv"), seen)
    }

    @Test fun keepOptimizedWithoutStoreIsNoOp() = runTest(timeout = 10.seconds) {
        val v = vmWithStore(fake(), null)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.offerKeepOptimized)
        v.keepOptimized(pkg); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.offerKeepOptimized)
    }

    @Test fun loadKeptReflectsStore() = runTest(timeout = 10.seconds) {
        val store = io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore().apply { setMaintained("com.nuvio.tv", true) }
        val v = vmWithStore(fake(), store)
        v.loadKept(pkg); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.kept)
    }

    @Test fun stopKeepingUntickesPackage() = runTest(timeout = 10.seconds) {
        val store = io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore().apply { setMaintained("com.nuvio.tv", true) }
        val v = vmWithStore(fake(), store)
        v.loadKept(pkg); dispatcher.scheduler.advanceUntilIdle()
        v.stopKeeping(pkg); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(store.maintainedFlow.value.isEmpty())
        assertFalse(v.state.value.kept)
    }

    @Test fun keepOptimizedSetsKept() = runTest(timeout = 10.seconds) {
        val v = vmWithStore(fake(), io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore())
        v.keepOptimized(pkg); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.kept)
    }

    private fun TestScope.statusVm(adb: FakeAdbBackend, store: FakeCompileStatusStore, up: Boolean = true) =
        CompileViewModel(
            CompileRunner(adb), StatusChecker(adb), FakePrefs(), dispatcher,
            clock = { 1234L },
            status = CompileStatus(store, "0.2.1", false, { false }, { 1234L }, backgroundScope),
            helperUp = { up },
        )

    @Test fun manualCompileStartAndFinishReported() = runTest(timeout = 10.seconds) {
        val store = FakeCompileStatusStore()
        val v = statusVm(fake(), store)
        runCurrent()
        v.start(pkg, CompileMode.SPEED, "Nuvio"); advanceUntilIdle(); dispatcher.scheduler.advanceUntilIdle(); runCurrent()
        val s = v.state.value
        assertTrue(s.result?.success == true)
        val row = store.saved
        assertEquals("com.nuvio.tv", row?.last?.pkg)
        assertEquals("Nuvio", row?.last?.label)
        assertEquals(true, row?.last?.ok)
    }

    @Test fun manualCompileHelperLossReportedAsInterrupted() = runTest(timeout = 10.seconds) {
        val adb = fake()
        adb.dropAfterLines(cmd, 0)
        val store = FakeCompileStatusStore()
        val v = statusVm(adb, store, up = false)
        runCurrent()
        v.start(pkg, CompileMode.SPEED, "Nuvio"); advanceUntilIdle(); dispatcher.scheduler.advanceUntilIdle(); runCurrent()
        val row = store.saved
        // The interrupted compile is kept as in-progress until the retry starts.
        assertEquals("com.nuvio.tv", row?.current?.pkg)
        assertEquals("Nuvio", row?.current?.label)
    }

    @Test fun exceptionWithNullMessageShowsFailureErrorBanner() = runTest(timeout = 10.seconds) {
        val throwingRunner = object : CommandRunner {
            override fun shell(command: String): Flow<ShellEvent> = kotlinx.coroutines.flow.flow { throw IllegalStateException() }
        }
        val v = CompileViewModel(CompileRunner(throwingRunner), StatusChecker(fake()), FakePrefs(), dispatcher)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        val outcome = v.state.value.result!!.outcome
        assertEquals(CompileOutcome.Error(null), outcome)
        assertEquals("Failure: Error", resultBanner(outcome).english())
    }

    @Test fun exceptionWithEmptyMessageShowsFailureBanner() = runTest(timeout = 10.seconds) {
        val throwingRunner = object : CommandRunner {
            override fun shell(command: String): Flow<ShellEvent> = kotlinx.coroutines.flow.flow { throw IllegalStateException("") }
        }
        val v = CompileViewModel(CompileRunner(throwingRunner), StatusChecker(fake()), FakePrefs(), dispatcher)
        v.start(pkg, CompileMode.SPEED, "Nuvio"); dispatcher.scheduler.advanceUntilIdle()
        val outcome = v.state.value.result!!.outcome
        assertEquals(CompileOutcome.Error(""), outcome)
        assertEquals("Failure", resultBanner(outcome).english())
    }
}

class ResultBannerTest {
    @Test fun sameMessageIsNotRepeated() {
        assertEquals("Success", resultBanner(CompileOutcome.Success).english())
        assertEquals("Failure", resultBanner(CompileOutcome.Failure).english())
        assertEquals("Failure", resultBanner(CompileOutcome.Error("")).english())
        assertEquals("Failure", resultBanner(CompileOutcome.Error("   ")).english())
    }

    @Test fun differentMessageIsAppended() {
        assertEquals("Failure: Connection lost", resultBanner(CompileOutcome.ConnectionLost).english())
        assertEquals("Failure: Error", resultBanner(CompileOutcome.Error(null)).english())
        assertEquals("Failure: done", resultBanner(CompileOutcome.Error("done")).english())
    }
}

class ResultTextTest {
    @Test fun durationUnderAMinuteIsSecondsOnly() {
        assertEquals("51 s", formatDuration(51).english())
        assertEquals("0 s", formatDuration(0).english())
        assertEquals("59 s", formatDuration(59).english())
    }

    @Test fun durationWithMinutes() {
        assertEquals("2 min 58 s", formatDuration(178).english())
        assertEquals("1 min 0 s", formatDuration(60).english())
        assertEquals("12 min 5 s", formatDuration(725).english())
    }

    @Test fun duration_reads_as_before() {
        assertEquals("0 s", formatDuration(0).english())
        assertEquals("1 s", formatDuration(1).english())
        assertEquals("59 s", formatDuration(59).english())
        assertEquals("1 min 0 s", formatDuration(60).english())
        assertEquals("1 min 1 s", formatDuration(61).english())
        assertEquals("2 min 58 s", formatDuration(178).english())
    }

    @Test fun successTitle() {
        assertEquals("Compiled to speed", successTitle(CompileMode.SPEED).english())
        assertEquals("Compiled to speed-profile", successTitle(CompileMode.SPEED_PROFILE).english())
    }

    @Test fun successDetailWithConfirmedBadge() {
        assertEquals(
            "Took 2 min 58 s · status confirmed speed ✓",
            successDetail(178, CompileMode.SPEED, StatusBadge.SPEED_OK).english(),
        )
        assertEquals(
            "Took 51 s · status confirmed speed-profile",
            successDetail(51, CompileMode.SPEED_PROFILE, StatusBadge.SPEED_PROFILE).english(),
        )
    }

    @Test fun successDetailWithoutUsableBadge() {
        assertEquals("Took 51 s", successDetail(51, CompileMode.SPEED, null).english())
        assertEquals("Took 51 s", successDetail(51, CompileMode.SPEED, StatusBadge.UNKNOWN).english())
    }

    @Test fun successDetailWhenStatusDisagrees() {
        assertEquals(
            "Took 51 s · status shows speed-profile",
            successDetail(51, CompileMode.SPEED, StatusBadge.SPEED_PROFILE).english(),
        )
        assertEquals(
            "Took 51 s · status shows Not compiled",
            successDetail(51, CompileMode.SPEED, StatusBadge.NEEDS_RECOMPILE).english(),
        )
    }
}
