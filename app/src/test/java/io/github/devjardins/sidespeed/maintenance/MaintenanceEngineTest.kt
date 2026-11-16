package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    override fun find(pkg: String) = apps[pkg]
}

private class FakePrefs : Prefs {
    val saved = mutableMapOf<String, LastCompile>()
    val writes = mutableListOf<Pair<String, LastCompile>>()
    override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setLanWarningAcked(v: Boolean) {}
    override val pairedTls: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setPairedTls(v: Boolean) {}
    override fun lastCompile(pkg: String): Flow<LastCompile?> = flow { emit(saved[pkg]) }
    override suspend fun setLastCompile(pkg: String, value: LastCompile) { saved[pkg] = value; writes += pkg to value }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(testScope: TestScope) {
    val source = FakeSource()
    val store = FakeMaintenanceStore()
    val prefs = FakePrefs()
    var up = true
    var now = 1000L
    val calls = mutableListOf<Pair<String, CompileMode>>()
    var script: (String) -> Flow<CompileEvent> = { success() }

    fun success(): Flow<CompileEvent> = flow {
        emit(CompileEvent.Attempt("pm compile"))
        emit(CompileEvent.Finished(true, false, "Success"))
    }
    fun failure(message: String = "Failure"): Flow<CompileEvent> = flow { emit(CompileEvent.Finished(false, true, message)) }

    val engine = MaintenanceEngine(
        source, store, prefs,
        runCompile = { pkg, mode -> calls += pkg.value to mode; script(pkg.value) },
        helperUp = { up },
        scope = testScope.backgroundScope,
        ownPackage = "own.pkg",
        clock = { now },
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

    @Test fun unmaintainedAppsAreIgnoredAndSwitchAddsOnlySideloaded() = runTest {
        val h = Harness(this)
        h.source.put("side.app", installer = null)
        h.source.put("store.app", installer = "com.android.vending")
        runCurrent()
        assertTrue(h.calls.isEmpty())
        h.store.setOptimizeSideloaded(true)
        runCurrent()
        assertEquals(listOf("side.app"), h.calls.map { it.first })
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
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, "Success")) } }
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
        h.store.setOptimizeSideloaded(true)
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
                emit(CompileEvent.Finished(true, false, "Success"))
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

    @Test fun helperDyingMidCompileRequeuesWithoutUsingARetry() = runTest {
        val h = Harness(this)
        h.source.put("a.a")
        h.store.setMaintained("a.a", true)
        var run = 0
        h.script = { if (run++ == 0) flow { h.up = false; emit(CompileEvent.Finished(false, true, "Connection lost")) } else h.success() }
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
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, "Success")) } }
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
        h.script = { flow { gate.await(); emit(CompileEvent.Finished(true, false, "Success")) } }
        h.source.put("a.a"); h.source.put("b.b")
        h.store.setOptimizeSideloaded(true)
        runCurrent()
        assertEquals(setOf(JobPhase.RUNNING, JobPhase.WAITING), h.phases.values.toSet())
        h.store.setOptimizeSideloaded(false)
        runCurrent()
        assertEquals(setOf(JobPhase.RUNNING), h.phases.values.toSet())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, h.calls.size)
        assertTrue(h.phases.isEmpty())
    }
}
