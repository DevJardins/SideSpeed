package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.data.LastCompile
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
import java.io.IOException
import java.net.ConnectException
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
        override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(null)
        override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
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
        val adb = FakeAdbBackend().apply { failConnect = RuntimeException("handshake failed") }
        val r = strategy(adb) { null }.ensureReady()
        assertEquals(Readiness.NeedsHelperStart("ADB not reachable"), r)
        assertEquals(StartCause.OTHER, (r as Readiness.NeedsHelperStart).cause)
    }

    @Test fun closedPortIsReportedAsRefused() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { failConnect = ConnectException("Connection refused") }
        val r = strategy(adb) { null }.ensureReady()
        assertEquals(Readiness.NeedsHelperStart("ADB not reachable", StartCause.REFUSED), r)
    }

    @Test fun wrappedConnectExceptionIsRefused() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { failConnect = IOException("wrapped", ConnectException("refused")) }
        val r = strategy(adb) { null }.ensureReady() as Readiness.NeedsHelperStart
        assertEquals(StartCause.REFUSED, r.cause)
    }

    @Test fun classicRefusedStaysRefusedWhenTlsAlsoFails() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply {
            failConnectFor[AdbTarget.Classic()] = ConnectException("refused")
            failConnectFor[AdbTarget.WirelessTls] = RuntimeException("no mdns")
        }
        val r = strategy(adb, tls = true) { null }.ensureReady() as Readiness.NeedsHelperStart
        assertEquals(StartCause.REFUSED, r.cause)
    }

    @Test fun onLaunchFiresAfterConnectBeforeLaunchCommand() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(HelperLaunch.command()) { emptyList<String>() to 0 }
        var commandsAtLaunch: Int? = null
        strategy(adb) { if (adb.commands.isNotEmpty()) HelperPing(1, apk) else null }
            .ensureReady(onLaunch = { commandsAtLaunch = adb.commands.size })
        assertEquals(0, commandsAtLaunch)
    }

    @Test fun onLaunchNotCalledWhenRefused() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { failConnect = ConnectException("refused") }
        var launched = false
        strategy(adb) { null }.ensureReady(onLaunch = { launched = true })
        assertFalse(launched)
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

    @Test fun stalledLaunchStreamTimesOut() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend().apply { hangShellFor += HelperLaunch.command() }
        val r = strategy(adb) { null }.ensureReady()
        assertEquals(Readiness.NeedsHelperStart("Helper did not start"), r)
        assertEquals(ConnectionStrategy.SHELL_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test fun stalledKillTimesOutOnRestart() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend().apply { hangShellFor += ConnectionStrategy.KILL_COMMAND }
        val r = strategy(adb) { HelperPing(1, "/old") }.restartHelper()
        assertEquals(Readiness.NeedsHelperStart("Helper did not start"), r)
        assertEquals(listOf(ConnectionStrategy.KILL_COMMAND), adb.commands)
    }

    @Test fun fireOsPcCommandsUseClassicAdb() {
        val launch = HelperLaunch.command()
        assertFalse(launch.contains("'"))
        val c = pcStartCommands(DeviceKind.FIRE_OS, "192.168.1.20", launch, killFirst = false)
        assertEquals(listOf("adb connect 192.168.1.20:5555"), c.connect)
        assertEquals(listOf("adb shell '$launch'"), c.run)
        assertEquals(listOf(launch), c.shellPaste)
    }

    @Test fun killFirstPrecedesLaunch() {
        val launch = HelperLaunch.command()
        val c = pcStartCommands(DeviceKind.FIRE_OS, null, launch, killFirst = true)
        assertEquals(listOf("adb connect <tv-ip>:5555"), c.connect)
        assertEquals(listOf("adb shell '${ConnectionStrategy.KILL_COMMAND}'", "adb shell '$launch'"), c.run)
        assertEquals(listOf(ConnectionStrategy.KILL_COMMAND, launch), c.shellPaste)
    }

    @Test fun googleTvPcCommandsUseWirelessDebuggingPorts() {
        val launch = HelperLaunch.command()
        val c = pcStartCommands(DeviceKind.GOOGLE_TV, "192.168.1.30", launch, killFirst = false)
        assertEquals(
            listOf("adb pair 192.168.1.30:<pairing port>", "adb connect 192.168.1.30:<port>"),
            c.connect,
        )
        assertTrue(c.connect.none { it.contains(":5555") })
        assertEquals(listOf("adb shell '$launch'"), c.run)
        assertEquals(listOf(launch), c.shellPaste)
        val noIp = pcStartCommands(DeviceKind.GOOGLE_TV, null, launch, killFirst = false)
        assertEquals("adb pair <tv-ip>:<pairing port>", noIp.connect[0])
    }

    @Test fun deviceKindFromFlag() {
        assertEquals(DeviceKind.FIRE_OS, DeviceKind.of(isFireOs = true))
        assertEquals(DeviceKind.GOOGLE_TV, DeviceKind.of(isFireOs = false))
    }
}
