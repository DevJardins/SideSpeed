package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.AdbProbe
import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.discovery.TlsConnectService
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.FakeLan
import io.github.devjardins.sidespeed.companion.discovery.HostSource
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.discovery.helpFor
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.tv.ActivityPhase
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.PairResult
import io.github.devjardins.sidespeed.companion.tv.Pairer
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.LinkOffer
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionViewModelTest {
    private val apk = "/data/app/x/base.apk"
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val homeNet = TrustedNetwork("home", "Living room", home)

    private fun ip(s: String) = Ipv4.parse(s)!!

    private fun tv(manufacturer: String = "Amazon") = FakeTvSession(
        mapOf(
            TvCommands.SERIAL to "SER-1",
            TvCommands.MODEL to "Fire TV Cube",
            TvCommands.MANUFACTURER to manufacturer,
            TvCommands.PKG_PATH to "package:$apk",
            TvCommands.PIDOF to "123",
            TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
        ),
    )

    private class Rig(
        scope: TestScope,
        val store: InMemoryCompanionStore,
        val fake: FakeTvSession,
        var fp: NetworkFingerprint?,
        val lan: FakeLan = FakeLan(),
    ) {
        val source = FingerprintSource { fp }
        val connector = fake.connector()
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner()) { 1_000_000L }
        val discovery = Discovery(listOf(SweepSource), lan)
        val vm = CompanionViewModel(store, source, upkeep, connector, discovery, scope, { 1_000_000L }, { "tv-1" })
    }

    private fun CompanionViewModel.addSaved(address: String) = addTvByAddress(address, acknowledged = true)

    private val allowed = TvLink.Classic(ackAtEpochMs = 1L)

    private fun TestScope.rig(trusted: Boolean, fake: FakeTvSession = tv()) = Rig(
        this,
        InMemoryCompanionStore(networks = if (trusted) listOf(homeNet) else emptyList()),
        fake,
        home,
    )

    @Test
    fun noTrustedNetworkIsFirstRunAndOnOpenTouchesNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = false)
        r.vm.onOpen()
        assertTrue(r.vm.state.value.firstRun)
        assertTrue(r.fake.opened.isEmpty())
    }

    @Test
    fun refreshUpgradesTheTrustedRecordWithTheGatewayMac() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.fp = home.copy(gatewayMac = "00:11:22:33:44:55")
        r.vm.refreshNow()
        runCurrent()
        assertEquals("00:11:22:33:44:55", r.store.trustedNetworks().single().fingerprint.gatewayMac)
    }

    @Test
    fun refreshNeverChangesExistingRecordFields() = runTest(UnconfinedTestDispatcher()) {
        val stored = homeNet.copy(fingerprint = home.copy(gatewayMac = "00:11:22:33:44:55"))
        val r = Rig(this, InMemoryCompanionStore(networks = listOf(stored)), FakeTvSession(mapOf()), home.copy(gatewayMac = "00:11:22:33:44:55"))
        r.vm.refreshNow()
        runCurrent()
        assertEquals(stored, r.store.trustedNetworks().single())
    }

    @Test
    fun trustCurrentNetworkSavesFingerprintUnderName() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = false)
        r.vm.trustCurrentNetwork("Living room")
        val saved = r.store.trustedNetworks().single()
        assertEquals("Living room", saved.name)
        assertEquals(home, saved.fingerprint)
        assertEquals(false, r.vm.state.value.firstRun)
        assertEquals("Living room", r.vm.state.value.trustedNetworkName)
    }

    @Test
    fun emptyTrustedNetworkLabelUsesNumberedDefault() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = false)
        r.vm.trustCurrentNetwork("")
        assertEquals("Trusted Network 1", r.store.trustedNetworks().single().name)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.vm.trustCurrentNetwork("")
        assertEquals("Trusted Network 2", r.store.trustedNetworks()[1].name)
    }

    @Test
    fun blankTvModelUsesAddressInDefaultName() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeTvSession(
            mapOf(
                TvCommands.SERIAL to "SER-2",
                TvCommands.MODEL to "",
                TvCommands.MANUFACTURER to "Amazon",
                TvCommands.PKG_PATH to "package:$apk",
                TvCommands.PIDOF to "123",
                TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
            ),
        )
        val r = rig(trusted = true, fake = fake)
        r.vm.addSaved("192.168.5.20")
        runCurrent()
        assertEquals("TV at 192.168.5.20", r.store.tvs().single().name)
    }

    @Test
    fun savedDefaultNamesNeverContainBidiIsolates() = runTest(UnconfinedTestDispatcher()) {
        val isolate = Regex("[\u2066\u2067\u2068\u2069]")
        val r = rig(trusted = false)
        r.vm.trustCurrentNetwork("")
        assertFalse(isolate.containsMatchIn(r.store.trustedNetworks().single().name))
        val fake = FakeTvSession(
            mapOf(
                TvCommands.SERIAL to "SER-3",
                TvCommands.MODEL to "",
                TvCommands.MANUFACTURER to "Amazon",
                TvCommands.PKG_PATH to "package:$apk",
                TvCommands.PIDOF to "123",
                TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
            ),
        )
        val r2 = rig(trusted = true, fake = fake)
        r2.vm.addSaved("192.168.5.21")
        runCurrent()
        assertFalse(isolate.containsMatchIn(r2.store.tvs().single().name))
    }

    @Test
    fun findTvsOnUntrustedNetworkErrorsAndDoesNotScan() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.vm.findTvs()
        assertEquals("Join a Trusted Network to find TVs", r.vm.state.value.error?.english())
        assertEquals(0, r.lan.calls.size)
    }

    @Test
    fun findTvsInACustomRangeSweepsOnlyThatRange() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.findTvsIn("192.168.4.0/23")
        runCurrent()
        val scope = Cidr.parse("192.168.4.0/23")!!
        assertTrue(r.lan.calls.isNotEmpty())
        assertTrue(r.lan.calls.all { it.address in scope })
        assertTrue(r.lan.calls.any { it.address.toString().startsWith("192.168.4.") })
        assertNotNull(r.vm.state.value.diagnosis)
    }

    @Test
    fun aBadRangeErrorsAndDoesNotScan() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.findTvsIn("10.0.0.0/8")
        runCurrent()
        assertTrue(r.lan.calls.isEmpty())
        assertEquals(Copy.BAD_RANGE, r.vm.state.value.error)
        assertEquals(false, r.vm.state.value.scanning)
        assertNull(r.vm.state.value.diagnosis)
    }

    @Test
    fun keepScanningStaysInTheCustomRange() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val lan = FakeLan(delayMs = 10)
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvsIn("192.168.4.0/23")
        advanceTimeBy(150)
        vm.cancelFind()
        runCurrent()
        vm.keepScanning()
        advanceUntilIdle()
        val scope = Cidr.parse("192.168.4.0/23")!!
        assertTrue(lan.calls.isNotEmpty())
        assertTrue(lan.calls.all { it.address in scope })
    }

    @Test
    fun blankRangeIsTheAutomaticSweep() = runTest(UnconfinedTestDispatcher()) {
        val a = rig(trusted = true)
        val b = rig(trusted = true)
        a.vm.findTvs()
        b.vm.findTvsIn("")
        runCurrent()
        assertTrue(a.lan.calls.isNotEmpty())
        assertEquals(a.lan.calls.map { it.address }, b.lan.calls.map { it.address })
    }

    @Test
    fun addTvSavesSerialAndModelThenRunsUpkeep() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        val saved = r.store.tvs().single()
        assertEquals("SER-1", saved.serial)
        assertEquals("Fire TV Cube", saved.name)
        assertEquals("home", saved.trustedNetworkId)
        // Upkeep ran: it checked the helper (APP_OPEN) and recorded a status.
        assertNotNull(r.store.tv("tv-1").lastCheckedEpochMs)
        assertNull(r.vm.state.value.tvs.single().note)
    }

    @Test
    fun restartNowRunsRestartUpkeepClearingAllowFlag() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        r.store.updateTv("tv-1") { it.copy(skipBackgroundUntilUserAction = true) }
        r.vm.restartNow()
        assertEquals(false, r.store.tv("tv-1").skipBackgroundUntilUserAction)
    }

    @Test
    fun nonAmazonTvIsSavedWithGoogleTvNote() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true, fake = tv("Google"))
        r.vm.addSaved("192.168.5.20")
        assertEquals(1, r.store.tvs().size)
        assertEquals(
            "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts. " +
                "On Google TV the helper is optional; Home shows what's compiling.",
            r.vm.state.value.tvs.single().note?.english(),
        )
    }

    @Test
    fun addingAnOverlayAddressIsAllowedAndConnects() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("100.101.102.103")
        assertTrue("100.101.102.103:5555" in r.fake.opened)
        assertEquals("100.101.102.103", r.store.tvs().single().address)
    }

    @Test
    fun addPublicOrBadAddressConnectsToNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        for (publicAddress in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "127.0.0.1")) {
            r.vm.addSaved(publicAddress)
            assertEquals(Copy.OUTSIDE_NETWORK, r.vm.state.value.error, publicAddress)
        }
        r.vm.addSaved("not-an-ip")
        assertEquals(Copy.BAD_ADDRESS, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
        assertTrue(r.store.tvs().isEmpty())
    }

    @Test
    fun anyPrivateAddressCanBeAddedWhileOnATrustedNetwork() = runTest(UnconfinedTestDispatcher()) {
        // 192.168.9.20 and 10.9.9.9 are private but outside the trusted 192.168.5.0/24 (a /22 or a second subnet).
        for (address in listOf("192.168.9.20", "10.9.9.9", "172.16.4.4")) {
            val r = rig(trusted = true)
            r.vm.addSaved(address)
            assertEquals(setOf("$address:5555"), r.fake.opened.toSet(), address)
            assertEquals(address, r.store.tvs().single().address)
        }
    }

    @Test
    fun privateAddressIsStillRefusedOffATrustedNetwork() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.vm.addSaved("192.168.5.20")
        assertEquals(Copy.NOT_TRUSTED_ADD, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
    }

    @Test
    fun addErrorPathsShowMessagesAndSaveNothing() = runTest(UnconfinedTestDispatcher()) {
        val pending = rig(trusted = true, fake = tv().also { it.authPending = true })
        pending.vm.addSaved("192.168.5.20")
        assertEquals(Copy.ALLOW_STEP, pending.vm.state.value.error)
        val down = rig(trusted = true, fake = tv().also { it.failOnOpen = true })
        down.vm.addSaved("192.168.5.20")
        assertEquals(Copy.UNREACHABLE, down.vm.state.value.error)
        val blank = rig(trusted = true, fake = tv().also { it.outputs[TvCommands.SERIAL] = "" })
        blank.vm.addSaved("192.168.5.20")
        assertEquals(Copy.NO_SERIAL, blank.vm.state.value.error)
        assertTrue(pending.store.tvs().isEmpty() && down.store.tvs().isEmpty() && blank.store.tvs().isEmpty())
        assertEquals(false, blank.vm.state.value.busy)
    }

    @Test
    fun restartNowWithTvIdChecksOnlyThatTv() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        r.store.saveTv(r.store.tv("tv-1").copy(id = "tv-2", serial = "OTHER", address = "192.168.5.21"))
        r.fake.opened.clear()
        r.vm.restartNow("tv-1")
        assertEquals(listOf("192.168.5.20:5555"), r.fake.opened)
    }

    @Test
    fun restartNowOffTheTrustedNetworkIsNotChecked() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", status = TvStatus.RUNNING, link = allowed))
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.fake.opened.clear()
        r.vm.restartNow("tv-1")
        assertEquals(RestartResult("tv-1", RestartOutcome.NOT_CHECKED), r.vm.state.value.lastRestart)
        assertTrue(r.fake.opened.isEmpty())
    }

    @Test
    fun restartNowThatReachesARunningTvIsRestarted() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", link = allowed))
        r.vm.restartNow("tv-1")
        assertEquals(RestartResult("tv-1", RestartOutcome.RESTARTED), r.vm.state.value.lastRestart)
        assertEquals(listOf("192.168.5.20:5555"), r.fake.opened)
    }

    @Test
    fun restartNowThatReachesAnOfflineTvIsChecked() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true, fake = tv().also { it.failOnOpen = true })
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", status = TvStatus.NOT_RUNNING, link = allowed))
        r.vm.restartNow("tv-1")
        assertEquals(RestartResult("tv-1", RestartOutcome.CHECKED), r.vm.state.value.lastRestart)
        assertEquals(listOf("192.168.5.20:5555"), r.fake.opened)
    }

    @Test
    fun refreshOnAnotherNetworkDoesNotProbeSavedTvs() = runTest(UnconfinedTestDispatcher()) {
        var probes = 0
        var lookups = 0
        val store = InMemoryCompanionStore(networks = listOf(homeNet))
        store.saveTv(
            SavedTv(
                "tv-1", "Cube", "192.168.5.20", "SER-1", "home",
                status = TvStatus.NEEDS_ATTENTION, link = TvLink.Classic(null),
            ),
        )
        val away = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        val source = FingerprintSource { away }
        val connector = tv().connector()
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner()) { 1_000_000L }
        CompanionViewModel(
            store, source, upkeep, connector, Discovery(listOf(SweepSource), FakeLan()), this,
            { 1_000_000L }, { "tv-1" },
            ports = ConnectPortFinder { lookups += 1; null },
            linkOffer = LinkOffer { probes += 1; false },
        )
        runCurrent()
        assertEquals(0, probes)
        assertEquals(0, lookups)
    }

    @Test
    fun aTvOnAnotherNetworkIsNotCheckableHere() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home"))
        r.vm.refreshNow()
        assertEquals(true, r.vm.state.value.tvs.single().checkableHere)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.vm.refreshNow()
        assertEquals(false, r.vm.state.value.tvs.single().checkableHere)
    }

    @Test
    fun removingOneTvKeepsTheOtherTvsAndTheNetwork() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        r.store.saveTv(r.store.tv("tv-1").copy(id = "tv-2", serial = "OTHER", address = "192.168.5.21"))
        r.vm.refreshNow()
        r.vm.removeTv("tv-1")
        assertEquals(listOf("tv-2"), r.store.tvs().map { it.id })
        assertEquals(listOf("tv-2"), r.vm.state.value.tvs.map { it.id })
        assertEquals(1, r.store.trustedNetworks().size)
    }

    @Test
    fun removingTrustedNetworkRemovesItsTvsAndRetrustKeepsName() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        r.vm.trustCurrentNetwork("Renamed")
        assertEquals("Living room", r.store.trustedNetworks().single().name)
        r.vm.removeTrustedNetwork("home")
        assertTrue(r.store.tvs().isEmpty())
        assertTrue(r.vm.state.value.tvs.isEmpty())
    }

    @Test
    fun renamingATrustedNetworkSavesTheTrimmedLabelAndRefreshesState() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.renameTrustedNetwork("home", "  Cabin  ")
        assertEquals("Cabin", r.store.trustedNetworks().single().name)
        assertEquals("Cabin", r.vm.state.value.trustedNetworkName)
        assertEquals(home, r.store.trustedNetworks().single().fingerprint)
    }

    @Test
    fun blankLabelFallsBackToTheIdentifierForDisplay() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.renameTrustedNetwork("home", "   ")
        val n = r.store.trustedNetworks().single()
        assertEquals("", n.name)
        assertEquals("home", n.displayName)
        assertEquals("home", r.vm.state.value.trustedNetworkName)
    }

    @Test
    fun renamingAnUnknownNetworkChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.renameTrustedNetwork("nope", "X")
        assertEquals("Living room", r.store.trustedNetworks().single().name)
    }

    @Test
    fun successfulAddRecordsTheAddressAndClearsPending() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        assertEquals("192.168.5.20", r.vm.state.value.addedAddress)
        assertTrue(r.vm.state.value.adding.isEmpty())
        assertTrue(r.vm.state.value.addErrors.isEmpty())
    }

    @Test
    fun pressingAnAlreadyAddedAddressAgainDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addSaved("192.168.5.20")
        val opens = r.fake.opened.size
        r.vm.addSaved("192.168.5.20")
        assertEquals(opens, r.fake.opened.size)
        assertEquals(1, r.store.tvs().size)
        assertEquals("192.168.5.20", r.vm.state.value.addedAddress)
    }

    @Test
    fun failedAddShowsAnErrorForThatAddressAndCanBeRetried() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true, fake = tv().also { it.failOnOpen = true })
        r.vm.addSaved("192.168.5.20")
        assertEquals(Copy.UNREACHABLE, r.vm.state.value.addErrors["192.168.5.20"])
        assertTrue(r.vm.state.value.adding.isEmpty())
        assertNull(r.vm.state.value.addedAddress)
        r.fake.failOnOpen = false
        r.vm.addSaved("192.168.5.20")
        assertTrue(r.vm.state.value.addErrors.isEmpty())
        assertEquals(1, r.store.tvs().size)
    }

    /** Wraps a probe so Discovery's parallel sweep hits hosts one at a time. */
    private class SerialProbe(private val inner: AdbProbe, private val yieldMs: Long = 0) : AdbProbe {
        private val lock = Mutex()
        override suspend fun probe(address: Ipv4, port: Int, timeoutMs: Int): ProbeResult =
            lock.withLock {
                if (yieldMs > 0) delay(yieldMs)
                inner.probe(address, port, timeoutMs)
            }
    }

    /** A LAN whose probes wait on [gate]: the sweep stays in flight until the test opens it. */
    private class GateLan(private val found: Set<String>, open: Boolean = false) : AdbProbe {
        val gate = CompletableDeferred<Unit>().also { if (open) it.complete(Unit) }
        var calls = 0
        override suspend fun probe(address: Ipv4, port: Int, timeoutMs: Int): ProbeResult {
            calls++
            gate.await()
            return if (address.toString() in found) ProbeResult.AdbOpen else ProbeResult.Refused
        }
    }

    private fun TestScope.discoveryRig(lan: AdbProbe, source: HostSource = SweepSource): CompanionViewModel {
        val r = rig(trusted = true)
        return CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(source), lan), this, { 1_000_000L }, { "tv-1" })
    }

    private fun allRefused(subnet: String) =
        Cidr.parse(subnet)!!.hosts().associateWith { listOf(ProbeResult.Refused) }.toMutableMap()

    @Test
    fun scanningIsTrueWhileScanRunsAndRepeatPressesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        var runs = 0
        val source = HostSource { runs++; SweepSource.hosts(it) }
        val lan = GateLan(setOf("192.168.5.30"))
        val vm = discoveryRig(lan, source)
        vm.findTvs()
        assertTrue(vm.state.value.scanning)
        vm.findTvs()
        lan.gate.complete(Unit)
        runCurrent()
        assertEquals(1, runs)
        assertEquals(false, vm.state.value.scanning)
        assertEquals(listOf("192.168.5.30"), vm.state.value.found)
        assertEquals(false, vm.state.value.noneFound)
    }

    @Test
    fun emptyScanSetsNoneFoundAndNextScanClearsIt() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        val vm = discoveryRig(FakeLan(script))
        vm.findTvs()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        assertTrue(vm.state.value.noneFound)
        assertEquals("Scanned 254 of 254", vm.state.value.scanProgress?.english())
        script[ip("192.168.5.30")] = listOf(ProbeResult.AdbOpen)
        vm.findTvs()
        runCurrent()
        assertEquals(listOf("192.168.5.30"), vm.state.value.found)
        assertEquals(false, vm.state.value.noneFound)
    }

    @Test
    fun foundStreamBeforeTheScanCompletes() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        script[ip("192.168.5.10")] = listOf(ProbeResult.AdbAuthRequired)
        script[ip("192.168.5.11")] = listOf(ProbeResult.AdbOpen)
        script[ip("192.168.5.12")] = listOf(ProbeResult.Timeout, ProbeResult.AdbOpen)
        val lan = FakeLan(script, delayMs = 100)
        val vm = discoveryRig(lan)
        vm.findTvs()
        advanceTimeBy(150)
        assertEquals(setOf("192.168.5.10", "192.168.5.11"), vm.state.value.found.toSet())
        assertTrue(vm.state.value.scanning)
        assertNotNull(vm.state.value.scanProgress)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(setOf("192.168.5.10", "192.168.5.11", "192.168.5.12"), vm.state.value.found.toSet())
        assertEquals(false, vm.state.value.scanning)
        // The silent host's second pass is not counted again: a /24 has 254 hosts.
        assertEquals("Scanned 254 of 254", vm.state.value.scanProgress?.english())
    }

    @Test
    fun cancelFindStopsTheScanAndKeepsPartialResults() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        script[ip("192.168.5.10")] = listOf(ProbeResult.AdbAuthRequired)
        script[ip("192.168.5.11")] = listOf(ProbeResult.AdbOpen)
        script[ip("192.168.5.12")] = listOf(ProbeResult.Timeout, ProbeResult.AdbOpen)
        val lan = FakeLan(script, delayMs = 100)
        val vm = discoveryRig(lan)
        vm.findTvs()
        advanceTimeBy(150)
        vm.cancelFind()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        assertTrue(vm.state.value.scanStopped)
        assertEquals(setOf("192.168.5.10", "192.168.5.11"), vm.state.value.found.toSet())
        val calls = lan.calls.size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(calls, lan.calls.size)
    }

    @Test
    fun truncatedSweepOffersKeepScanning() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val fp16 = NetworkFingerprint("10.0.2.1", "10.0.0.0/16", setOf("10.0.2.1"), ownIp = "10.0.1.3")
        r.store.saveTrustedNetwork(TrustedNetwork("big", "Big", fp16))
        r.fp = fp16
        val lan = FakeLan(allRefused("10.0.0.0/16"))
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        assertEquals(false, vm.state.value.scanStopped)
        assertEquals(Diagnosis.PARTIAL, vm.state.value.diagnosis)
        assertEquals("Scanned 4096 of 4096", vm.state.value.scanProgress?.english())
    }

    @Test
    fun keepScanningResumesPastProbedHosts() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val fp16 = NetworkFingerprint("10.0.2.1", "10.0.0.0/16", setOf("10.0.2.1"), ownIp = "10.0.1.3")
        r.store.saveTrustedNetwork(TrustedNetwork("big", "Big", fp16))
        r.fp = fp16
        val lan = FakeLan(allRefused("10.0.0.0/16"))
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertEquals(4096, lan.calls.size)
        vm.keepScanning()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        // The 4096 cap applies per run: the resume covers the next 4096 hosts, repeating none.
        val probed = lan.calls.map { it.address }
        assertEquals(8192, probed.size)
        assertEquals(probed.size, probed.toSet().size)
        assertEquals(Diagnosis.PARTIAL, vm.state.value.diagnosis)
    }

    @Test
    fun keepScanningFinishesTheStoppedScanWithoutClearingFound() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        script[ip("192.168.5.10")] = listOf(ProbeResult.AdbAuthRequired)
        script[ip("192.168.5.11")] = listOf(ProbeResult.AdbOpen)
        script[ip("192.168.5.12")] = listOf(ProbeResult.Timeout, ProbeResult.AdbOpen)
        val lan = FakeLan(script, delayMs = 100)
        val vm = discoveryRig(lan)
        vm.findTvs()
        vm.cancelFind()
        runCurrent()
        assertTrue(vm.state.value.scanStopped)
        vm.keepScanning()
        assertTrue(vm.state.value.scanning)
        assertEquals(false, vm.state.value.scanStopped)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(setOf("192.168.5.10", "192.168.5.11", "192.168.5.12"), vm.state.value.found.toSet())
        assertEquals(false, vm.state.value.scanning)
    }

    @Test
    fun keepScanningAfterStopSkipsHostsAlreadyProbed() = runTest(UnconfinedTestDispatcher()) {
        val lan = FakeLan(allRefused("192.168.5.0/24"), delayMs = 100)
        val vm = discoveryRig(lan)
        vm.findTvs()
        // First wave (128 probes) has answered; the second wave is in flight when Stop is pressed.
        advanceTimeBy(150)
        val callsAtStop = lan.calls.size
        val answered = Discovery.PARALLELISM
        vm.cancelFind()
        runCurrent()
        assertTrue(vm.state.value.scanStopped)
        vm.keepScanning()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        // The resume probes only the hosts that had not answered, not the whole subnet again.
        assertEquals(callsAtStop + (callsAtStop - answered), lan.calls.size)
    }

    @Test
    fun scanSkipsTheLiveOwnAddressEvenWhenTheSavedNetworkHasNone() = runTest(UnconfinedTestDispatcher()) {
        // The Trusted Network was saved without ownIp (older save, or DHCP moved us since): use the live one.
        val r = rig(trusted = true)
        r.fp = home.copy(ownIp = "192.168.5.7")
        val lan = FakeLan(allRefused("192.168.5.0/24"))
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertEquals(253, lan.calls.map { it.address }.toSet().size)
        assertTrue(lan.calls.none { it.address == ip("192.168.5.7") })
        assertEquals("Scanned 253 of 253", vm.state.value.scanProgress?.english())
    }

    @Test
    fun aSlash8NetworkSweepsTheSlash16AroundTheOwnAddress() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val fp8 = NetworkFingerprint("10.0.0.1", "10.0.0.0/8", setOf("10.0.0.1"), ownIp = "10.20.3.4")
        r.store.saveTrustedNetwork(TrustedNetwork("big8", "Big", fp8))
        r.fp = fp8
        val lan = FakeLan(allRefused("10.20.0.0/16"))
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertTrue(lan.calls.isNotEmpty())
        assertTrue(lan.calls.all { it.address.toString().startsWith("10.20.") })
        assertTrue(lan.calls.none { it.address == ip("10.20.3.4") })
    }

    @Test
    fun completedScanWithoutAdbSetsDiagnosisAndHelp() = runTest(UnconfinedTestDispatcher()) {
        val vm = discoveryRig(FakeLan(allRefused("192.168.5.0/24")))
        vm.findTvs()
        runCurrent()
        assertEquals(Diagnosis.NO_ADB_ON_LIVE_HOSTS, vm.state.value.diagnosis)
        assertTrue(
            helpFor(vm.state.value.diagnosis!!).english().endsWith("or type the address shown in SideSpeed on the TV"),
        )
    }

    @Test
    fun cancelledScanSetsPartialDiagnosis() = runTest(UnconfinedTestDispatcher()) {
        val lan = GateLan(setOf("192.168.5.30"))
        val vm = discoveryRig(lan)
        vm.findTvs()
        vm.cancelFind()
        runCurrent()
        assertEquals(Diagnosis.PARTIAL, vm.state.value.diagnosis)
    }

    @Test
    fun cancelledScanWithFindsSetsFoundDiagnosis() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        script[ip("192.168.5.10")] = listOf(ProbeResult.AdbAuthRequired)
        script[ip("192.168.5.12")] = listOf(ProbeResult.Timeout, ProbeResult.AdbOpen)
        val vm = discoveryRig(FakeLan(script, delayMs = 100))
        vm.findTvs()
        advanceTimeBy(150)
        vm.cancelFind()
        runCurrent()
        assertEquals(setOf("192.168.5.10"), vm.state.value.found.toSet())
        assertEquals(Diagnosis.FOUND, vm.state.value.diagnosis)
    }

    @Test
    fun ownAddressIsNeverProbedAndOwnSlash24ComesFirst() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val fp16 = NetworkFingerprint("10.0.2.1", "10.0.0.0/16", setOf("10.0.2.1"), ownIp = "10.0.1.3")
        r.store.saveTrustedNetwork(TrustedNetwork("big", "Big", fp16))
        r.fp = fp16
        val script = Cidr.parse("10.0.0.0/16")!!.hosts()
            .associateWith { listOf(ProbeResult.Refused) }.toMutableMap()
        val tv = ip("10.0.1.50")
        script[tv] = listOf(ProbeResult.AdbAuthRequired)
        val lan = FakeLan(script)
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        assertTrue(lan.calls.none { it.address == ip("10.0.1.3") }, "own address was probed")
        assertEquals(ip("10.0.1.0"), lan.calls.first().address)
        val tvIndex = lan.calls.indexOfFirst { it.address == tv }
        val outwardIndex = lan.calls.indexOfFirst { it.address.toString().startsWith("10.0.2.") }
        assertTrue(tvIndex >= 0 && outwardIndex > tvIndex, "tv at $tvIndex, outward at $outwardIndex")
        assertEquals(listOf("10.0.1.50"), vm.state.value.found)
    }

    @Test
    fun tlsHostIsListedForPairing() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        script[ip("192.168.5.10")] = listOf(ProbeResult.AdbTls)
        script[ip("192.168.5.11")] = listOf(ProbeResult.AdbAuthRequired)
        val vm = discoveryRig(FakeLan(script))
        vm.findTvs()
        runCurrent()
        assertEquals(listOf("192.168.5.11"), vm.state.value.found)
        assertEquals(listOf("192.168.5.10"), vm.state.value.tlsHosts)
        assertEquals(Diagnosis.FOUND, vm.state.value.diagnosis)
    }

    @Test
    fun tlsOnlySweepListsTheTvForPairing() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        script[ip("192.168.5.10")] = listOf(ProbeResult.AdbTls)
        val vm = discoveryRig(FakeLan(script))
        vm.findTvs()
        runCurrent()
        assertTrue(vm.state.value.found.isEmpty())
        assertEquals(listOf("192.168.5.10"), vm.state.value.tlsHosts)
        assertEquals(Diagnosis.FOUND, vm.state.value.diagnosis)
    }

    @Test
    fun disposeCancelsARunningScan() = runTest(UnconfinedTestDispatcher()) {
        val lan = GateLan(setOf("192.168.5.30"))
        val vm = discoveryRig(lan)
        vm.findTvs()
        assertTrue(vm.state.value.scanning)
        vm.onDispose()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        lan.gate.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.found.isEmpty())
    }

    @Test
    fun untrustedNetworkDoesNotLeaveScanningSet() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = false)
        r.vm.findTvs()
        assertEquals(false, r.vm.state.value.scanning)
        assertEquals(false, r.vm.state.value.noneFound)
    }

    @Test
    fun aScanThatNeverRanHasNoDiagnosisAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val tiny = NetworkFingerprint("192.168.5.5", "192.168.5.4/31", setOf("192.168.5.5"))
        r.store.saveTrustedNetwork(TrustedNetwork("tiny", "Tiny", tiny))
        r.fp = tiny
        val lan = FakeLan()
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), lan), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertTrue(lan.calls.isEmpty())
        assertNull(vm.state.value.diagnosis)
        assertEquals(false, vm.state.value.noneFound)
        assertEquals(Copy.CANT_SCAN, vm.state.value.error)
    }

    @Test
    fun offATrustedNetworkTheScanLeavesNoDiagnosis() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = false)
        r.vm.findTvs()
        assertNull(r.vm.state.value.diagnosis)
        assertEquals(Copy.NOT_TRUSTED_ERROR, r.vm.state.value.error)
    }

    @Test
    fun keepScanningRemembersLiveHostsSeenBeforeTheStop() = runTest(UnconfinedTestDispatcher()) {
        val script = allRefused("192.168.5.0/24")
        val lan = FakeLan(script, delayMs = 100)
        val vm = discoveryRig(lan)
        vm.findTvs()
        advanceTimeBy(150)
        vm.cancelFind()
        runCurrent()
        // The first wave (128 in flight) had answered by the stop; later hosts had only been started.
        val answered = lan.calls.take(128).map { it.address }.toSet()
        assertEquals(128, answered.size)
        Cidr.parse("192.168.5.0/24")!!.hosts().filter { it !in answered }.forEach { script[it] = listOf(ProbeResult.Timeout) }
        vm.keepScanning()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.scanning)
        assertEquals(Diagnosis.NO_ADB_ON_LIVE_HOSTS, vm.state.value.diagnosis)
    }

    @Test
    fun aCappedSweepThatFoundATvStillOffersKeepScanning() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val fp16 = NetworkFingerprint("10.0.2.1", "10.0.0.0/16", setOf("10.0.2.1"), ownIp = "10.0.1.3")
        r.store.saveTrustedNetwork(TrustedNetwork("big", "Big", fp16))
        r.fp = fp16
        val script = allRefused("10.0.0.0/16")
        script[ip("10.0.1.50")] = listOf(ProbeResult.AdbAuthRequired)
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, r.connector, Discovery(listOf(SweepSource), FakeLan(script)), this, { 1_000_000L }, { "tv-1" })
        vm.findTvs()
        runCurrent()
        assertEquals(Diagnosis.FOUND, vm.state.value.diagnosis)
        assertTrue(vm.state.value.scanTruncated)
        vm.keepScanning()
        runCurrent()
        assertEquals(listOf("10.0.1.50"), vm.state.value.found)
    }

    @Test
    fun aScanFinishingDuringAnAddDoesNotClearTheAddsBusyState() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val gate = CompletableDeferred<Unit>()
        val gated = TvConnector { address, port -> gate.await(); r.connector.open(address, port) }
        val vm = CompanionViewModel(r.store, r.source, r.upkeep, gated, Discovery(listOf(SweepSource), FakeLan(allRefused("192.168.5.0/24"))), this, { 1_000_000L }, { "tv-1" })
        vm.addSaved("192.168.5.77")
        assertTrue(vm.state.value.busy)
        vm.findTvs()
        runCurrent()
        assertEquals(false, vm.state.value.scanning)
        assertTrue(vm.state.value.busy, "the scan's refresh cleared busy while the add was still running")
        gate.complete(Unit)
        runCurrent()
        assertEquals(false, vm.state.value.busy)
    }

    @Test
    fun savedByNewerVersionShowsTheOlderCopyBanner() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(this, InMemoryCompanionStore(networks = listOf(homeNet), savedByNewerVersion = true), tv(), home)
        runCurrent()
        assertTrue(r.vm.state.value.savedByNewerVersion, "the banner shows when the flag is set")
        assertEquals(
            "This copy of SideSpeed Companion is older than the one that saved your TVs. " +
                "Changes won't be saved; open the newest copy instead.",
            Copy.OLDER_COPY.english(),
        )
        val plain = rig(trusted = true)
        runCurrent()
        assertEquals(false, plain.vm.state.value.savedByNewerVersion, "no banner for a current file")
    }

    private val compilingRow =
        "Row: 0 v=1, state=compiling, pkg=com.example.app, label=Example%2C Inc, mode=speed, " +
            "started_ms=1731000000000, queue=2, last_pkg=com.example.other, last_label=Other, " +
            "last_result=ok, last_ms=1730999000000, self=optimized, version=0.2.1"

    private suspend fun TestScope.activityRig(
        trusted: Boolean = true,
        status: TvStatus = TvStatus.RUNNING,
        statusRow: String? = compilingRow,
    ): Rig {
        val fake = tv()
        if (statusRow != null) fake.outputs[TvCommands.STATUS_QUERY] = statusRow
        val r = Rig(this, InMemoryCompanionStore(networks = if (trusted) listOf(homeNet) else emptyList()), fake, home)
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", status = status, link = allowed))
        r.vm.refreshNow()
        runCurrent()
        return r
    }

    @Test
    fun homeVisiblePollsARunningCheckableTv() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        runCurrent()
        val card = r.vm.state.value.tvs.single()
        assertEquals(ActivityPhase.COMPILING, card.activity?.phase)
        assertEquals("Example, Inc", card.activity?.label)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun pollsReuseOneSessionPerTv() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        advanceTimeBy(15_000)
        assertEquals(listOf("192.168.5.20:5555"), r.fake.opened)
        assertTrue(r.fake.sent.count { it == TvCommands.STATUS_QUERY } >= 4)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun hiddenHomeAndBackgroundStopPolling() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        runCurrent()
        val polled = r.fake.sent.count { it == TvCommands.STATUS_QUERY }
        assertTrue(polled >= 1)
        r.vm.setHomeVisible(false)
        advanceTimeBy(30_000)
        assertEquals(polled, r.fake.sent.count { it == TvCommands.STATUS_QUERY })
    }

    @Test
    fun backgroundedAppStopsPollingUntilForeground() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        runCurrent()
        r.vm.setForeground(false)
        advanceTimeBy(30_000)
        val polled = r.fake.sent.count { it == TvCommands.STATUS_QUERY }
        r.vm.setForeground(true)
        advanceTimeBy(3_001)
        assertTrue(r.fake.sent.count { it == TvCommands.STATUS_QUERY } > polled)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun tvsThatAreNotRunningOrCheckableAreNotPolled() = runTest(UnconfinedTestDispatcher()) {
        val idle = activityRig(status = TvStatus.NOT_RUNNING)
        idle.vm.setHomeVisible(true)
        advanceTimeBy(10_000)
        assertTrue(idle.fake.sent.none { it == TvCommands.STATUS_QUERY })
        assertNull(idle.vm.state.value.tvs.single().activity)

        val away = activityRig()
        away.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        away.vm.refreshNow()
        runCurrent()
        away.vm.setHomeVisible(true)
        advanceTimeBy(10_000)
        assertTrue(away.fake.sent.none { it == TvCommands.STATUS_QUERY })
        idle.vm.setHomeVisible(false)
        away.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun threeConsecutiveFailuresParkUntilTheNextVisit() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.fake.loseConnectionOn = TvCommands.STATUS_QUERY
        r.vm.setHomeVisible(true)
        advanceTimeBy(3_001)
        advanceTimeBy(3_000)
        advanceTimeBy(3_000)
        val attempts = r.fake.sent.count { it == TvCommands.STATUS_QUERY }
        assertEquals(3, attempts)
        advanceTimeBy(30_000)
        assertEquals(3, r.fake.sent.count { it == TvCommands.STATUS_QUERY })
        r.vm.setHomeVisible(false)
        r.vm.setHomeVisible(true)
        advanceTimeBy(3_001)
        assertTrue(r.fake.sent.count { it == TvCommands.STATUS_QUERY } > 3)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun unparsableOutputKeepsThePreviousState() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig(statusRow = "garbage without a row")
        r.vm.setHomeVisible(true)
        advanceTimeBy(10_000)
        assertNull(r.vm.state.value.tvs.single().activity)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun aRefreshKeepsTheLatestActivity() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        runCurrent()
        assertNotNull(r.vm.state.value.tvs.single().activity)
        r.vm.refreshNow()
        runCurrent()
        assertEquals(ActivityPhase.COMPILING, r.vm.state.value.tvs.single().activity?.phase)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun pollRechecksTheNetworkBeforeEachTv() = runTest(UnconfinedTestDispatcher()) {
        val away = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        var fp: NetworkFingerprint = home
        var statusQueries = 0
        class Scripted(val serial: String) : io.github.devjardins.sidespeed.companion.tv.TvSession {
            val sent = mutableListOf<String>()
            var closed = false
            override var tlsNegotiated: Boolean = true
            override suspend fun run(command: String): String {
                sent += command
                if (command == TvCommands.STATUS_QUERY) {
                    statusQueries += 1
                    if (statusQueries >= 3) fp = away
                }
                return when (command) {
                    TvCommands.SERIAL -> serial
                    TvCommands.STATUS_QUERY -> compilingRow
                    else -> error("unscripted $command")
                }
            }
            override fun close() {
                closed = true
            }
        }
        val first = Scripted("SER-1")
        val second = Scripted("SER-2")
        val connector = TvConnector { address, _ ->
            when (address) {
                "192.168.5.20" -> first
                "192.168.5.21" -> second
                else -> error(address)
            }
        }
        val store = InMemoryCompanionStore(networks = listOf(homeNet))
        store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", status = TvStatus.RUNNING, link = allowed))
        store.saveTv(SavedTv("tv-2", "Stick", "192.168.5.21", "SER-2", "home", status = TvStatus.RUNNING, link = allowed))
        val source = FingerprintSource { fp }
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner()) { 1_000_000L }
        val vm = CompanionViewModel(
            store, source, upkeep, connector, Discovery(listOf(SweepSource), FakeLan()), this, { 1_000_000L }, { "tv-1" },
        )
        vm.refreshNow()
        runCurrent()
        vm.setHomeVisible(true)
        runCurrent()
        assertEquals(1, first.sent.count { it == TvCommands.STATUS_QUERY })
        assertEquals(1, second.sent.count { it == TvCommands.STATUS_QUERY })
        assertEquals(false, second.closed)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(2, first.sent.count { it == TvCommands.STATUS_QUERY })
        assertEquals(1, second.sent.count { it == TvCommands.STATUS_QUERY }, second.sent.joinToString())
        assertEquals(true, second.closed)
        assertEquals(false, first.closed)
        vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun pollStopsWhenTheTrustedNetworkIsLost() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        runCurrent()
        val polledAfterTrust = r.fake.sent.count { it == TvCommands.STATUS_QUERY }
        assertTrue(polledAfterTrust >= 1)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        advanceTimeBy(3_001)
        assertEquals(polledAfterTrust, r.fake.sent.count { it == TvCommands.STATUS_QUERY })
        assertEquals(1, r.fake.opened.size)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun pollSerialMismatchParksAfterSerialOnly() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.fake.outputs[TvCommands.SERIAL] = "OTHER-TV"
        r.vm.setHomeVisible(true)
        runCurrent()
        assertEquals(listOf(TvCommands.SERIAL), r.fake.sent)
        assertTrue(r.fake.sent.none { it == TvCommands.STATUS_QUERY })
        advanceTimeBy(30_000)
        assertEquals(listOf(TvCommands.SERIAL), r.fake.sent)
        r.vm.setHomeVisible(false)
        r.vm.setHomeVisible(true)
        runCurrent()
        assertTrue(r.fake.sent.count { it == TvCommands.SERIAL } >= 2)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun pollSerialMatchReusesSessionWithoutASecondSerial() = runTest(UnconfinedTestDispatcher()) {
        val r = activityRig()
        r.vm.setHomeVisible(true)
        runCurrent()
        assertEquals(1, r.fake.sent.count { it == TvCommands.SERIAL })
        advanceTimeBy(3_001)
        assertEquals(1, r.fake.sent.count { it == TvCommands.SERIAL })
        assertTrue(r.fake.sent.count { it == TvCommands.STATUS_QUERY } >= 2)
        r.vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun serialProbeDiscoveryStillStreamsFound() = runTest(UnconfinedTestDispatcher()) {
        val hosts = listOf(ip("192.168.5.30"), ip("192.168.5.31"))
        val innerLan = FakeLan(hosts.associateWith { listOf(ProbeResult.AdbOpen) })
        val source = HostSource { hosts }
        val r = Rig(this, InMemoryCompanionStore(networks = listOf(homeNet)), tv(), home)
        val vm = CompanionViewModel(
            r.store,
            r.source,
            r.upkeep,
            r.connector,
            Discovery(listOf(source), SerialProbe(innerLan)),
            this,
            { 1_000_000L },
            { "tv-1" },
        )
        advanceUntilIdle()
        vm.findTvs()
        advanceUntilIdle()
        assertEquals(listOf("192.168.5.30", "192.168.5.31"), vm.state.value.found)
    }

    @Test
    fun findSweepStopsWhenTrustIsLostMidScan() = runTest(UnconfinedTestDispatcher()) {
        val hosts = listOf(ip("192.168.5.30"), ip("192.168.5.31"), ip("192.168.5.32"), ip("192.168.5.33"))
        val script = hosts.associateWith { listOf(ProbeResult.AdbOpen) }
        val innerLan = FakeLan(script)
        val lan = SerialProbe(innerLan, yieldMs = 1)
        val cafe = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        val switching = object : FingerprintSource {
            override suspend fun current() = if (innerLan.calls.size >= 3) cafe else home
        }
        val source = HostSource { hosts }
        val r = Rig(this, InMemoryCompanionStore(networks = listOf(homeNet)), tv(), home)
        val vm = CompanionViewModel(
            r.store,
            switching,
            r.upkeep,
            r.connector,
            Discovery(listOf(source), lan),
            this,
            clock = { if (innerLan.calls.size >= 3) 1_500L else 0L },
            idGenerator = { "tv-1" },
        )
        advanceUntilIdle()
        vm.findTvs()
        advanceTimeBy(50)
        advanceUntilIdle()
        assertEquals(Copy.NOT_TRUSTED_ERROR, vm.state.value.error)
        assertEquals(false, vm.state.value.scanning)
        assertEquals(listOf("192.168.5.30", "192.168.5.31"), vm.state.value.found)
        assertEquals(3, innerLan.calls.size)
    }

    @Test
    fun classicHostIsNotSavedUntilTheAcknowledgementIsChecked() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addTvByAddress("192.168.5.20")
        assertTrue(r.store.tvs().isEmpty())
        assertTrue(r.fake.opened.isEmpty())
        assertEquals(ClassicAck("192.168.5.20", checked = false), r.vm.state.value.classicAck)
        r.vm.addTvByAddress("192.168.5.20", acknowledged = false)
        assertTrue(r.store.tvs().isEmpty())
        r.vm.addTvByAddress("192.168.5.20", acknowledged = true)
        assertEquals(TvLink.Classic(1_000_000L), r.store.tvs().single().link)
        assertTrue(r.fake.opened.any { it == "192.168.5.20:5555" })
    }

    @Test
    fun classicAckSaysTheNetworkCanChangeCommands() {
        assertEquals(
            "This TV can't use an encrypted connection. SideSpeed can still connect the older way. " +
                "Anyone on your network could see or change the commands it sends.",
            Copy.CLASSIC_ACK.english(),
        )
    }

    @Test
    fun submitPairOnAnUntrustedNetworkSendsNoPairingRequest() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        var paired = false
        val vm = CompanionViewModel(
            r.store, r.source, r.upkeep, r.connector, r.discovery, this, { 1_000_000L }, { "tv-1" },
            pairer = Pairer { _, _, _ ->
                paired = true
                PairResult.Paired
            },
            ports = ConnectPortFinder { 37_123 },
        )
        vm.startPairing("192.168.5.20")
        vm.submitPair("123456", "37000")
        assertEquals(false, paired)
        assertTrue(r.fake.opened.isEmpty())
        assertEquals(Copy.NOT_TRUSTED_ADD, vm.state.value.error)
        assertTrue(r.store.tvs().isEmpty())
    }

    @Test
    fun upgradingASavedTvOffItsTrustedNetworkOpensNoSession() = runTest(UnconfinedTestDispatcher()) {
        val office = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        val store = InMemoryCompanionStore(
            tvs = listOf(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", status = TvStatus.RUNNING, link = allowed)),
            networks = listOf(homeNet, TrustedNetwork("office", "Office", office)),
        )
        val r = Rig(this, store, tv(), office)
        var paired = false
        val vm = CompanionViewModel(
            store, r.source, r.upkeep, r.connector, r.discovery, this, { 1_000_000L }, { "tv-1" },
            pairer = Pairer { _, _, _ ->
                paired = true
                PairResult.Paired
            },
            ports = ConnectPortFinder { 37_123 },
        )
        vm.refreshNow()
        runCurrent()
        vm.pairSaved("tv-1")
        vm.submitPair("123456", "37000")
        assertEquals(false, paired)
        assertTrue(r.fake.opened.isEmpty())
        assertEquals(Copy.NOT_TRUSTED_ADD, vm.state.value.error)
        assertEquals(allowed, store.tv("tv-1").link)
    }

    @Test
    fun aNetworkChangeBetweenPairingAndConnectingOpensNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", link = allowed))
        val away = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        var paired = false
        val vm = CompanionViewModel(
            r.store, r.source, r.upkeep, r.connector, r.discovery, this, { 1_000_000L }, { "tv-1" },
            pairer = Pairer { _, _, _ ->
                paired = true
                r.fp = away
                PairResult.Paired
            },
            ports = ConnectPortFinder { 37_123 },
        )
        vm.refreshNow()
        runCurrent()
        vm.pairSaved("tv-1")
        vm.submitPair("123456", "37000")
        assertEquals(true, paired)
        assertTrue(r.fake.opened.isEmpty(), r.fake.opened.joinToString())
        assertEquals(Copy.NOT_TRUSTED_ADD, vm.state.value.error)
        assertEquals(allowed, r.store.tv("tv-1").link)
    }

    @Test
    fun submitPairOnTheTrustedNetworkStillConnects() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<String>()
        val r = rig(trusted = true)
        val vm = CompanionViewModel(
            r.store, r.source, r.upkeep, r.connector, r.discovery, this, { 1_000_000L }, { "tv-1" },
            pairer = Pairer { host, port, code ->
                seen += "$host:$port:$code"
                PairResult.Paired
            },
            ports = ConnectPortFinder { 37_123 },
        )
        vm.startPairing("192.168.5.20")
        vm.submitPair("123456", "37000")
        assertEquals(listOf("192.168.5.20:37000:123456"), seen)
        assertEquals(TvLink.Encrypted(37_123), r.store.tvs().single().link)
        assertTrue(r.fake.opened.any { it == "192.168.5.20:37123" })
    }

    @Test
    fun tlsHostIsPairedThenSavedOnTheEncryptedPort() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<String>()
        val r = rig(trusted = true)
        val vm = CompanionViewModel(
            r.store, r.source, r.upkeep, r.connector, r.discovery, this, { 1_000_000L }, { "tv-1" },
            pairer = Pairer { host, port, code ->
                seen += "$host:$port:$code"
                PairResult.Paired
            },
            ports = ConnectPortFinder { 37_123 },
        )
        vm.addTvByAddress("192.168.5.20", acknowledged = true)
        assertTrue(r.store.tvs().isEmpty())
        assertEquals("192.168.5.20", vm.state.value.pairing?.address)
        vm.submitPair("123456", "37000")
        assertEquals(listOf("192.168.5.20:37000:123456"), seen)
        assertEquals(TvLink.Encrypted(37_123), r.store.tvs().single().link)
        assertTrue(r.fake.opened.none { it.endsWith(":5555") })
        assertTrue(r.fake.opened.any { it == "192.168.5.20:37123" })
    }

    @Test
    fun typedConnectPortIsSavedWhenMdnsFindsNone() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        val vm = CompanionViewModel(
            r.store, r.source, r.upkeep, r.connector, r.discovery, this, { 1_000_000L }, { "tv-1" },
            pairer = Pairer { _, _, _ -> PairResult.Paired },
            ports = ConnectPortFinder { null },
        )
        vm.startPairing("192.168.5.20")
        vm.submitPair("123456", "37000")
        assertEquals(true, vm.state.value.pairing?.askConnectPort)
        assertTrue(r.store.tvs().isEmpty())
        vm.submitConnectPort("41000")
        assertEquals(TvLink.Encrypted(41_000), r.store.tvs().single().link)
        assertTrue(r.fake.opened.none { it.endsWith(":5555") })
        assertTrue(r.fake.opened.any { it == "192.168.5.20:41000" })
    }

    @Test
    fun aLegacyTvIsNotContactedUntilItIsAcknowledged() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.store.saveTv(SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", link = TvLink.Classic(null)))
        r.vm.refreshNow()
        assertEquals(TvAttention.LEGACY_ACK, r.vm.state.value.tvs.single().attention)
        r.vm.onOpen()
        assertTrue(r.fake.opened.isEmpty())
        r.vm.acknowledgeClassic("tv-1")
        assertEquals(TvLink.Classic(1_000_000L), r.store.tv("tv-1").link)
        assertTrue(r.fake.opened.any { it == "192.168.5.20:5555" })
    }

    @Test fun homeSavesAnEncryptedTvThatMovedWhenTheSerialMatches() = runTest(UnconfinedTestDispatcher()) {
        val session = tv().also { it.outputs[TvCommands.STATUS_QUERY] = compilingRow }
        val opened = mutableListOf<String>()
        val connector = TvConnector { address, port ->
            opened += "$address:$port"
            if (address == "192.168.5.20") throw io.github.devjardins.sidespeed.companion.tv.TvUnreachable(null)
            session
        }
        val services = listOf(
            TlsConnectService("192.168.5.88", 38_001, "adb-SER-11-cccccc"),
            TlsConnectService("192.168.8.40", 39_001, "adb-SER-1-bbbbbb"),
        )
        val ports = object : ConnectPortFinder {
            override suspend fun find(host: String) = services.firstOrNull { it.host == host }?.port
            override suspend fun servicesFor(host: String): List<TlsConnectService> = services
        }
        val store = InMemoryCompanionStore(networks = listOf(homeNet))
        store.saveTv(
            SavedTv("tv-1", "Cube", "192.168.5.20", "SER-1", "home", status = TvStatus.RUNNING, link = TvLink.Encrypted(37_000)),
        )
        val source = FingerprintSource { home }
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner(), ports = ports) { 1_000_000L }
        val vm = CompanionViewModel(
            store, source, upkeep, connector, Discovery(listOf(SweepSource), FakeLan()), this,
            { 1_000_000L }, { "tv-1" }, ports = ports,
        )
        vm.refreshNow()
        runCurrent()
        vm.setHomeVisible(true)
        runCurrent()
        assertEquals("192.168.8.40", store.tv("tv-1").address)
        assertEquals(TvLink.Encrypted(39_001), store.tv("tv-1").link)
        assertEquals(listOf("192.168.5.20:37000", "192.168.8.40:39001"), opened)
        vm.setHomeVisible(false)
        runCurrent()
    }

    @Test
    fun refreshShowsEveryCardWhileOfferLookupsHang() = runTest(UnconfinedTestDispatcher()) {
        val vm = legacyVm(count = 4, offer = LinkOffer { suspendCancellableCoroutine { } }, ports = hangingPorts())
        try {
            assertTrue(vm.state.value.loaded)
            assertEquals(4, vm.state.value.tvs.size)
            assertEquals(List(4) { TvAttention.LEGACY_ACK }, vm.state.value.tvs.map { it.attention })
        } finally {
            coroutineContext[Job]?.cancelChildren()
        }
    }

    @Test
    fun legacyOfferLookupsRunTogether() = runTest(UnconfinedTestDispatcher()) {
        var entered = 0
        val vm = legacyVm(
            count = 2,
            offer = LinkOffer {
                entered += 1
                suspendCancellableCoroutine { }
            },
        )
        try {
            assertTrue(vm.state.value.loaded)
            assertEquals(2, entered)
            assertEquals(List(2) { TvAttention.LEGACY_ACK }, vm.state.value.tvs.map { it.attention })
        } finally {
            coroutineContext[Job]?.cancelChildren()
        }
    }

    @Test
    fun legacyOfferLookupsStayWithinTheBound() = runTest(UnconfinedTestDispatcher()) {
        var entered = 0
        val vm = legacyVm(
            count = 5,
            offer = LinkOffer {
                entered += 1
                suspendCancellableCoroutine { }
            },
        )
        try {
            assertTrue(vm.state.value.loaded)
            assertEquals(5, vm.state.value.tvs.size)
            assertEquals(4, entered)
        } finally {
            coroutineContext[Job]?.cancelChildren()
        }
    }

    @Test
    fun aLegacyCardOffersPairWhenTheLookupFindsTls() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Boolean>()
        val vm = legacyVm(count = 1, offer = LinkOffer { gate.await() })
        assertEquals(TvAttention.LEGACY_ACK, vm.state.value.tvs.single().attention)
        gate.complete(true)
        assertEquals(TvAttention.LEGACY_PAIR, vm.state.value.tvs.single().attention)
    }

    @Test
    fun aKnownLegacyOfferIsNotLookedUpAgainOnRefresh() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val vm = legacyVm(count = 1, offer = LinkOffer { calls += 1; false })
        assertEquals(1, calls)
        assertEquals(TvAttention.LEGACY_ACK, vm.state.value.tvs.single().attention)
        vm.refreshNow()
        assertEquals(1, calls)
        assertEquals(TvAttention.LEGACY_ACK, vm.state.value.tvs.single().attention)
    }

    private suspend fun TestScope.legacyVm(
        count: Int,
        offer: LinkOffer,
        ports: ConnectPortFinder = ConnectPortFinder { null },
    ): CompanionViewModel {
        val store = InMemoryCompanionStore(networks = listOf(homeNet))
        repeat(count) { i ->
            store.saveTv(
                SavedTv(
                    "tv-$i",
                    "TV $i",
                    "192.168.250.${10 + i}",
                    "SER-$i",
                    "home",
                    status = TvStatus.NEEDS_ATTENTION,
                    link = TvLink.Classic(null),
                ),
            )
        }
        val connector = tv().connector()
        val source = FingerprintSource { home }
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner(), ports = ports) { 1_000_000L }
        return CompanionViewModel(
            store, source, upkeep, connector, Discovery(listOf(SweepSource), FakeLan()), this,
            { 1_000_000L }, { "tv-x" }, ports = ports, linkOffer = offer,
        )
    }

    private fun hangingPorts() = object : ConnectPortFinder {
        override suspend fun find(host: String): Int? = suspendCancellableCoroutine { }
    }
}
