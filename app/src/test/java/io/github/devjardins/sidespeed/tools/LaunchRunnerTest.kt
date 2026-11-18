package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.ShellEvent
import io.github.devjardins.sidespeed.apps.PkgVersion
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchRunnerTest {
    private val pkg = PackageName.parse("com.nuvio.tv")!!
    private val launchCmd = "launch-time com.nuvio.tv com.nuvio.tv.Main"
    private val resetCmd = "pm compile -m verify -f com.nuvio.tv"
    private val speedCmd = "pm compile -m speed -f com.nuvio.tv"

    private class FakePrefs(var mode: CompileMode? = null) : Prefs {
        override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setLanWarningAcked(v: Boolean) {}
        override val pairedTls: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setPairedTls(v: Boolean) {}
        override fun lastCompile(pkg: String): Flow<LastCompile?> =
            MutableStateFlow(mode?.let { LastCompile(1L, it) })
        override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
        override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
        override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
        override suspend fun setSelfCompile(v: SelfCompile) {}
    }

    private class FakeToolsStore(val events: MutableList<String> = mutableListOf()) : ToolsStore {
        override val cleanupThreshold: Flow<CleanupThreshold> = MutableStateFlow(CleanupThreshold.OFF)
        override suspend fun setCleanupThreshold(t: CleanupThreshold) {}
        override val lastCleanup: Flow<CleanupRecord?> = MutableStateFlow(null)
        override suspend fun setLastCleanup(r: CleanupRecord) {}
        private val launches = MutableStateFlow<Map<String, LaunchResult>>(emptyMap())
        val saved = mutableListOf<LaunchResult>()
        override fun launchResult(pkg: String): Flow<LaunchResult?> = launches.map { it[pkg] }
        override fun launchResults(): Flow<Map<String, LaunchResult>> = launches
        override suspend fun setLaunchResult(r: LaunchResult) {
            saved += r
            launches.value = launches.value + (r.pkg to r)
        }
        override val animationOriginals: Flow<AnimationOriginals?> = MutableStateFlow(null)
        override suspend fun setAnimationOriginals(o: AnimationOriginals?) {}
        val pending = MutableStateFlow<RestorePending?>(null)
        override val restorePending: Flow<RestorePending?> = pending
        var failWrites = false
        override suspend fun setRestorePending(r: RestorePending?) {
            events += if (r == null) "pending-clear" else "pending-write"
            if (failWrites) throw RuntimeException("disk")
            pending.value = r
        }
        override val updateDismissedVersion: Flow<String?> = MutableStateFlow(null)
        override suspend fun setUpdateDismissedVersion(v: String) {}
        override val updateLastCheckMs: Flow<Long> = MutableStateFlow(0L)
        override val updateLatestVersion: Flow<String?> = MutableStateFlow(null)
        override suspend fun setUpdateCheck(atMs: Long, latest: String?) {}
    }

    private class ScriptRunner(
        val handler: (String, Int) -> Pair<List<String>, Int>,
        val events: MutableList<String> = mutableListOf(),
    ) : CommandRunner {
        val commands = mutableListOf<String>()
        val hangs = mutableSetOf<String>()
        private val counts = mutableMapOf<String, Int>()
        override fun shell(command: String): Flow<ShellEvent> = flow {
            commands += command
            events += command
            if (command in hangs) awaitCancellation()
            val n = counts.getOrDefault(command, 0).also { counts[command] = it + 1 }
            val (lines, exit) = handler(command, n)
            lines.forEach { emit(ShellEvent.Line(it)) }
            emit(ShellEvent.Exit(exit))
        }
    }

    private fun totalTime(ms: Long) = listOf(
        "Starting: Intent { cmp=com.nuvio.tv/.Main }",
        "Status: ok",
        "TotalTime: $ms",
    )

    private fun fullScript(): ScriptRunner = ScriptRunner(handler = { command, n ->
        when {
            command == launchCmd && n < 3 -> totalTime(1900) to 0
            command == launchCmd -> totalTime(1100) to 0
            command == resetCmd -> listOf("Success") to 0
            command == speedCmd -> listOf("Success") to 0
            command == "bring-back" -> emptyList<String>() to 0
            else -> emptyList<String>() to 127
        }
    })

    private suspend fun uiCompile(runner: CommandRunner, pkg: PackageName, mode: CompileMode): CompileEvent.Finished {
        var fin: CompileEvent.Finished? = null
        CompileRunner(runner).run(pkg, mode).collect { if (it is CompileEvent.Finished) fin = it }
        return fin!!
    }

    private fun runner(
        script: ScriptRunner,
        store: ToolsStore = FakeToolsStore(),
        prefs: FakePrefs = FakePrefs(),
        scope: CoroutineScope,
        awaitEngineIdle: suspend (String) -> Unit = {},
    ) = LaunchRunner(
        commands = ToolsCommands(script) { true },
        store = store,
        prefs = prefs,
        compile = CompileRunner(script),
        scope = scope,
        awaitEngineIdle = awaitEngineIdle,
        launchActivityOf = { "com.nuvio.tv.Main" },
        versionOf = { PkgVersion(7, 100) },
    )

    @Test fun measureStoresBeforeAndAfter() = runTest(timeout = 10.seconds) {
        val script = fullScript()
        val store = FakeToolsStore()
        val r = runner(script, store, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        assertEquals(1, store.saved.size)
        val saved = store.saved.single()
        assertEquals(1900L, saved.beforeMs)
        assertEquals(1100L, saved.afterMs)
        assertEquals(7L, saved.versionCode)
        assertNull(store.pending.value)
        assertTrue(r.progress.value.isEmpty())
    }

    @Test fun cancelMidTimingStillRecompiles() = runTest(timeout = 10.seconds) {
        val script = fullScript().apply { hangs += launchCmd }
        val store = FakeToolsStore()
        val r = runner(script, store, scope = backgroundScope)
        val job = r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!
        runCurrent()
        assertEquals(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7), store.pending.value)
        job.cancel()
        runCurrent()
        assertTrue("cancelled sequence must still recompile", speedCmd in script.commands)
        assertNull(store.pending.value)
        assertTrue(r.progress.value.isEmpty())
    }

    @Test fun failedRestoreKeepsPendingRecord() = runTest(timeout = 10.seconds) {
        val script = ScriptRunner(handler = { command, n ->
            when {
                command == launchCmd && n < 3 -> totalTime(1900) to 0
                command == launchCmd -> totalTime(1100) to 0
                command == resetCmd -> listOf("Success") to 0
                command == speedCmd -> listOf("Failure") to 1
                else -> emptyList<String>() to 0
            }
        })
        val store = FakeToolsStore()
        val r = runner(script, store, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        assertEquals(2, script.commands.count { it == speedCmd })
        assertEquals(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7), store.pending.value)
        assertTrue(r.progress.value["com.nuvio.tv"]?.error != null)
    }

    @Test fun restorerRecompilesAndClearsOnNextStart() = runTest(timeout = 10.seconds) {
        val script = fullScript()
        val store = FakeToolsStore()
        store.setRestorePending(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7))
        val r = runner(script, store, scope = backgroundScope)
        r.restorePending()
        assertTrue(speedCmd in script.commands)
        assertNull(store.pending.value)
    }

    @Test fun restorerKeepsPendingWhenHelperDown() = runTest(timeout = 10.seconds) {
        val script = ScriptRunner(handler = { _, _ -> listOf("Failure") to 1 })
        val store = FakeToolsStore()
        store.setRestorePending(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7))
        val r = runner(script, store, scope = backgroundScope)
        r.restorePending()
        assertEquals(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7), store.pending.value)
    }

    @Test fun measureWaitsForEngineBeforeReset() = runTest(timeout = 10.seconds) {
        val script = fullScript()
        val store = FakeToolsStore()
        val gate = CompletableDeferred<Unit>()
        var waitedFor: String? = null
        val r = runner(script, store, scope = backgroundScope, awaitEngineIdle = { pkg ->
            waitedFor = pkg
            gate.await()
        })
        var finished = false
        val job = r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!
        runCurrent()
        assertEquals("com.nuvio.tv", waitedFor)
        assertEquals("Waiting for SideSpeed to finish optimizing this app…", r.progress.value["com.nuvio.tv"]?.busy)
        assertTrue("no reset while the engine is busy", resetCmd !in script.commands)
        gate.complete(Unit)
        job.join()
        finished = true
        assertTrue(finished)
        assertEquals(1, store.saved.size)
        assertNull(r.progress.value["com.nuvio.tv"])
    }

    @Test fun restorerIsNoOpWithoutPending() = runTest(timeout = 10.seconds) {
        val script = fullScript()
        val r = runner(script, scope = backgroundScope)
        r.restorePending()
        assertTrue(script.commands.isEmpty())
    }

    @Test fun secondStartWhileBusyIsRejected() = runTest(timeout = 10.seconds) {
        val script = fullScript().apply { hangs += launchCmd }
        val r = runner(script, scope = backgroundScope)
        val first = r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!
        runCurrent()
        assertTrue(r.isBusy())
        assertEquals(null, r.measure(pkg) { p, m -> uiCompile(script, p, m) })
        assertEquals(null, r.startTimed(pkg, CompileMode.SPEED) { p, m -> uiCompile(script, p, m) })
        first.cancel()
        runCurrent()
        assertTrue(!r.isBusy())
    }

    @Test fun resetAndRestoreFailureKeepsPending() = runTest(timeout = 10.seconds) {
        val script = ScriptRunner(handler = { command, _ ->
            when (command) {
                resetCmd -> listOf("Failure") to 1
                else -> emptyList<String>() to 0
            }
        })
        val store = FakeToolsStore()
        val r = runner(script, store, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        // Restore was attempted (the reset may have run) but also failed: retry later.
        assertTrue(speedCmd in script.commands)
        assertEquals(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7), store.pending.value)
        assertTrue(store.saved.isEmpty())
        assertTrue(r.progress.value["com.nuvio.tv"]?.error != null)
    }

    @Test fun pendingIsWrittenBeforeReset() = runTest(timeout = 10.seconds) {
        val events = mutableListOf<String>()
        val script = ScriptRunner({ command, n ->
            when {
                command == launchCmd && n < 3 -> totalTime(1900) to 0
                command == launchCmd -> totalTime(1100) to 0
                command == resetCmd -> listOf("Success") to 0
                command == speedCmd -> listOf("Success") to 0
                else -> emptyList<String>() to 0
            }
        }, events)
        val store = FakeToolsStore(events)
        val r = runner(script, store, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        val writeIdx = events.indexOf("pending-write")
        val resetIdx = events.indexOf(resetCmd)
        assertTrue("pending must land before reset runs", writeIdx >= 0 && writeIdx < resetIdx)
    }

    @Test fun resetFailureKeepsPendingAndRestores() = runTest(timeout = 10.seconds) {
        val events = mutableListOf<String>()
        val script = ScriptRunner({ command, _ ->
            when (command) {
                resetCmd -> listOf("Failure") to 1
                speedCmd -> listOf("Success") to 0
                else -> emptyList<String>() to 0
            }
        }, events)
        val store = FakeToolsStore(events)
        val r = runner(script, store, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        // The reset may have run on-device despite the failure (e.g. HelperDown
        // after a mid-command disconnect), so the pending record stays and the
        // finally recompiles; a never-reset app is harmlessly recompiled.
        assertEquals(listOf("pending-write", resetCmd, speedCmd, "pending-clear"), events)
        assertNull(store.pending.value)
        assertTrue(store.saved.isEmpty())
        assertTrue(r.progress.value["com.nuvio.tv"]?.error != null)
    }

    @Test fun cancelWithMissingRecordRestoresFromMemory() = runTest(timeout = 10.seconds) {
        val script = fullScript().apply { hangs += launchCmd }
        val store = FakeToolsStore().apply {
            // The store never holds the record: the finally must fall back to memory.
            pending.value = null
        }
        val dropping = object : ToolsStore by store {
            override suspend fun setRestorePending(r: RestorePending?) {}
            override val restorePending: Flow<RestorePending?> = MutableStateFlow(null)
        }
        val r = runner(script, dropping, scope = backgroundScope)
        val job = r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue("cancelled sequence must still recompile", speedCmd in script.commands)
        assertNull(store.pending.value)
    }

    @Test fun pendingWriteFailureStillMeasures() = runTest(timeout = 10.seconds) {
        val script = fullScript()
        val store = FakeToolsStore().apply { failWrites = true }
        val r = runner(script, store, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        assertEquals(1, store.saved.size)
        assertTrue(speedCmd in script.commands)
    }

    @Test fun clearWriteFailureKeepsPendingForRetry() = runTest(timeout = 10.seconds) {
        val script = fullScript()
        val store = FakeToolsStore()
        val clearing = object : ToolsStore by store {
            override suspend fun setRestorePending(r: RestorePending?) {
                if (r == null) throw RuntimeException("disk")
                store.pending.value = r
            }
            override val restorePending: Flow<RestorePending?> = store.pending
        }
        val r = runner(script, clearing, scope = backgroundScope)
        r.measure(pkg) { p, m -> uiCompile(script, p, m) }!!.join()
        assertEquals(1, store.saved.size)
        assertEquals(RestorePending("com.nuvio.tv", CompileMode.SPEED, 7), store.pending.value)
    }
}
