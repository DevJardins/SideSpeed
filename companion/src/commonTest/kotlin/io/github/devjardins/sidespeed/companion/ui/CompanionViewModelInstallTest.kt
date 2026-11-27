package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.FakeLan
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.install.InstallOutcome
import io.github.devjardins.sidespeed.companion.install.InstallSource
import io.github.devjardins.sidespeed.companion.install.InstallTestFixtures
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.tv.ActivityPhase
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.InstallStream
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionViewModelInstallTest {
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val elsewhere = NetworkFingerprint("10.9.9.1", "10.9.9.0/24", setOf("10.9.9.1"))
    private val apk = "/data/app/x/base.apk"
    private val pkgName = "com.example.app"

    private fun card(
        status: TvStatus = TvStatus.RUNNING,
        here: Boolean = true,
        attention: TvAttention = TvAttention.NONE,
        activity: TvActivity? = null,
    ) = TvCardState(
        id = "tv-1", name = "Cube", address = "192.168.5.20", status = status, statusLabel = Copy.statusLabel(status),
        checkableHere = here, checkedAtEpochMs = null, note = null, activity = activity, attention = attention,
    )

    private fun remote(phase: ActivityPhase = ActivityPhase.IDLE, supported: Boolean = true) =
        TvActivity(phase = phase, remoteSupported = supported)

    @Test fun cardActions_exposes_install_and_optimize_only_when_eligible() {
        // Install: reachable even when the helper is not running.
        assertEquals(
            listOf(TvCardAction.RESTART, TvCardAction.INSTALL, TvCardAction.REMOVE),
            cardActions(card(TvStatus.NOT_RUNNING)),
        )
        // Hidden when offline, waiting on the user, or off the Trusted Network.
        assertFalse(TvCardAction.INSTALL in cardActions(card(TvStatus.OFFLINE)))
        assertFalse(TvCardAction.INSTALL in cardActions(card(TvStatus.RUNNING, attention = TvAttention.CONFIRM)))
        assertFalse(TvCardAction.INSTALL in cardActions(card(TvStatus.RUNNING, here = false)))
        assertEquals(listOf(TvCardAction.REMOVE), cardActions(card(TvStatus.CHECK_FAILED)))
        // Optimize: running, remote supported, not compiling.
        assertTrue(TvCardAction.OPTIMIZE in cardActions(card(activity = remote())))
        assertFalse(TvCardAction.OPTIMIZE in cardActions(card(activity = remote(ActivityPhase.COMPILING))))
        assertFalse(TvCardAction.OPTIMIZE in cardActions(card(activity = remote(supported = false))))
        assertFalse(TvCardAction.OPTIMIZE in cardActions(card()))
        assertFalse(TvCardAction.OPTIMIZE in cardActions(card(TvStatus.NOT_RUNNING, activity = remote())))
    }

    private class TrackedSource(
        override val displayName: String,
        private val bytes: ByteArray,
        private val onRead: (Int) -> Unit = {},
    ) : InstallSource {
        override val sizeBytes: Long? = bytes.size.toLong()
        var closes = 0
        private var reads = 0
        override fun openStream(): InputStream = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                onRead(reads++)
                return super.read(b, off, len)
            }
        }
        override fun asZipFile(): ZipFile? = null
        override fun close() { closes++ }
    }

    private class Host : InstallTransferHost {
        val starts = mutableListOf<Pair<String, String>>()
        val progress = mutableListOf<Triple<Long, Long, Boolean>>()
        val phases = mutableListOf<String>()
        var ends = 0
        var phaseProbe: () -> String = { "" }
        override fun onStart(tvName: String, appName: String) { starts += tvName to appName }
        override fun onProgress(bytesSent: Long, totalBytes: Long, committing: Boolean) {
            progress += Triple(bytesSent, totalBytes, committing)
            phases += phaseProbe()
        }
        override fun onEnd() { ends++ }
    }

    private inner class Rig(scope: TestScope, extraOutputs: Map<String, String> = emptyMap(), trusted: Boolean = true) {
        var fp: NetworkFingerprint = if (trusted) home else elsewhere
        val args = mutableListOf<List<String>>()
        val sessions = mutableListOf<FakeTvSession>()
        val host = Host()
        val store = InMemoryCompanionStore(
            tvs = listOf(
                SavedTv(
                    id = "tv-1", name = "Cube", address = "192.168.5.20", serial = "SER-1", trustedNetworkId = "home",
                    link = TvLink.Classic(ackAtEpochMs = 1L), status = TvStatus.RUNNING,
                ),
            ),
            networks = listOf(TrustedNetwork("home", "Living room", home)),
        )
        val outputs: Map<String, String> = buildMap {
            put(TvCommands.SERIAL, "SER-1")
            put(TvCommands.MODEL, "Fire TV Cube")
            put(TvCommands.MANUFACTURER, "Amazon")
            put(TvCommands.PROP_ABILIST, "arm64-v8a,armeabi-v7a")
            put(TvCommands.PROP_DENSITY, "320")
            put(TvCommands.PROP_LOCALE, "en-US")
            put(TvCommands.packagePath(pkgName), "")
            put(TvCommands.PKG_PATH, "package:$apk")
            put(TvCommands.PIDOF, "123")
            put(TvCommands.cmdline(123), "app_process\u0000--apk=$apk")
            put(TvCommands.callCompilePackage(pkgName, keepOptimized = true), "Result: Bundle[{ok=true, queued=1, req_seq=7}]")
            putAll(extraOutputs)
        }
        var onCommit: () -> Unit = {}
        val connector = TvConnector { _, _ ->
            FakeTvSession(
                outputs,
                packageCmdHandler = { a ->
                    val list = a.toList()
                    args += list
                    if (list[0] == "install-commit") onCommit()
                    val reply = when (list[0]) {
                        "install-create" -> "Success: created install session [41]"
                        "install-write" -> "Success: streamed"
                        "install-commit" -> "Success"
                        else -> "Success"
                    }
                    object : InstallStream {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                        override fun flush() = Unit
                        override fun readAllText() = reply
                        override fun close() = Unit
                    }
                },
            ).also { sessions += it }
        }
        val source = FingerprintSource { fp }
        val io = UnconfinedTestDispatcher(scope.testScheduler)
        val vm = CompanionViewModel(
            store, source, Upkeep(store, source, connector, HelperKeeper(), TcpScanner()) { 1_000_000L }, connector,
            Discovery(listOf(SweepSource), FakeLan()), scope, { 1_000_000L }, { "tv-1" },
            installConnector = connector, transferHost = host, ioDispatcher = io,
        ).also { host.phaseProbe = { it.state.value.install?.phase?.let { p -> p::class.simpleName } ?: "none" } }
    }

    private fun apkBytes(padding: Int = 0): ByteArray {
        val manifest = InstallTestFixtures.buildAxml(
            InstallTestFixtures.AxmlSpec(packageName = pkgName, versionCode = 5, versionName = "1.5", appLabelLiteral = "Example"),
        )
        val entries = mutableListOf("AndroidManifest.xml" to manifest)
        if (padding > 0) entries += "assets/pad.bin" to Random(1).nextBytes(padding)
        return InstallTestFixtures.buildZipBytes(entries)
    }

    @Test fun beginInstall_and_confirmInstall_enforce_trusted_network_gate() = runTest(UnconfinedTestDispatcher()) {
        // Trust is lost before the file is read.
        val off = Rig(this, trusted = false)
        val first = TrackedSource("a.apk", apkBytes())
        off.vm.beginInstall("tv-1", first)
        runCurrent()
        assertNull(off.vm.state.value.install)
        assertEquals(Copy.NOT_TRUSTED_ERROR, off.vm.state.value.installNotice["tv-1"])
        assertEquals(1, first.closes)
        assertTrue(off.sessions.isEmpty())

        // Trust is lost between review and confirm: nothing is sent.
        val r = Rig(this)
        val second = TrackedSource("a.apk", apkBytes())
        r.vm.beginInstall("tv-1", second)
        runCurrent()
        assertTrue(r.vm.state.value.install?.phase is InstallPhase.Review)
        r.fp = elsewhere
        r.vm.confirmInstall()
        runCurrent()
        val phase = r.vm.state.value.install?.phase as InstallPhase.Failed
        assertEquals(Copy.NOT_TRUSTED_ERROR, phase.message)
        assertTrue(r.args.none { it[0] == "install-create" })
        r.vm.cancelInstall()
        runCurrent()
        assertNull(r.vm.state.value.install)
        assertEquals(1, second.closes)
    }

    @Test fun modal_install_lifecycle_and_transfer_host_callbacks() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this)
        val bytes = apkBytes(padding = 150_000)
        val source = TrackedSource("Example.apk", bytes)
        r.vm.beginInstall("tv-1", source)
        runCurrent()
        val review = r.vm.state.value.install!!
        assertEquals("Cube", review.tvName)
        val shown = (review.phase as InstallPhase.Review).review
        assertEquals("Example", shown.appName)
        assertEquals("1.5", shown.newVersion)
        assertNull(shown.installedVersion)
        assertTrue(review.keepOptimized)
        assertEquals(0, source.closes)

        r.vm.confirmInstall()
        runCurrent()
        assertNull(r.vm.state.value.install)
        assertEquals(listOf("Cube" to "Example"), r.host.starts)
        assertEquals(1, r.host.ends)
        assertEquals(listOf("Transferring", "Committing", "StartingCompile"), r.host.phases.distinct())
        assertTrue(r.host.progress.last().third)
        assertEquals(1, source.closes)
        assertEquals("Example installed. SideSpeed will optimize it next.", r.vm.state.value.installNotice["tv-1"]?.english())
        assertTrue(TvCommands.callCompilePackage(pkgName, true) in r.sessions.flatMap { it.sent })
        r.vm.dismissInstallNotice("tv-1")
        assertTrue(r.vm.state.value.installNotice.isEmpty())
    }

    @Test fun installed_notice_matches_what_the_tv_will_do() {
        fun notice(outcome: InstallOutcome) = Copy.installed(outcome).english()
        assertEquals(
            "Example installed. SideSpeed will optimize it next.",
            notice(InstallOutcome("com.example", "Example", compileRequested = true, reqSeq = 1L)),
        )
        assertEquals(
            "Example installed. Update SideSpeed on this TV to optimize it from here.",
            notice(InstallOutcome("com.example", "Example", compileRequested = false, tvNeedsUpdate = true)),
        )
        // SideSpeed not installed on the TV, and SideSpeed updating itself: nothing is pending.
        assertEquals("Example installed.", notice(InstallOutcome("com.example", "Example", compileRequested = false)))
        assertEquals("SideSpeed installed.", notice(InstallOutcome("io.github.devjardins.sidespeed", "SideSpeed", compileRequested = false)))
    }

    @Test fun cancelInstall_aborts_transferring_but_ignores_committing() = runTest(UnconfinedTestDispatcher()) {
        // Cancel mid-upload: the first read after the phase turned to Transferring cancels.
        lateinit var cancelRig: Rig
        var sentChunks = 0
        val source = TrackedSource("Example.apk", apkBytes(padding = 200_000)) {
            val phase = cancelRig.vm.state.value.install?.phase
            if (phase is InstallPhase.Transferring && ++sentChunks == 2) cancelRig.vm.cancelInstall()
        }
        cancelRig = Rig(this)
        cancelRig.vm.beginInstall("tv-1", source)
        runCurrent()
        cancelRig.vm.confirmInstall()
        runCurrent()
        assertNull(cancelRig.vm.state.value.install)
        assertTrue(cancelRig.vm.state.value.installNotice.isEmpty())
        assertTrue(cancelRig.args.any { it == listOf("install-abandon", "41") })
        assertEquals(1, cancelRig.host.ends)
        assertEquals(1, source.closes)

        // Cancel while committing does nothing: the install finishes.
        val r = Rig(this)
        r.onCommit = { r.vm.cancelInstall() }
        val committed = TrackedSource("Example.apk", apkBytes())
        r.vm.beginInstall("tv-1", committed)
        runCurrent()
        r.vm.confirmInstall()
        runCurrent()
        assertNull(r.vm.state.value.install)
        assertTrue(r.args.none { it[0] == "install-abandon" })
        assertEquals("Example installed. SideSpeed will optimize it next.", r.vm.state.value.installNotice["tv-1"]?.english())
        assertEquals(1, committed.closes)
    }

    @Test fun optimizeNow_sends_content_call_and_updates_notice() = runTest(UnconfinedTestDispatcher()) {
        val started = Rig(this, mapOf(TvCommands.CALL_OPTIMIZE_NOW to "Result: Bundle[{ok=true, queued=2, req_seq=3}]"))
        started.vm.optimizeNow("tv-1")
        runCurrent()
        assertEquals(Copy.OPTIMIZE_STARTED, started.vm.state.value.optimizeNotice["tv-1"])
        assertTrue(started.vm.state.value.optimizingTvIds.isEmpty())
        assertTrue(TvCommands.CALL_OPTIMIZE_NOW in started.sessions.flatMap { it.sent })

        val caughtUp = Rig(this, mapOf(TvCommands.CALL_OPTIMIZE_NOW to "Result: Bundle[{ok=true, queued=0, req_seq=4}]"))
        caughtUp.vm.optimizeNow("tv-1")
        runCurrent()
        assertEquals(Copy.OPTIMIZE_CAUGHT_UP, caughtUp.vm.state.value.optimizeNotice["tv-1"])

        val failed = Rig(this, mapOf(TvCommands.CALL_OPTIMIZE_NOW to "Result: Bundle[{ok=false, error=busy, req_seq=5}]"))
        failed.vm.optimizeNow("tv-1")
        runCurrent()
        assertEquals(Copy.OPTIMIZE_FAILED, failed.vm.state.value.optimizeNotice["tv-1"])
        assertTrue(failed.vm.state.value.optimizingTvIds.isEmpty())
    }
}
