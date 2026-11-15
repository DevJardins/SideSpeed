package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileRunner
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.status.StatusChecker
import io.github.devjardins.sidespeed.ui.compile.CompileUiState
import io.github.devjardins.sidespeed.ui.compile.CompileViewModel
import io.github.devjardins.sidespeed.ui.compile.resultBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    }

    private fun fake() = FakeAdbBackend().apply {
        on(cmd) { listOf("Success") to 0 }
        on("dumpsys package dexopt") { listOf("  [com.nuvio.tv]", "      arm64: [status=speed]") to 0 }
    }

    private fun vm(adb: FakeAdbBackend, prefs: FakePrefs = FakePrefs()) =
        CompileViewModel(CompileRunner(adb), StatusChecker(adb), prefs, dispatcher) { 1234L }

    @Test fun successLogsSavesAndShowsBadge() = runTest(timeout = 10.seconds) {
        val prefs = FakePrefs()
        val v = vm(fake(), prefs)
        v.start(pkg, CompileMode.SPEED); advanceUntilIdle(); dispatcher.scheduler.advanceUntilIdle()
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
        v.start(pkg, CompileMode.SPEED)
        assertTrue(v.state.value.running)
        v.start(pkg, CompileMode.SPEED)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, adb.commands.count { it == cmd })
    }

    @Test fun failureDoesNotSaveOrBadge() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(cmd) { listOf("Failure: nope") to 1 }
        val prefs = FakePrefs()
        val v = vm(adb, prefs)
        v.start(pkg, CompileMode.SPEED); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, v.state.value.result?.success)
        assertTrue(prefs.saved.isEmpty())
        assertEquals(null, v.state.value.badge)
    }

    @Test fun connectionLostIsReported() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.dropAfterLines(cmd, 0)
        val v = vm(adb)
        v.start(pkg, CompileMode.SPEED); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Connection lost", v.state.value.result?.message)
        assertNotNull(v.state.value.result)
    }

    @Test fun resetClearsPreviousRun() = runTest(timeout = 10.seconds) {
        val v = vm(fake())
        v.start(pkg, CompileMode.SPEED); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.log.isNotEmpty())
        v.reset()
        assertEquals(CompileUiState(), v.state.value)
    }

    @Test fun resetIgnoredWhileRunning() = runTest(timeout = 10.seconds) {
        val v = vm(fake())
        v.start(pkg, CompileMode.SPEED)
        v.reset()
        assertTrue(v.state.value.running)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, v.state.value.result?.success)
    }

    @Test fun prefsFailureDoesNotLeaveRunning() = runTest(timeout = 10.seconds) {
        val prefs = object : Prefs by FakePrefs() {
            override suspend fun setLastCompile(pkg: String, value: LastCompile) { throw RuntimeException("disk") }
        }
        val v = CompileViewModel(CompileRunner(fake()), StatusChecker(fake()), prefs, dispatcher) { 1L }
        v.start(pkg, CompileMode.SPEED); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.running)
        assertEquals(true, v.state.value.result?.success)
    }

    @Test fun logIsCapped() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(cmd) { (List(3000) { "l$it" } + "Success") to 0 }
        val v = vm(adb)
        v.start(pkg, CompileMode.SPEED); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2000, v.state.value.log.size)
        assertEquals("Success", v.state.value.log.last())
    }
}

class ResultBannerTest {
    @Test fun sameMessageIsNotRepeated() {
        assertEquals("Success", resultBanner(true, "Success"))
        assertEquals("Failure", resultBanner(false, "Failure"))
        assertEquals("Success", resultBanner(true, ""))
    }

    @Test fun differentMessageIsAppended() {
        assertEquals("Failure: Connection lost", resultBanner(false, "Connection lost"))
        assertEquals("Success: done", resultBanner(true, "done"))
    }
}
