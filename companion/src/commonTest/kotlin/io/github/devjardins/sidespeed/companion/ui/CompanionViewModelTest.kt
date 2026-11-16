package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.AdbProbe
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
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun findTvsOnUntrustedNetworkErrorsAndDoesNotScan() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.vm.findTvs()
        assertEquals("Join a Trusted Network to find TVs", r.vm.state.value.error)
        assertEquals(0, r.lan.calls.size)
    }

    @Test
    fun addTvSavesSerialAndModelThenRunsUpkeep() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addTvByAddress("192.168.5.20")
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
        r.vm.addTvByAddress("192.168.5.20")
        r.store.updateTv("tv-1") { it.copy(skipBackgroundUntilUserAction = true) }
        r.vm.restartNow()
        assertEquals(false, r.store.tv("tv-1").skipBackgroundUntilUserAction)
    }

    @Test
    fun nonAmazonTvIsSavedWithGoogleTvNote() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true, fake = tv("Google"))
        r.vm.addTvByAddress("192.168.5.20")
        assertEquals(1, r.store.tvs().size)
        assertEquals(
            "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts.",
            r.vm.state.value.tvs.single().note,
        )
    }

    @Test
    fun addPublicOrBadAddressConnectsToNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        for (publicAddress in listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1")) {
            r.vm.addTvByAddress(publicAddress)
            assertEquals(Copy.OUTSIDE_NETWORK, r.vm.state.value.error, publicAddress)
        }
        r.vm.addTvByAddress("not-an-ip")
        assertEquals(Copy.BAD_ADDRESS, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
        assertTrue(r.store.tvs().isEmpty())
    }

    @Test
    fun anyPrivateAddressCanBeAddedWhileOnATrustedNetwork() = runTest(UnconfinedTestDispatcher()) {
        // 192.168.9.20 and 10.9.9.9 are private but outside the trusted 192.168.5.0/24 (a /22 or a second subnet).
        for (address in listOf("192.168.9.20", "10.9.9.9", "172.16.4.4")) {
            val r = rig(trusted = true)
            r.vm.addTvByAddress(address)
            assertEquals(setOf("$address:5555"), r.fake.opened.toSet(), address)
            assertEquals(address, r.store.tvs().single().address)
        }
    }

    @Test
    fun privateAddressIsStillRefusedOffATrustedNetwork() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.fp = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
        r.vm.addTvByAddress("192.168.5.20")
        assertEquals(Copy.NOT_TRUSTED_ERROR, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
    }

    @Test
    fun addErrorPathsShowMessagesAndSaveNothing() = runTest(UnconfinedTestDispatcher()) {
        val pending = rig(trusted = true, fake = tv().also { it.authPending = true })
        pending.vm.addTvByAddress("192.168.5.20")
        assertEquals(Copy.ALLOW_STEP, pending.vm.state.value.error)
        val down = rig(trusted = true, fake = tv().also { it.failOnOpen = true })
        down.vm.addTvByAddress("192.168.5.20")
        assertEquals(Copy.UNREACHABLE, down.vm.state.value.error)
        val blank = rig(trusted = true, fake = tv().also { it.outputs[TvCommands.SERIAL] = "" })
        blank.vm.addTvByAddress("192.168.5.20")
        assertEquals(Copy.NO_SERIAL, blank.vm.state.value.error)
        assertTrue(pending.store.tvs().isEmpty() && down.store.tvs().isEmpty() && blank.store.tvs().isEmpty())
        assertEquals(false, blank.vm.state.value.busy)
    }

    @Test
    fun restartNowWithTvIdChecksOnlyThatTv() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addTvByAddress("192.168.5.20")
        r.store.saveTv(r.store.tv("tv-1").copy(id = "tv-2", serial = "OTHER", address = "192.168.5.21"))
        r.fake.opened.clear()
        r.vm.restartNow("tv-1")
        assertEquals(listOf("192.168.5.20:5555"), r.fake.opened)
    }

    @Test
    fun removingTrustedNetworkRemovesItsTvsAndRetrustKeepsName() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addTvByAddress("192.168.5.20")
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
        r.vm.addTvByAddress("192.168.5.20")
        assertEquals("192.168.5.20", r.vm.state.value.addedAddress)
        assertTrue(r.vm.state.value.adding.isEmpty())
        assertTrue(r.vm.state.value.addErrors.isEmpty())
    }

    @Test
    fun pressingAnAlreadyAddedAddressAgainDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addTvByAddress("192.168.5.20")
        val opens = r.fake.opened.size
        r.vm.addTvByAddress("192.168.5.20")
        assertEquals(opens, r.fake.opened.size)
        assertEquals(1, r.store.tvs().size)
        assertEquals("192.168.5.20", r.vm.state.value.addedAddress)
    }

    @Test
    fun failedAddShowsAnErrorForThatAddressAndCanBeRetried() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true, fake = tv().also { it.failOnOpen = true })
        r.vm.addTvByAddress("192.168.5.20")
        assertEquals(Copy.UNREACHABLE, r.vm.state.value.addErrors["192.168.5.20"])
        assertTrue(r.vm.state.value.adding.isEmpty())
        assertNull(r.vm.state.value.addedAddress)
        r.fake.failOnOpen = false
        r.vm.addTvByAddress("192.168.5.20")
        assertTrue(r.vm.state.value.addErrors.isEmpty())
        assertEquals(1, r.store.tvs().size)
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
        assertEquals("Scanned 254 of 254", vm.state.value.scanProgress)
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
        assertEquals("Scanned 255 of 255", vm.state.value.scanProgress)
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
        assertEquals("Scanned 4096 of 4096", vm.state.value.scanProgress)
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
    fun completedScanWithoutAdbSetsDiagnosisAndHelp() = runTest(UnconfinedTestDispatcher()) {
        val vm = discoveryRig(FakeLan(allRefused("192.168.5.0/24")))
        vm.findTvs()
        runCurrent()
        assertEquals(Diagnosis.NO_ADB_ON_LIVE_HOSTS, vm.state.value.diagnosis)
        assertTrue(
            helpFor(vm.state.value.diagnosis!!).endsWith("or type the address shown in SideSpeed on the TV"),
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
}
