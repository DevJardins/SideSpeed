package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.status.CompileLast
import io.github.devjardins.sidespeed.status.CompilePhase
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.status.FakeCompileStatusStore
import io.github.devjardins.sidespeed.status.SelfState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSelfCompilePrefs : Prefs {
    override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setLanWarningAcked(v: Boolean) {}
    override val pairedTls: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setPairedTls(v: Boolean) {}
    override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(null)
    override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
    override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
    val selfCompileFlow = MutableStateFlow<SelfCompile?>(null)
    override val selfCompile: Flow<SelfCompile?> = selfCompileFlow
    var writes = 0
    override suspend fun setSelfCompile(v: SelfCompile) { selfCompileFlow.value = v; writes++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SelfCompilerTest {
    private val own = "io.github.devjardins.sidespeed"

    private fun compiler(
        prefs: FakeSelfCompilePrefs,
        up: Boolean = true,
        debuggable: Boolean = false,
        versionCode: Long = 7L,
        lastUpdateTime: Long = 4242L,
        script: (String) -> Flow<CompileEvent> = { success() },
        calls: MutableList<Pair<String, CompileMode>> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
        status: CompileStatus? = null,
        measuring: () -> String? = { null },
    ) = SelfCompiler(
        prefs = prefs,
        runCompile = { pkg, mode -> calls += pkg.value to mode; script(pkg.value) },
        helperUp = { up },
        ownPackage = own,
        versionCode = versionCode,
        lastUpdateTime = lastUpdateTime,
        debuggable = debuggable,
        status = status,
        log = { logs += it },
        measuring = measuring,
    )

    private fun success(): Flow<CompileEvent> = flow {
        emit(CompileEvent.Attempt("pm compile -m speed -f $own"))
        emit(CompileEvent.Finished(true, false, io.github.devjardins.sidespeed.compile.CompileOutcome.Success))
    }

    @Test fun compilesOncePerInstallAndRecords() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val calls = mutableListOf<Pair<String, CompileMode>>()
        compiler(prefs, calls = calls).maybeCompile()
        assertEquals(listOf(own to CompileMode.SPEED), calls)
        assertEquals(SelfCompile(7L, 4242L), prefs.selfCompileFlow.value)
        // Second run for the same install is a no-op.
        compiler(prefs, calls = calls).maybeCompile()
        assertEquals(1, calls.size)
    }

    @Test fun recompilesAfterUpdate() = runTest {
        val prefs = FakeSelfCompilePrefs()
        prefs.selfCompileFlow.value = SelfCompile(7L, 1111L)
        val calls = mutableListOf<Pair<String, CompileMode>>()
        compiler(prefs, calls = calls).maybeCompile()
        assertEquals(listOf(own to CompileMode.SPEED), calls)
        assertEquals(SelfCompile(7L, 4242L), prefs.selfCompileFlow.value)
    }

    @Test fun recompilesAfterVersionBump() = runTest {
        val prefs = FakeSelfCompilePrefs()
        prefs.selfCompileFlow.value = SelfCompile(6L, 4242L)
        val calls = mutableListOf<Pair<String, CompileMode>>()
        compiler(prefs, calls = calls).maybeCompile()
        assertEquals(1, calls.size)
    }

    @Test fun debuggableBuildIsSkippedSilently() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val calls = mutableListOf<Pair<String, CompileMode>>()
        val logs = mutableListOf<String>()
        var helperChecks = 0
        SelfCompiler(
            prefs = prefs,
            runCompile = { pkg, mode -> calls += pkg.value to mode; success() },
            helperUp = { helperChecks++; true },
            ownPackage = own,
            versionCode = 7L,
            lastUpdateTime = 4242L,
            debuggable = true,
            log = { logs += it },
        ).maybeCompile()
        assertTrue(calls.isEmpty())
        assertEquals(0, helperChecks)
        assertNull(prefs.selfCompileFlow.value)
        assertEquals(1, logs.size)
    }

    @Test fun helperDownMeansNoCompileAndNoRecord() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val calls = mutableListOf<Pair<String, CompileMode>>()
        compiler(prefs, up = false, calls = calls).maybeCompile()
        assertTrue(calls.isEmpty())
        assertNull(prefs.selfCompileFlow.value)
    }

    @Test fun failedCompileLeavesNoRecordSoItRetries() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val calls = mutableListOf<Pair<String, CompileMode>>()
        compiler(prefs, calls = calls, script = {
            flow { emit(CompileEvent.Finished(false, true, io.github.devjardins.sidespeed.compile.CompileOutcome.Failure)) }
        }).maybeCompile()
        assertEquals(1, calls.size)
        assertNull(prefs.selfCompileFlow.value)
        assertEquals(0, prefs.writes)
    }

    @Test fun compileErrorLeavesNoRecordSoItRetries() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val calls = mutableListOf<Pair<String, CompileMode>>()
        compiler(prefs, calls = calls, script = { error("helper died") }).maybeCompile()
        assertEquals(1, calls.size)
        assertNull(prefs.selfCompileFlow.value)
    }

    @Test fun successIsReportedWithSelfOptimized() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val store = FakeCompileStatusStore()
        val status = CompileStatus(store, "0.2.1", false, { false }, { 1000L }, backgroundScope)
        runCurrent()
        compiler(prefs, status = status).maybeCompile()
        runCurrent()
        assertEquals(SelfCompile(7L, 4242L), prefs.selfCompileFlow.value)
        assertEquals(CompileLast(own, "SideSpeed", true, 1000L), store.saved?.last)
        assertEquals(SelfState.OPTIMIZED, status.snapshot().self)
    }

    @Test fun failureIsReportedAsFailedResult() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val store = FakeCompileStatusStore()
        val status = CompileStatus(store, "0.2.1", false, { false }, { 1000L }, backgroundScope)
        runCurrent()
        compiler(prefs, status = status, script = {
            flow { emit(CompileEvent.Finished(false, true, io.github.devjardins.sidespeed.compile.CompileOutcome.Failure)) }
        }).maybeCompile()
        runCurrent()
        assertNull(prefs.selfCompileFlow.value)
        assertEquals(CompileLast(own, "SideSpeed", false, 1000L), store.saved?.last)
    }

    @Test fun failureWhileHelperIsDownIsReportedAsInterrupted() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val store = FakeCompileStatusStore()
        val status = CompileStatus(store, "0.2.1", false, { false }, { 1000L }, backgroundScope)
        runCurrent()
        var up = true
        val c = SelfCompiler(
            prefs = prefs,
            runCompile = { _, _ ->
                flow {
                    up = false
                    emit(CompileEvent.Finished(false, true, io.github.devjardins.sidespeed.compile.CompileOutcome.Failure))
                }
            },
            helperUp = { up },
            ownPackage = own,
            versionCode = 7L,
            lastUpdateTime = 4242L,
            debuggable = false,
            status = status,
        )
        c.maybeCompile()
        runCurrent()
        assertEquals(CompilePhase.INTERRUPTED, status.snapshot().phase)
    }

    @Test fun defersWhileLaunchSequenceRunningAndRunsOnNextLeave() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val calls = mutableListOf<Pair<String, CompileMode>>()
        val logs = mutableListOf<String>()
        var measuring: String? = "com.nuvio.tv"
        val c = compiler(prefs, calls = calls, logs = logs, measuring = { measuring })
        // A leave during a launch sequence (SideSpeed backgrounded by the timed launch) must not compile.
        c.maybeCompile()
        assertTrue(calls.isEmpty())
        assertNull(prefs.selfCompileFlow.value)
        assertEquals(listOf("self-compile deferred: launch sequence running"), logs)
        // The next normal leave after the sequence ends compiles and records.
        measuring = null
        c.maybeCompile()
        assertEquals(listOf(own to CompileMode.SPEED), calls)
        assertEquals(SelfCompile(7L, 4242L), prefs.selfCompileFlow.value)
    }

    @Test fun awaitIdleWaitsForRunningCompile() = runTest {
        val prefs = FakeSelfCompilePrefs()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val c = compiler(prefs, script = {
            flow {
                entered.complete(Unit)
                release.await()
                emit(CompileEvent.Finished(true, false, io.github.devjardins.sidespeed.compile.CompileOutcome.Success))
            }
        })
        val job = backgroundScope.launch { c.maybeCompile() }
        entered.await()
        var idle = false
        val waiter = backgroundScope.launch { c.awaitIdle(); idle = true }
        runCurrent()
        assertFalse("awaitIdle must wait while the self-compile is running", idle)
        release.complete(Unit)
        job.join()
        waiter.join()
        assertTrue(idle)
        assertEquals(SelfCompile(7L, 4242L), prefs.selfCompileFlow.value)
    }
}
