package io.github.devjardins.sidespeed.ui.compile

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
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.tools.AnimationOriginals
import io.github.devjardins.sidespeed.tools.CleanupRecord
import io.github.devjardins.sidespeed.tools.CleanupThreshold as ToolsCleanupThreshold
import io.github.devjardins.sidespeed.tools.LaunchResult
import io.github.devjardins.sidespeed.tools.LaunchRunner
import io.github.devjardins.sidespeed.tools.RestorePending
import io.github.devjardins.sidespeed.tools.ToolsCommands
import io.github.devjardins.sidespeed.tools.ToolsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchTimeStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val pkg = PackageName.parse("com.nuvio.tv")!!
    private val launchCmd = "launch-time com.nuvio.tv com.nuvio.tv.Main"
    private val resetCmd = "pm compile -m verify -f com.nuvio.tv"
    private val speedCmd = "pm compile -m speed -f com.nuvio.tv"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

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

    private class FakeToolsStore : ToolsStore {
        override val cleanupThreshold: Flow<ToolsCleanupThreshold> =
            MutableStateFlow(ToolsCleanupThreshold.OFF)
        override suspend fun setCleanupThreshold(t: ToolsCleanupThreshold) {}
        override val lastCleanup: Flow<CleanupRecord?> = MutableStateFlow(null)
        override suspend fun setLastCleanup(r: CleanupRecord) {}
        private val launches = MutableStateFlow<Map<String, LaunchResult>>(emptyMap())
        val saved = mutableListOf<LaunchResult>()
        fun seed(r: LaunchResult) { launches.value = launches.value + (r.pkg to r) }
        override fun launchResult(pkg: String): Flow<LaunchResult?> = launches.map { it[pkg] }
        override fun launchResults(): Flow<Map<String, LaunchResult>> = launches
        override suspend fun setLaunchResult(r: LaunchResult) {
            saved += r
            launches.value = launches.value + (r.pkg to r)
        }
        override val animationOriginals: Flow<AnimationOriginals?> = MutableStateFlow(null)
        override suspend fun setAnimationOriginals(o: AnimationOriginals?) {}
        private val pending = MutableStateFlow<RestorePending?>(null)
        override val restorePending: Flow<RestorePending?> = pending
        override suspend fun setRestorePending(r: RestorePending?) { pending.value = r }
        override val updateDismissedVersion: Flow<String?> = MutableStateFlow(null)
        override suspend fun setUpdateDismissedVersion(v: String) {}
        override val updateLastCheckMs: Flow<Long> = MutableStateFlow(0L)
        override val updateLatestVersion: Flow<String?> = MutableStateFlow(null)
        override suspend fun setUpdateCheck(atMs: Long, latest: String?) {}
    }

    /** Stateful scripted runner: handler sees (command, per-command call index). */
    private class ScriptRunner(val handler: (String, Int) -> Pair<List<String>, Int>) : CommandRunner {
        val commands = mutableListOf<String>()
        val hangs = mutableSetOf<String>()
        private val counts = mutableMapOf<String, Int>()
        override fun shell(command: String): Flow<ShellEvent> = flow {
            commands += command
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

    private fun fullSequenceRunner(): ScriptRunner = ScriptRunner { command, n ->
        when {
            command == launchCmd && n < 3 -> totalTime(1900) to 0
            command == launchCmd -> totalTime(1100) to 0
            command == resetCmd -> listOf("Success") to 0
            command == speedCmd -> listOf("Success") to 0
            command == "bring-back" -> emptyList<String>() to 0
            command == "dumpsys package dexopt" ->
                listOf("  [com.nuvio.tv]", "      arm64: [status=speed]") to 0
            else -> emptyList<String>() to 127
        }
    }

    private fun runnerFor(
        script: ScriptRunner,
        store: FakeToolsStore,
        prefs: FakePrefs = FakePrefs(),
        scope: CoroutineScope,
    ) = LaunchRunner(
        commands = ToolsCommands(script) { true },
        store = store,
        prefs = prefs,
        compile = CompileRunner(script),
        scope = scope,
        awaitEngineIdle = {},
        launchActivityOf = { "com.nuvio.tv.Main" },
        versionOf = { PkgVersion(7, 100) },
    )

    private suspend fun uiCollect(script: ScriptRunner, pkg: PackageName, mode: CompileMode): CompileEvent.Finished {
        var fin: CompileEvent.Finished? = null
        CompileRunner(script).run(pkg, mode).collect { if (it is CompileEvent.Finished) fin = it }
        return fin!!
    }

    private fun vm(
        script: ScriptRunner,
        store: FakeToolsStore,
        prefs: FakePrefs = FakePrefs(),
        launches: LaunchRunner,
    ) = CompileViewModel(
        runner = CompileRunner(script),
        checker = StatusChecker(script),
        prefs = prefs,
        dispatcher = dispatcher,
        launches = launches,
    ) { 1234L }

    /** Flushes the test scheduler and the ViewModel's own dispatcher. */
    private suspend fun kotlinx.coroutines.test.TestScope.settle() {
        advanceUntilIdle()
        dispatcher.scheduler.advanceUntilIdle()
        advanceUntilIdle()
    }

    // Option B through the container-level runner.

    @Test fun measureStoresBeforeAndAfterAndRefreshesScreen() = runTest(timeout = 10.seconds) {
        val script = fullSequenceRunner()
        val store = FakeToolsStore()
        val r = runnerFor(script, store, scope = backgroundScope)
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        v.measure(pkg)!!.join(); settle()
        assertEquals(1, store.saved.size)
        assertEquals(1900L, store.saved.single().beforeMs)
        assertEquals(1100L, store.saved.single().afterMs)
        assertEquals(store.saved.single(), v.launch.value.stored)
        assertTrue(!v.launch.value.needsBefore)
        assertNull(v.launch.value.busy)
        assertNull(v.launch.value.error)
        // The sequence compile stamps its own start: the shown duration is the compile, not epoch time.
        assertEquals(0L, v.state.value.durationSeconds)
    }

    @Test fun measureFailureAfterResetStillRecompiles() = runTest(timeout = 10.seconds) {
        val script = ScriptRunner { command, _ ->
            when (command) {
                resetCmd -> listOf("Success") to 0
                launchCmd -> listOf("Error: Activity not started, unable to resolve Intent") to 0
                speedCmd -> listOf("Success") to 0
                else -> emptyList<String>() to 0
            }
        }
        val store = FakeToolsStore()
        val r = runnerFor(script, store, scope = backgroundScope)
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        v.measure(pkg)!!.join(); settle()
        assertTrue("recompile must run after a failed timing", speedCmd in script.commands)
        assertTrue(store.saved.isEmpty())
        assertEquals("Couldn't time this app", v.launch.value.error)
    }

    @Test fun measureResetFailureRestores() = runTest(timeout = 10.seconds) {
        val script = ScriptRunner { command, _ ->
            when (command) {
                resetCmd -> listOf("Failure") to 1
                speedCmd -> listOf("Success") to 0
                else -> emptyList<String>() to 0
            }
        }
        val store = FakeToolsStore()
        val r = runnerFor(script, store, scope = backgroundScope)
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        v.measure(pkg)!!.join(); settle()
        // The reset may have run on-device despite the failure, so the runner
        // recompiles instead of assuming nothing changed.
        assertTrue(speedCmd in script.commands)
        assertTrue(launchCmd !in script.commands)
        assertTrue(store.saved.isEmpty())
        assertTrue(v.launch.value.error != null)
    }

    @Test fun measureWithoutLaunchActivitySetsError() = runTest(timeout = 10.seconds) {
        val script = fullSequenceRunner()
        val store = FakeToolsStore()
        val r = LaunchRunner(
            commands = ToolsCommands(script) { true },
            store = store,
            prefs = FakePrefs(),
            compile = CompileRunner(script),
            scope = backgroundScope,
            awaitEngineIdle = {},
            launchActivityOf = { null },
            versionOf = { PkgVersion(7, 100) },
        )
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        assertTrue(!v.launch.value.launchable)
        v.measure(pkg)!!.join(); settle()
        assertTrue(script.commands.isEmpty())
        assertEquals("This app can't be opened from SideSpeed", v.launch.value.error)
    }

    @Test fun measureUsesPreviousCompileMode() = runTest(timeout = 10.seconds) {
        val profileCmd = "pm compile -m speed-profile -f com.nuvio.tv"
        val script = ScriptRunner { command, n ->
            when {
                command == launchCmd && n < 3 -> totalTime(1900) to 0
                command == launchCmd -> totalTime(1100) to 0
                command == resetCmd -> listOf("Success") to 0
                command == profileCmd -> listOf("Success") to 0
                else -> emptyList<String>() to 0
            }
        }
        val prefs = FakePrefs(CompileMode.SPEED_PROFILE)
        val store = FakeToolsStore()
        val r = runnerFor(script, store, prefs, backgroundScope)
        val v = vm(script, store, prefs, r)
        v.loadLaunch("com.nuvio.tv"); settle()
        v.measure(pkg)!!.join(); settle()
        assertTrue(profileCmd in script.commands)
        assertTrue(speedCmd !in script.commands)
    }

    @Test fun startIgnoredWhileRunnerBusy() = runTest(timeout = 10.seconds) {
        val script = fullSequenceRunner().apply { hangs += launchCmd }
        val store = FakeToolsStore()
        val r = runnerFor(script, store, scope = backgroundScope)
        val job = r.measure(pkg) { p, m -> uiCollect(script, p, m) }!!
        runCurrent()
        assertTrue(r.isBusy())
        val v = vm(script, store, launches = r)
        v.start(pkg, CompileMode.SPEED); settle()
        assertNull(v.state.value.result)
        assertTrue(v.state.value.log.isEmpty())
        job.cancel(); settle()
    }

    @Test fun loadLaunchShowsBusyWithoutClobbering() = runTest(timeout = 10.seconds) {
        val script = fullSequenceRunner().apply { hangs += launchCmd }
        val store = FakeToolsStore()
        store.seed(LaunchResult("com.nuvio.tv", 7, 100, 1900, 1100, 1))
        val r = runnerFor(script, store, scope = backgroundScope)
        val job = r.measure(pkg) { p, m -> uiCollect(script, p, m) }!!
        runCurrent()
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        assertTrue(v.launch.value.busy != null)
        assertTrue(v.launch.value.launchable)
        assertEquals(1900L, v.launch.value.stored?.beforeMs)
        assertTrue(!v.launch.value.needsBefore)
        job.cancel(); settle()
    }

    // Option A through the container-level runner.

    @Test fun startMaybeTimedMeasuresBeforeAndAfter() = runTest(timeout = 10.seconds) {
        val script = fullSequenceRunner()
        val store = FakeToolsStore()
        val r = runnerFor(script, store, scope = backgroundScope)
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        assertTrue(v.launch.value.needsBefore)
        v.startMaybeTimed(pkg, CompileMode.SPEED)!!.join()
        settle()
        assertEquals(1, store.saved.size)
        assertEquals(1900L, store.saved.single().beforeMs)
        assertEquals(1100L, store.saved.single().afterMs)
        assertTrue(v.state.value.result?.success == true)
    }

    @Test fun startMaybeTimedWithoutNeedCompilesNormally() = runTest(timeout = 10.seconds) {
        val script = fullSequenceRunner()
        val store = FakeToolsStore().apply {
            seed(LaunchResult("com.nuvio.tv", 7, 100, 1900, 1100, 1))
        }
        val r = runnerFor(script, store, scope = backgroundScope)
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        assertTrue(!v.launch.value.needsBefore)
        assertNull(v.startMaybeTimed(pkg, CompileMode.SPEED))
        settle()
        assertTrue(launchCmd !in script.commands)
        assertTrue(store.saved.isEmpty())
        assertTrue(v.state.value.result?.success == true)
    }

    @Test fun timedBeforeFailureSkipsCompile() = runTest(timeout = 10.seconds) {
        val script = ScriptRunner { command, _ ->
            when (command) {
                launchCmd -> listOf("Error: Activity not started") to 0
                speedCmd -> listOf("Success") to 0
                else -> emptyList<String>() to 0
            }
        }
        val store = FakeToolsStore()
        val r = runnerFor(script, store, scope = backgroundScope)
        val v = vm(script, store, launches = r)
        v.loadLaunch("com.nuvio.tv"); settle()
        v.startMaybeTimed(pkg, CompileMode.SPEED)!!.join()
        settle()
        assertTrue(speedCmd !in script.commands)
        assertTrue(store.saved.isEmpty())
        assertEquals("Couldn't time this app", v.launch.value.error)
    }

    // Pure display formatting.

    @Test fun formatLaunchResultFaster() {
        assertEquals("Launch: 1.9 s → 1.1 s (42% faster)", formatLaunchResult(1900, 1100, false))
    }

    @Test fun formatLaunchResultNoBefore() {
        assertEquals("Launch: 1.1 s", formatLaunchResult(null, 1100, false))
    }

    @Test fun formatLaunchResultStale() {
        assertEquals(
            "Launch: 1.9 s → 1.1 s (42% faster) · from an older version",
            formatLaunchResult(1900, 1100, true),
        )
    }

    @Test fun formatLaunchResultNoFaster() {
        assertEquals("Launch: 1.0 s → 1.2 s (no faster)", formatLaunchResult(1000, 1200, false))
    }

    @Test fun formatLaunchResultAboutTheSame() {
        assertEquals("Launch: 0.5 s → 0.5 s (about the same)", formatLaunchResult(505, 500, false))
        assertEquals("Launch: 1.0 s → 1.0 s (about the same)", formatLaunchResult(1000, 1000, false))
        assertEquals("Launch: 1.0 s → 1.0 s (about the same)", formatLaunchResult(1000, 951, false))
        assertEquals("Launch: 1.0 s → 1.0 s (about the same)", formatLaunchResult(1000, 950, false))
    }

    @Test fun formatLaunchResultBoundaryStillFaster() {
        assertEquals("Launch: 2.0 s → 1.9 s (5% faster)", formatLaunchResult(2000, 1900, false))
    }

    @Test fun formatLaunchMsOneDecimal() {
        assertEquals("1.9 s", formatLaunchMs(1900))
        assertEquals("1.1 s", formatLaunchMs(1100))
    }
}
