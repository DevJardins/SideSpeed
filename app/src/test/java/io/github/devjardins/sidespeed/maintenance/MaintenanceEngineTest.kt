package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileOutcome
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.status.CompileLast
import io.github.devjardins.sidespeed.status.CompilePhase
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.status.FakeCompileStatusStore
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.testing.EnglishStrings
import io.github.devjardins.sidespeed.testing.english
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

private class FakeSource : AppSource {
    val apps = linkedMapOf<String, AppEntry>()
    fun put(pkg: String, updated: Long = 100, installer: String? = null) {
        apps[pkg] = AppEntry(pkg, pkg.uppercase(), installer, updated)
    }
    fun update(pkg: String, updated: Long) { apps[pkg] = apps.getValue(pkg).copy(lastUpdateTime = updated) }
    override fun list() = apps.values.toList()
    var failFind = false
    override fun find(pkg: String): AppEntry? { if (failFind) error("pm died"); return apps[pkg] }
}

private class FakePrefs : Prefs {
    val saved = mutableMapOf<String, LastCompile>()
    val writes = mutableListOf<Pair<String, LastCompile>>()
    override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setLanWarningAcked(v: Boolean) {}
    override val pairedTls: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setPairedTls(v: Boolean) {}
    override val onBoxAdbRefusedBuild: Flow<String?> = MutableStateFlow(null)
    override suspend fun setOnBoxAdbRefusedBuild(v: String?) {}
    override fun lastCompile(pkg: String): Flow<LastCompile?> = flow { emit(saved[pkg]) }
    override suspend fun setLastCompile(pkg: String, value: LastCompile) { saved[pkg] = value; writes += pkg to value }
    override fun lastCompiles(): Flow<Map<String, LastCompile>> = flow { emit(saved.toMap()) }
    val selfCompileFlow = MutableStateFlow<SelfCompile?>(null)
    override val selfCompile: Flow<SelfCompile?> = selfCompileFlow
    override suspend fun setSelfCompile(v: SelfCompile) { selfCompileFlow.value = v }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(testScope: TestScope, failingHistory: Boolean = false, statusStore: FakeCompileStatusStore? = null) {
    val badges = mutableMapOf<String, StatusBadge>()
    var badgesFail = false
    val source = FakeSource()
    val store: FakeMaintenanceStore = if (failingHistory) object : FakeMaintenanceStore() {
        override suspend fun addHistory(record: JobRecord) { error("disk full") }
    } else FakeMaintenanceStore()
    val prefs = FakePrefs()
    var up = true
    var now = 1000L
    val calls = mutableListOf<Pair<String, CompileMode>>()
    var script: (String) -> Flow<CompileEvent> = { success() }

    fun success(): Flow<CompileEvent> = flow {
        emit(CompileEvent.Attempt("pm compile"))
        emit(CompileEvent.Finished(true, false, CompileOutcome.Success))
    }
    fun failure(outcome: CompileOutcome = CompileOutcome.Failure): Flow<CompileEvent> =
        flow { emit(CompileEvent.Finished(false, true, outcome)) }

    var measuring: String? = null
    var helperUpCalls = 0
    var helperUpGate: CompletableDeferred<Unit>? = null
    var helperUpInvocations = 0
    val status = statusStore?.let {
        CompileStatus(it, "0.2.1", false, { false }, { now }, testScope.backgroundScope)
    }

    val engine = MaintenanceEngine(
        source, store, prefs,
        runCompile = { pkg, mode -> calls += pkg.value to mode; script(pkg.value) },
        helperUp = { helperUpInvocations++; helperUpGate?.await(); up },
        badges = { pkgs -> if (badgesFail) error("dumpsys failed") else badges.filterKeys { it in pkgs } },
        scope = testScope.backgroundScope,
        ownPackage = "own.pkg",
        strings = EnglishStrings,
        status = status,
        clock = { now },
        measuring = { measuring },
        onHelperUp = { helperUpCalls++ },
    )
    val phases get() = engine.state.value.phases
}

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceEngineTest {
    @Test fun tickedStaleAppCompilesInStoredModeAndRecordsLastCompile() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 500)
        h.prefs.saved["a.a"] = LastCompile(100, CompileMode.SPEED_PROFILE)
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(listOf("a.a" to CompileMode.SPEED_PROFILE), h.calls)
        assertEquals(listOf("a.a" to LastCompile(1000, CompileMode.SPEED_PROFILE)), h.prefs.writes)
    }

    @Test fun neverCompiledAppUsesSpeed() = runTest {
        val h = Harness(this)
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(listOf("a.a" to CompileMode.SPEED), h.calls)
    }

    @Test fun freshAppIsNotEnqueued() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 100)
        h.prefs.saved["a.a"] = LastCompile(500, CompileMode.SPEED)
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertTrue(h.calls.isEmpty())
    }

    @Test fun unmaintainedAppsAreIgnoredAndSwitchAddsEveryNonSystemApp() = runTest {
        val h = Harness(this)
        h.source.put("side.app", installer = null)
        h.source.put("store.app", installer = "com.android.vending")
        h.source.apps["sys.app"] = AppEntry("sys.app", "SYS", null, 100, isSystem = true)
        runCurrent()
        assertTrue(h.calls.isEmpty())
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertEquals(listOf("side.app", "store.app"), h.calls.map { it.first })
    }

    @Test fun freshAppStillInSpeedProfileIsUpgradedToSpeed() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 100)
        h.prefs.saved["a.a"] = LastCompile(500, CompileMode.SPEED)
        h.badges["a.a"] = StatusBadge.SPEED_PROFILE
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(listOf("a.a" to CompileMode.SPEED), h.calls)
    }

    @Test fun neverCompiledAppsInSpeedProfileAreUpgradedToSpeedNotKeptAsIs() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 100)
        h.badges["a.a"] = StatusBadge.SPEED_PROFILE
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertEquals(listOf("a.a" to CompileMode.SPEED), h.calls)
    }

    @Test fun appChosenInSpeedProfileIsNotForcedToSpeed() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 100)
        h.prefs.saved["a.a"] = LastCompile(500, CompileMode.SPEED_PROFILE)
        h.badges["a.a"] = StatusBadge.SPEED_PROFILE
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertTrue(h.calls.isEmpty())
    }

    @Test fun appAlreadyAtSpeedIsLeftAloneAndStatusFailureFallsBackToTime() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 100)
        h.source.put("b.b", updated = 100)
        h.prefs.saved["a.a"] = LastCompile(500, CompileMode.SPEED)
        h.prefs.saved["b.b"] = LastCompile(500, CompileMode.SPEED)
        h.badges["a.a"] = StatusBadge.SPEED_OK
        h.store.setMaintained("a.a", true)
        h.store.setMaintained("b.b", true)
        h.badgesFail = true
        runCurrent()
        assertTrue(h.calls.isEmpty())
    }

    @Test fun anAppThatStaysBelowSpeedAfterCompilingIsNotRecompiledInALoop() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 100)
        h.badges["a.a"] = StatusBadge.SPEED_PROFILE
        h.store.setMaintained("a.a", true)
        runCurrent()
        h.engine.scan(); runCurrent()
        assertEquals(1, h.calls.size)
    }

    @Test fun duplicateScansEnqueueOnce() = runTest {
        val h = Harness(this)
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        h.engine.scan(); h.engine.scan(); h.engine.scan()
        runCurrent()
        assertEquals(1, h.calls.size)
    }

    @Test fun jobsRunOneAtATime() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, CompileOutcome.Success)) } }
        h.source.put("a.a"); h.source.put("b.b")
        h.store.setMaintained("a.a", true); h.store.setMaintained("b.b", true)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(setOf(JobPhase.RUNNING, JobPhase.WAITING), h.phases.values.toSet())
        assertEquals(2, h.engine.state.value.queueLength)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, h.calls.size)
        assertTrue(h.phases.isEmpty())
    }

    @Test fun ownPackageIsNeverEnqueued() = runTest {
        val h = Harness(this)
        h.source.put("own.pkg")
        h.store.setMaintained("own.pkg", true)
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertTrue(h.calls.isEmpty())
    }

    @Test fun lastCompileEpochIsJobStartSoMidCompileUpdateRecompiles() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 500)
        h.store.setMaintained("a.a", true)
        var run = 0
        h.script = {
            flow {
                if (run++ == 0) { h.source.update("a.a", 1500); h.now = 2000 }
                emit(CompileEvent.Finished(true, false, CompileOutcome.Success))
            }
        }
        runCurrent()
        assertEquals(2, h.calls.size)
        assertEquals(1000L, h.prefs.writes.first().second.epochMs)
    }

    @Test fun helperDownPausesAndResumes() = runTest {
        val h = Harness(this)
        h.up = false
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertTrue(h.calls.isEmpty())
        assertTrue(h.engine.state.value.helperDown)
        assertEquals(JobPhase.WAITING, h.phases["a.a"])
        h.up = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, h.calls.size)
        assertFalse(h.engine.state.value.helperDown)
    }

    @Test fun measuredPackageIsSkippedUntilSequenceEnds() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 500)
        h.store.setMaintained("a.a", true)
        h.measuring = "a.a"
        runCurrent()
        assertTrue(h.calls.isEmpty())
        h.measuring = null
        h.engine.scan()
        runCurrent()
        assertEquals(listOf("a.a" to CompileMode.SPEED), h.calls)
    }

    @Test fun helperUpTransitionFiresHookOnce() = runTest {
        val h = Harness(this)
        h.up = false
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(0, h.helperUpCalls)
        h.up = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, h.helperUpCalls)
        assertEquals(1, h.calls.size)
    }

    @Test fun runJobSkipsMeasuredPkgAtCompileTime() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 500)
        h.store.setMaintained("a.a", true)
        h.helperUpGate = CompletableDeferred()
        runCurrent()
        // Queued and parked in the helper check, before the take: nothing compiled yet.
        assertEquals(JobPhase.WAITING, h.phases["a.a"])
        assertTrue("drain must reach the helper check", h.helperUpInvocations > 0)
        assertTrue(h.calls.isEmpty())
        h.measuring = "a.a"
        h.helperUpGate?.complete(Unit)
        h.helperUpGate = null
        advanceTimeBy(1000)
        runCurrent()
        assertTrue("measured package must not compile", h.calls.isEmpty())
        // The drain must have progressed past the take (not still parked) and
        // left nothing running: a vacuous pass would still show WAITING.
        assertNull(h.engine.state.value.phases["a.a"])
    }

    @Test fun awaitNotRunningReturnsWhenIdle() = runTest {
        val h = Harness(this)
        h.engine.awaitNotRunning("a.a")
    }

    @Test fun helperDyingMidCompileRequeuesWithoutUsingARetry() = runTest {
        val h = Harness(this)
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        var run = 0
        h.script = { if (run++ == 0) flow { h.up = false; emit(CompileEvent.Finished(false, true, CompileOutcome.ConnectionLost)) } else h.success() }
        runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(JobPhase.WAITING, h.phases["a.a"])
        assertTrue(h.store.historyFlow.value.isEmpty())
        h.up = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, h.calls.size)
        assertEquals(listOf(true), h.store.historyFlow.value.map { it.success })
    }

    @Test fun failureRetriesAfter30MinutesUpTo3Times() = runTest {
        val h = Harness(this)
        h.script = { h.failure() }
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(JobPhase.RETRY, h.phases["a.a"])
        advanceTimeBy(30 * MIN - 1); runCurrent()
        assertEquals(1, h.calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, h.calls.size)
        advanceTimeBy(30 * MIN); runCurrent()
        advanceTimeBy(30 * MIN); runCurrent()
        assertEquals(4, h.calls.size)
        advanceTimeBy(5 * 60 * MIN); runCurrent()
        assertEquals(4, h.calls.size)
        assertEquals(JobPhase.RETRY, h.phases["a.a"])
        assertEquals(4, h.store.historyFlow.value.size)
        assertTrue(h.store.historyFlow.value.none { it.success })
    }

    @Test fun manualRetryRestartsAnExhaustedApp() = runTest {
        val h = Harness(this)
        h.script = { h.failure() }
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        runCurrent()
        advanceTimeBy(3 * 30 * MIN); runCurrent()
        assertEquals(4, h.calls.size)
        h.engine.retry("a.a")
        runCurrent()
        assertEquals(5, h.calls.size)
    }

    @Test fun updateClearsTheFailureRecord() = runTest {
        val h = Harness(this)
        var run = 0
        h.script = { if (run++ == 0) h.failure() else h.success() }
        h.source.put("a.a", updated = 100)
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(JobPhase.RETRY, h.phases["a.a"])
        h.source.update("a.a", 900)
        h.engine.scan()
        runCurrent()
        assertEquals(2, h.calls.size)
        assertTrue(h.phases.isEmpty())
    }

    @Test fun uninstalledTickedAppIsSkippedAndPruned() = runTest {
        val h = Harness(this)
        h.store.setMaintained("gone.app", true)
        runCurrent()
        assertTrue(h.calls.isEmpty())
        assertTrue(h.store.maintainedFlow.value.isEmpty())
    }

    @Test fun appUninstalledWhileQueuedIsSkipped() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, CompileOutcome.Success)) } }
        h.source.put("a.a"); h.source.put("b.b")
        h.store.setMaintained("a.a", true); h.store.setMaintained("b.b", true)
        runCurrent()
        h.source.apps.remove("b.b")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a.a"), h.calls.map { it.first })
        assertEquals(setOf("a.a"), h.store.maintainedFlow.value)
    }

    @Test fun switchOffDropsWaitingSideloadedButNotTheRunningOne() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, CompileOutcome.Success)) } }
        h.source.put("a.a"); h.source.put("b.b")
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertEquals(setOf(JobPhase.RUNNING, JobPhase.WAITING), h.phases.values.toSet())
        h.store.setMode(AutoOptimizeMode.SELECTED)
        runCurrent()
        assertEquals(setOf(JobPhase.RUNNING), h.phases.values.toSet())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertTrue(h.phases.isEmpty())
    }

    private fun Harness.populate() {
        source.put("side.app", installer = null)
        source.put("store.app", installer = "com.android.vending")
        source.put("ticked.store", installer = "com.android.vending")
        source.apps["sys.app"] = AppEntry("sys.app", "SYS", null, 100, isSystem = true)
    }

    @Test fun selectedModeCompilesOnlyTickedApps() = runTest {
        val h = Harness(this)
        h.populate()
        h.store.setMaintained("ticked.store", true)
        runCurrent()
        assertEquals(listOf("ticked.store"), h.calls.map { it.first })
        assertEquals(setOf("ticked.store"), h.engine.state.value.maintained)
    }

    @Test fun sideloadedModeCoversSideloadedAppsAndTickedOnes() = runTest {
        val h = Harness(this)
        h.populate()
        h.store.setMaintained("ticked.store", true)
        h.store.setMode(AutoOptimizeMode.SIDELOADED)
        runCurrent()
        assertEquals(setOf("side.app", "ticked.store"), h.calls.map { it.first }.toSet())
        assertEquals(setOf("side.app", "ticked.store"), h.engine.state.value.maintained)
    }

    @Test fun allModeCoversEveryNonSystemAppAndCountMatches() = runTest {
        val h = Harness(this)
        h.populate()
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertEquals(setOf("side.app", "store.app", "ticked.store"), h.calls.map { it.first }.toSet())
        assertEquals(3, h.engine.state.value.maintained.size)
    }

    @Test fun allModeStillCoversATickedPreinstalledApp() = runTest {
        val h = Harness(this)
        h.populate()
        h.store.setMaintained("sys.app", true)
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertTrue("sys.app" in h.engine.state.value.maintained)
    }

    @Test fun switchingModesKeepsTickedAppsInTheSetAndTheyOnlyWidenCoverage() = runTest {
        val h = Harness(this)
        h.populate()
        h.store.setMaintained("ticked.store", true)
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        h.store.setMode(AutoOptimizeMode.SIDELOADED)
        runCurrent()
        h.store.setMode(AutoOptimizeMode.SELECTED)
        runCurrent()
        assertEquals(setOf("ticked.store"), h.store.maintainedFlow.value)
        assertEquals(setOf("ticked.store"), h.engine.state.value.maintained)
    }

    @Test fun narrowingModeDropsWaitingAppsThatNoLongerQualify() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, CompileOutcome.Success)) } }
        h.source.put("b.b", installer = null)
        h.source.put("a.a", installer = "com.android.vending")
        h.source.put("c.c", installer = null)
        h.store.setMode(AutoOptimizeMode.ALL)
        runCurrent()
        assertEquals(3, h.engine.state.value.queueLength)
        h.store.setMode(AutoOptimizeMode.SIDELOADED)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b.b", "c.c"), h.calls.map { it.first })
        assertEquals(setOf("b.b", "c.c"), h.engine.state.value.maintained)
    }

    @Test fun storeFailureDuringAJobDoesNotCrashTheEngine() = runTest {
        val h = Harness(this, failingHistory = true)
        h.source.put("a.a", updated = 500)
        h.store.setMaintained("a.a", true)
        runCurrent()
        h.source.put("b.b", updated = 500)
        h.store.setMaintained("b.b", true)
        runCurrent()
        assertEquals(listOf("a.a", "b.b"), h.calls.map { it.first })
    }

    @Test fun sourceFailureDuringAScanDoesNotCrashTheEngine() = runTest {
        val h = Harness(this)
        h.source.put("a.a", updated = 500)
        h.source.failFind = true
        h.store.setMaintained("a.a", true)
        runCurrent()
        h.source.failFind = false
        h.engine.scan()
        runCurrent()
        assertEquals(listOf("a.a"), h.calls.map { it.first })
    }

    @Test fun finishedCompileIsReportedToStatus() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        h.source.put("a.a", updated = 500)
        h.prefs.saved["a.a"] = LastCompile(100, CompileMode.SPEED)
        h.store.setMaintained("a.a", true)
        runCurrent()
        assertEquals(listOf("a.a" to CompileMode.SPEED), h.calls)
        runCurrent()
        val saved = statusStore.saved
        assertNull(saved?.current)
        assertEquals(CompileLast("a.a", "A.A", true, h.now), saved?.last)
        assertEquals(CompilePhase.IDLE, h.status!!.snapshot().phase)
    }

    @Test fun helperLossDuringCompileIsReportedAsInterrupted() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        h.script = { h.up = false; h.failure(CompileOutcome.Error("boom")) }
        h.source.put("a.a", updated = 500)
        h.prefs.saved["a.a"] = LastCompile(100, CompileMode.SPEED)
        h.store.setMaintained("a.a", true)
        runCurrent()
        runCurrent()
        val row = h.status!!.snapshot()
        assertEquals(CompilePhase.INTERRUPTED, row.phase)
        assertEquals("a.a", row.activePkg)
        assertEquals("A.A", row.activeLabel)
    }

    @Test fun helperDownWithQueueIsReportedAsWaitingHelper() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        h.up = false
        h.source.put("a.a", updated = 500)
        h.prefs.saved["a.a"] = LastCompile(100, CompileMode.SPEED)
        h.store.setMaintained("a.a", true)
        runCurrent()
        runCurrent()
        val row = h.status!!.snapshot()
        assertEquals(CompilePhase.WAITING_HELPER, row.phase)
        assertEquals(1, row.queueWaiting)
    }

    @Test fun historyRecordsSetMessageKeyAndDetail() = runTest {
        val h = Harness(this)
        h.source.put("ok.app")
        h.source.put("fail.app")
        h.source.put("conn.lost.app")
        h.source.put("ex.msg.app")
        h.source.put("ex.null.app")
        h.source.put("empty.app")
        h.script = { pkg ->
            when (pkg) {
                "ok.app" -> h.success()
                "fail.app" -> h.failure(CompileOutcome.Failure)
                "conn.lost.app" -> h.failure(CompileOutcome.ConnectionLost)
                "ex.msg.app" -> flow { throw IllegalStateException("boom") }
                "ex.null.app" -> flow { throw IllegalStateException() }
                else -> flow { }
            }
        }
        listOf("ok.app", "fail.app", "conn.lost.app", "ex.msg.app", "ex.null.app", "empty.app").forEach {
            h.store.setMaintained(it, true)
        }
        runCurrent()
        val byPkg = h.store.historyFlow.value.associateBy { it.pkg }
        assertEquals(JobMessage.SUCCESS.name, byPkg.getValue("ok.app").messageKey)
        assertNull(byPkg.getValue("ok.app").detail)
        assertEquals("Success", byPkg.getValue("ok.app").message)
        assertEquals("Success", byPkg.getValue("ok.app").display.english())

        assertEquals(JobMessage.FAILURE.name, byPkg.getValue("fail.app").messageKey)
        assertNull(byPkg.getValue("fail.app").detail)
        assertEquals("Failure", byPkg.getValue("fail.app").message)
        assertEquals("Failure", byPkg.getValue("fail.app").display.english())

        assertEquals(JobMessage.CONNECTION_LOST.name, byPkg.getValue("conn.lost.app").messageKey)
        assertNull(byPkg.getValue("conn.lost.app").detail)
        assertEquals("Connection lost", byPkg.getValue("conn.lost.app").message)
        assertEquals("Connection lost", byPkg.getValue("conn.lost.app").display.english())

        assertEquals(JobMessage.ERROR.name, byPkg.getValue("ex.msg.app").messageKey)
        assertEquals("boom", byPkg.getValue("ex.msg.app").detail)
        assertEquals("boom", byPkg.getValue("ex.msg.app").message)
        assertEquals("boom", byPkg.getValue("ex.msg.app").display.english())

        assertEquals(JobMessage.ERROR.name, byPkg.getValue("ex.null.app").messageKey)
        assertNull(byPkg.getValue("ex.null.app").detail)
        assertEquals("Error", byPkg.getValue("ex.null.app").message)
        assertEquals("Error", byPkg.getValue("ex.null.app").display.english())

        assertEquals(JobMessage.FAILURE.name, byPkg.getValue("empty.app").messageKey)
        assertNull(byPkg.getValue("empty.app").detail)
        assertEquals("Failure", byPkg.getValue("empty.app").message)
        assertEquals("Failure", byPkg.getValue("empty.app").display.english())
    }

    @Test fun requestCompile_without_keep_compiles_under_selected_mode_and_survives_concurrent_scan() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        val status = requireNotNull(h.status)
        h.source.put("com.example.new", updated = 100)
        h.helperUpGate = CompletableDeferred()

        assertTrue(h.engine.requestCompile("com.example.new", keepOptimized = false))
        assertEquals(1L, status.requestSeq())

        // Trigger a concurrent scan while queued: SELECTED + 0 ticked must not evict it.
        h.engine.scan()
        runCurrent()
        assertEquals(JobPhase.WAITING, h.phases["com.example.new"])
        assertTrue(h.calls.isEmpty())

        h.helperUpGate?.complete(Unit)
        h.helperUpGate = null
        runCurrent()

        assertEquals(listOf("com.example.new" to CompileMode.SPEED), h.calls)
        assertTrue(h.store.maintainedFlow.value.isEmpty())
        assertTrue(h.phases.isEmpty())

        // After completion it must drop out of priorityOneShot: a subsequent update + scan under SELECTED ignores it.
        h.source.update("com.example.new", 900)
        h.engine.scan()
        runCurrent()
        assertEquals(1, h.calls.size)
    }

    @Test fun requestCompile_with_keep_ticks_maintained_and_prioritizes_ahead_of_background_queue() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.script = { pkg ->
            flow {
                if (pkg == "bg.one") gate.await()
                emit(CompileEvent.Finished(true, false, CompileOutcome.Success))
            }
        }
        h.source.put("bg.one")
        h.source.put("bg.two")
        h.source.put("bg.three")
        h.source.put("com.example.urgent")
        h.store.setMaintained("bg.one", true)
        h.store.setMaintained("bg.two", true)
        h.store.setMaintained("bg.three", true)
        runCurrent()
        assertEquals(JobPhase.RUNNING, h.phases["bg.one"])
        assertEquals(JobPhase.WAITING, h.phases["bg.two"])
        assertEquals(JobPhase.WAITING, h.phases["bg.three"])

        assertTrue(h.engine.requestCompile("com.example.urgent", keepOptimized = true))
        assertTrue("com.example.urgent" in h.store.maintainedFlow.value)

        gate.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("bg.one", "com.example.urgent", "bg.two", "bg.three"),
            h.calls.map { it.first },
        )
    }

    @Test fun requestCompile_skips_own_package_and_uninstalled_and_respects_measuring() = runTest {
        val h = Harness(this)
        h.source.put("own.pkg")
        h.source.put("com.example.measured")
        h.source.put("com.example.race")

        assertFalse(h.engine.requestCompile("own.pkg", keepOptimized = true))
        assertFalse(h.engine.requestCompile("missing.pkg", keepOptimized = true))
        assertFalse(h.engine.requestCompile("bad..pkg", keepOptimized = true))
        assertTrue(h.store.maintainedFlow.value.isEmpty())

        h.measuring = "com.example.measured"
        assertFalse(h.engine.requestCompile("com.example.measured", keepOptimized = false))
        runCurrent()
        assertTrue(h.calls.isEmpty())

        // Also verify measuring starting while a one-shot request is parked at helperUp skips compiling.
        h.measuring = null
        h.helperUpGate = CompletableDeferred()
        assertTrue(h.engine.requestCompile("com.example.race", keepOptimized = false))
        runCurrent()
        assertEquals(JobPhase.WAITING, h.phases["com.example.race"])
        h.measuring = "com.example.race"
        h.helperUpGate?.complete(Unit)
        h.helperUpGate = null
        runCurrent()
        assertTrue("measured package must never compile", h.calls.isEmpty())
        assertNull(h.phases["com.example.race"])
    }

    @Test fun requestOptimizeNow_clears_retry_backoff_when_armed() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        val status = requireNotNull(h.status)
        var attempts = 0
        h.script = { if (attempts++ == 0) h.failure() else h.success() }
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        runCurrent(); runCurrent()

        assertEquals(1, h.calls.size)
        assertEquals(JobPhase.RETRY, h.phases["a.a"])
        assertEquals(1, status.snapshot().retrying)

        val queued = h.engine.requestOptimizeNow()
        assertEquals(1, queued)
        assertEquals(1L, status.requestSeq())
        assertEquals(0, status.snapshot().retrying)

        // Compiles immediately without waiting 30 minutes.
        runCurrent(); runCurrent()
        assertEquals(2, h.calls.size)
        assertTrue(h.phases.isEmpty())
        assertEquals(0, status.snapshot().retrying)
    }

    @Test fun requestOptimizeNow_unarmed_compiles_stale_sideloaded_without_changing_mode() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        val status = requireNotNull(h.status)
        h.source.put("side.stale", installer = null)
        h.source.put("side.fresh", updated = 100, installer = null)
        h.prefs.saved["side.fresh"] = LastCompile(500, CompileMode.SPEED)
        h.badges["side.fresh"] = StatusBadge.SPEED_OK
        h.source.put("store.stale", installer = "com.android.vending")
        h.source.apps["sys.stale"] = AppEntry("sys.stale", "SYS", null, 100, isSystem = true)
        h.source.put("own.pkg", installer = null)

        val queued = h.engine.requestOptimizeNow()
        assertEquals(1, queued)
        assertEquals(1L, status.requestSeq())
        runCurrent()

        assertEquals(listOf("side.stale" to CompileMode.SPEED), h.calls)
        assertEquals(AutoOptimizeMode.SELECTED, h.store.modeFlow.value)
        assertTrue(h.store.maintainedFlow.value.isEmpty())

        // Second call when nothing left is stale returns 0 and bumps reqSeq.
        assertEquals(0, h.engine.requestOptimizeNow())
        assertEquals(2L, status.requestSeq())
        runCurrent()
        assertEquals(1, h.calls.size)
    }

    @Test fun requestCompile_without_keep_retries_failure_then_clears_retrying_when_exhausted() = runTest {
        val statusStore = FakeCompileStatusStore()
        val h = Harness(this, statusStore = statusStore)
        val status = requireNotNull(h.status)
        h.script = { h.failure() }
        h.source.put("one.shot", updated = 100)

        assertTrue(h.engine.requestCompile("one.shot", keepOptimized = false))
        runCurrent(); runCurrent()
        assertEquals(1, h.calls.size)
        assertEquals(JobPhase.RETRY, h.phases["one.shot"])
        assertEquals(1, status.snapshot().retrying)

        // Retries 3 times (4 total attempts), then exhausts and clears retrying for unmaintained one-shot.
        advanceTimeBy(30 * MIN); runCurrent()
        advanceTimeBy(30 * MIN); runCurrent()
        advanceTimeBy(30 * MIN); runCurrent()
        runCurrent()
        assertEquals(4, h.calls.size)
        assertTrue(h.phases.isEmpty())
        assertEquals(0, status.snapshot().retrying)
    }
}
