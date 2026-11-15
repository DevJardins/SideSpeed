package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.HelperPing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStrategyTest {
    private val apk = "/data/app/x/base.apk"

    private class FakePrefs(tls: Boolean = false) : Prefs {
        val tlsState = MutableStateFlow(tls)
        val ack = MutableStateFlow(true)
        override val lanWarningAcked: Flow<Boolean> = ack
        override suspend fun setLanWarningAcked(v: Boolean) { ack.value = v }
        override val pairedTls: Flow<Boolean> = tlsState
        override suspend fun setPairedTls(v: Boolean) { tlsState.value = v }
    }

    private fun strategy(
        adb: FakeAdbBackend,
        fire: Boolean = false,
        timeoutMs: Long = 30_000,
        tls: Boolean = false,
        probe: HelperProbe,
    ) = ConnectionStrategy(probe, adb, FakePrefs(tls), apk, fire, timeoutMs)

    @Test fun liveMatchingHelperIsReadyWithoutAdb() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        val r = strategy(adb) { HelperPing(1, apk) }.ensureReady()
        assertEquals(Readiness.HelperReady, r)
        assertTrue(adb.connectAttempts.isEmpty())
        assertTrue(adb.commands.isEmpty())
    }

    @Test fun staleHelperNeedsRestart() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        val r = strategy(adb) { HelperPing(1, "/old/base.apk") }.ensureReady()
        assertEquals(Readiness.HelperNeedsRestart, r)
        assertTrue(adb.connectAttempts.isEmpty())
    }

    @Test fun fireOsNeverTriesAdb() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        val r = strategy(adb, fire = true) { null }.ensureReady()
        assertTrue(r is Readiness.NeedsHelperStart)
        assertTrue(adb.connectAttempts.isEmpty())
    }

    @Test fun classicConnectLaunchesHelper() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(HelperLaunch.command()) { emptyList<String>() to 0 }
        var pings = 0
        val r = strategy(adb) { if (adb.commands.isNotEmpty() && ++pings >= 2) HelperPing(1, apk) else null }
            .ensureReady()
        assertEquals(Readiness.HelperReady, r)
        assertEquals(listOf<AdbTarget>(AdbTarget.Classic()), adb.connectAttempts)
        assertEquals(listOf(HelperLaunch.command()), adb.commands)
    }

    @Test fun helperThatNeverStartsReportsNotStarted() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend()
        val r = strategy(adb) { null }.ensureReady()
        assertEquals(Readiness.NeedsHelperStart("Helper did not start"), r)
    }

    @Test fun hangingConnectWaitsForAllowPrompt() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { hangConnect = true }
        val r = strategy(adb, timeoutMs = 1_000) { null }.ensureReady()
        assertEquals(Readiness.WaitingForAllowPrompt, r)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test fun nothingConnects() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { failConnect = RuntimeException("refused") }
        val r = strategy(adb) { null }.ensureReady()
        assertEquals(Readiness.NeedsHelperStart("ADB not reachable"), r)
    }

    @Test fun tlsTriedAfterClassicWhenPaired() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply {
            failConnectFor[AdbTarget.Classic()] = RuntimeException("refused")
        }
        strategy(adb, tls = true) { null }.ensureReady()
        assertEquals(listOf(AdbTarget.Classic(), AdbTarget.WirelessTls), adb.connectAttempts)
    }

    @Test fun restartKillsThenRelaunchesOnGoogleTv() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend()
        var launched = false
        val s = strategy(adb) { if (launched) HelperPing(1, apk) else HelperPing(1, "/old") }
        adb.on(HelperLaunch.command()) { launched = true; emptyList<String>() to 0 }
        val r = s.restartHelper()
        assertEquals(Readiness.HelperReady, r)
        assertEquals(listOf(ConnectionStrategy.KILL_COMMAND, HelperLaunch.command()), adb.commands)
    }

    @Test fun pcCommandsQuoteLaunchInSingleQuotes() {
        val launch = HelperLaunch.command()
        assertFalse(launch.contains("'"))
        val cmds = pcStartCommands("192.168.1.20", launch, killFirst = false)
        assertEquals(listOf("adb connect 192.168.1.20:5555", "adb shell '$launch'"), cmds)
        val k = pcStartCommands(null, launch, killFirst = true)
        assertEquals("adb connect <tv-ip>:5555", k[0])
        assertEquals("adb shell '${ConnectionStrategy.KILL_COMMAND}'", k[1])
        assertEquals("adb shell '$launch'", k[2])
    }
}
