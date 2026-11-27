package io.github.devjardins.sidespeed.adb

import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.HelperPing
import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import io.github.devjardins.sidespeed.testing.english
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
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
        override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
        override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
        override suspend fun setSelfCompile(v: SelfCompile) {}
    }

    private fun strategy(
        adb: FakeAdbBackend,
        fire: Boolean = false,
        timeoutMs: Long = 30_000,
        tls: Boolean = false,
        pkg: String = SideSpeedPackages.RELEASE,
        probe: HelperProbe,
    ) = ConnectionStrategy(probe, adb, FakePrefs(tls), apk, fire, pkg, timeoutMs)

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
        assertEquals("Fire OS blocks on-box ADB", (r as Readiness.NeedsHelperStart).reason.english())
        assertTrue(adb.connectAttempts.isEmpty())
    }

    @Test fun classicConnectLaunchesHelper() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        adb.on(HelperLaunch.command(SideSpeedPackages.RELEASE)) { emptyList<String>() to 0 }
        var pings = 0
        val r = strategy(adb) { if (adb.commands.isNotEmpty() && ++pings >= 2) HelperPing(1, apk) else null }
            .ensureReady()
        assertEquals(Readiness.HelperReady, r)
        assertEquals(listOf<AdbTarget>(AdbTarget.Classic()), adb.connectAttempts)
        assertEquals(listOf(HelperLaunch.command(SideSpeedPackages.RELEASE)), adb.commands)
    }

    @Test fun helperThatNeverStartsReportsNotStarted() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend()
        val r = strategy(adb) { null }.ensureReady()
        assertEquals("Helper did not start", (r as Readiness.NeedsHelperStart).reason.english())
        assertEquals(StartCause.OTHER, r.cause)
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
        assertEquals("ADB not reachable", (r as Readiness.NeedsHelperStart).reason.english())
        assertEquals(StartCause.OTHER, r.cause)
    }

    @Test fun closedPortIsReportedAsRefused() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { failConnect = ConnectException("Connection refused") }
        val r = strategy(adb) { null }.ensureReady()
        assertEquals("ADB not reachable", (r as Readiness.NeedsHelperStart).reason.english())
        assertEquals(StartCause.REFUSED, r.cause)
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
        adb.on(HelperLaunch.command(SideSpeedPackages.RELEASE)) { emptyList<String>() to 0 }
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
        adb.on(HelperLaunch.command(SideSpeedPackages.RELEASE)) { launched = true; emptyList<String>() to 0 }
        val r = s.restartHelper()
        assertEquals(Readiness.HelperReady, r)
        assertEquals(listOf(HelperLaunch.killCommand(SideSpeedPackages.RELEASE), HelperLaunch.command(SideSpeedPackages.RELEASE)), adb.commands)
    }

    @Test fun stalledLaunchStreamTimesOut() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend().apply { hangShellFor += HelperLaunch.command(SideSpeedPackages.RELEASE) }
        val r = strategy(adb) { null }.ensureReady()
        assertEquals("Helper did not start", (r as Readiness.NeedsHelperStart).reason.english())
        assertEquals(ConnectionStrategy.SHELL_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test fun stalledKillTimesOutOnRestart() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend().apply { hangShellFor += HelperLaunch.killCommand(SideSpeedPackages.RELEASE) }
        val r = strategy(adb) { HelperPing(1, "/old") }.restartHelper()
        assertEquals("Helper did not start", (r as Readiness.NeedsHelperStart).reason.english())
        assertEquals(listOf(HelperLaunch.killCommand(SideSpeedPackages.RELEASE)), adb.commands)
    }

    @Test fun launchUsesOwnPackage() = runTest(timeout = 20.seconds) {
        val adb = FakeAdbBackend()
        val debugCmd = HelperLaunch.command(SideSpeedPackages.DEBUG)
        val debugKill = HelperLaunch.killCommand(SideSpeedPackages.DEBUG)
        adb.on(debugCmd) { emptyList<String>() to 0 }
        val r = strategy(adb, pkg = SideSpeedPackages.DEBUG) {
            if (adb.commands.contains(debugCmd)) HelperPing(1, apk) else null
        }.ensureReady()
        assertEquals(Readiness.HelperReady, r)
        assertEquals(listOf(debugCmd), adb.commands)

        val adbRestart = FakeAdbBackend()
        var restarted = false
        adbRestart.on(debugCmd) { restarted = true; emptyList<String>() to 0 }
        val r2 = strategy(adbRestart, pkg = SideSpeedPackages.DEBUG) {
            if (restarted) HelperPing(1, apk) else HelperPing(1, "/old")
        }.restartHelper()
        assertEquals(Readiness.HelperReady, r2)
        assertEquals(listOf(debugKill, debugCmd), adbRestart.commands)
    }

    @Test fun fireOsPcCommandsUseClassicAdb() {
        val launch = HelperLaunch.command(SideSpeedPackages.RELEASE)
        assertFalse(launch.contains("'"))
        val c = pcStartCommands(DeviceKind.FIRE_OS, "192.168.1.20", SideSpeedPackages.RELEASE, killFirst = false)
        assertEquals(listOf("adb connect 192.168.1.20:5555"), c.connect)
        assertEquals(listOf("adb shell '$launch'"), c.run)
        assertEquals(listOf(launch), c.shellPaste)
    }

    @Test fun killFirstPrecedesLaunch() {
        val launch = HelperLaunch.command(SideSpeedPackages.RELEASE)
        val kill = HelperLaunch.killCommand(SideSpeedPackages.RELEASE)
        val c = pcStartCommands(DeviceKind.FIRE_OS, null, SideSpeedPackages.RELEASE, killFirst = true)
        assertEquals(listOf("adb connect <tv-ip>:5555"), c.connect)
        assertEquals(listOf("adb shell '$kill'", "adb shell '$launch'"), c.run)
        assertEquals(listOf(kill, launch), c.shellPaste)
    }

    @Test fun googleTvPcCommandsUseWirelessDebuggingPorts() {
        val launch = HelperLaunch.command(SideSpeedPackages.RELEASE)
        val c = pcStartCommands(DeviceKind.GOOGLE_TV, "192.168.1.30", SideSpeedPackages.RELEASE, killFirst = false)
        assertEquals(
            listOf("adb pair 192.168.1.30:<pairing port>", "adb connect 192.168.1.30:<port>"),
            c.connect,
        )
        assertTrue(c.connect.none { it.contains(":5555") })
        assertEquals(listOf("adb shell '$launch'"), c.run)
        assertEquals(listOf(launch), c.shellPaste)
        val noIp = pcStartCommands(DeviceKind.GOOGLE_TV, null, SideSpeedPackages.RELEASE, killFirst = false)
        assertEquals("adb pair <tv-ip>:<pairing port>", noIp.connect[0])
    }

    @Test fun debugPcCommandsUseDebugPackage() {
        val launch = HelperLaunch.command(SideSpeedPackages.DEBUG)
        val kill = HelperLaunch.killCommand(SideSpeedPackages.DEBUG)
        val c = pcStartCommands(DeviceKind.GOOGLE_TV, "192.168.1.30", SideSpeedPackages.DEBUG, killFirst = true)
        assertEquals(listOf("adb shell '$kill'", "adb shell '$launch'"), c.run)
        assertEquals(listOf(kill, launch), c.shellPaste)
    }

    @Test fun deviceKindFromFlag() {
        assertEquals(DeviceKind.FIRE_OS, DeviceKind.of(isFireOs = true))
        assertEquals(DeviceKind.GOOGLE_TV, DeviceKind.of(isFireOs = false))
    }

    @Test fun concurrentEnsureReadyRunsOnce() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply { connectDelayMs = 200 }
        adb.on(HelperLaunch.command(SideSpeedPackages.RELEASE)) { emptyList<String>() to 0 }
        val s = ConnectionStrategy(
            helper = HelperProbe { if (adb.commands.isNotEmpty()) HelperPing(1, apk) else null },
            adb = adb,
            prefs = FakePrefs(),
            expectedApk = apk,
            isFireOs = false,
            pkg = SideSpeedPackages.RELEASE,
            adbMutex = Mutex(),
        )
        val first = async { s.ensureReady() }
        val second = async { s.ensureReady() }
        assertEquals(Readiness.HelperReady, first.await())
        assertEquals(Readiness.HelperReady, second.await())
        assertEquals(1, adb.connectAttempts.size)
        assertEquals(1, adb.closeCount)
        assertFalse(adb.isConnected)
    }

    @Test fun classicRefusedWithTlsMdnsMissSetsMdnsMissWhileKeepingRefusedCause() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend().apply {
            failConnectFor[AdbTarget.Classic()] = ConnectException("refused")
            failConnectFor[AdbTarget.WirelessTls] = AdbMdnsMissException()
        }
        val r = strategy(adb, tls = true) { null }.ensureReady() as Readiness.NeedsHelperStart
        assertEquals(StartCause.REFUSED, r.cause)
        assertTrue(r.mdnsMiss)
    }

    @Test fun adbClosedOnSuccessShellFailureAndPollTimeout() = runTest(timeout = 20.seconds) {
        // (a) HelperReady
        val adbOk = FakeAdbBackend()
        adbOk.on(HelperLaunch.command(SideSpeedPackages.RELEASE)) { emptyList<String>() to 0 }
        assertEquals(
            Readiness.HelperReady,
            strategy(adbOk) { if (adbOk.commands.isNotEmpty()) HelperPing(1, apk) else null }.ensureReady(),
        )
        assertEquals(1, adbOk.closeCount)
        assertFalse(adbOk.isConnected)

        // (b) hangShellFor timeout
        val adbHang = FakeAdbBackend().apply { hangShellFor += HelperLaunch.command(SideSpeedPackages.RELEASE) }
        assertTrue(strategy(adbHang) { null }.ensureReady() is Readiness.NeedsHelperStart)
        assertEquals(1, adbHang.closeCount)
        assertFalse(adbHang.isConnected)

        // (c) helper.ping() == null poll timeout
        val adbPoll = FakeAdbBackend()
        assertTrue(strategy(adbPoll) { null }.ensureReady() is Readiness.NeedsHelperStart)
        assertEquals(1, adbPoll.closeCount)
        assertFalse(adbPoll.isConnected)
    }

    @Test fun classicConnectDoesNotSetPairedTls() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        val prefs = FakePrefs(tls = false)
        adb.on(HelperLaunch.command(SideSpeedPackages.RELEASE)) { emptyList<String>() to 0 }
        val s = ConnectionStrategy(
            helper = HelperProbe { if (adb.commands.isNotEmpty()) HelperPing(1, apk) else null },
            adb = adb,
            prefs = prefs,
            expectedApk = apk,
            isFireOs = false,
            pkg = SideSpeedPackages.RELEASE,
        )
        assertEquals(Readiness.HelperReady, s.ensureReady())
        assertEquals(listOf<AdbTarget>(AdbTarget.Classic()), adb.connectAttempts)
        assertFalse(prefs.tlsState.value)
    }

    @Test fun probeWirelessTlsSucceedsAndClosesAdb() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        val prefs = FakePrefs(tls = false)
        val s = ConnectionStrategy(
            helper = HelperProbe { null },
            adb = adb,
            prefs = prefs,
            expectedApk = apk,
            isFireOs = false,
            pkg = SideSpeedPackages.RELEASE,
        )
        assertTrue(s.probeWirelessTls())
        assertEquals(listOf<AdbTarget>(AdbTarget.WirelessTls), adb.connectAttempts)
        assertEquals(1, adb.closeCount)
        assertFalse(adb.isConnected)
        assertFalse(prefs.tlsState.value)
    }

    @Test fun probeWirelessTlsReturnsFalseAndClosesOnFailureAndTimeout() = runTest(timeout = 10.seconds) {
        val failing = FakeAdbBackend().apply {
            failConnectFor[AdbTarget.WirelessTls] = IOException("rejected")
        }
        assertFalse(strategy(failing, tls = false) { null }.probeWirelessTls())
        assertEquals(1, failing.closeCount)
        assertFalse(failing.isConnected)

        val hanging = FakeAdbBackend().apply { hangConnect = true }
        assertFalse(strategy(hanging, tls = false) { null }.probeWirelessTls(probeTimeoutMs = 1_000))
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(1, hanging.closeCount)
        assertFalse(hanging.isConnected)
    }

    @Test fun probeWirelessTlsNeverRunsOnFireOs() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        assertFalse(strategy(adb, fire = true, tls = false) { null }.probeWirelessTls())
        assertTrue(adb.connectAttempts.isEmpty())
        assertEquals(0, adb.closeCount)
    }

    @Test fun probeWirelessTlsSerializesWithAdbMutex() = runTest(timeout = 10.seconds) {
        val adb = FakeAdbBackend()
        val mutex = Mutex()
        val s = ConnectionStrategy(
            helper = HelperProbe { null },
            adb = adb,
            prefs = FakePrefs(tls = false),
            expectedApk = apk,
            isFireOs = false,
            pkg = SideSpeedPackages.RELEASE,
            adbMutex = mutex,
        )
        mutex.lock()
        val deferred = async { s.probeWirelessTls() }
        testScheduler.runCurrent()
        assertTrue(adb.connectAttempts.isEmpty())
        mutex.unlock()
        assertTrue(deferred.await())
        assertEquals(listOf<AdbTarget>(AdbTarget.WirelessTls), adb.connectAttempts)
        assertEquals(1, adb.closeCount)
    }
}
