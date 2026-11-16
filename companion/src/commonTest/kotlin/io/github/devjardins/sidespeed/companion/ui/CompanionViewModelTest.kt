package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.upkeep.Scanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private class CountingScanner : Scanner {
        var calls = 0
        override suspend fun scan(subnet: String, port: Int): List<String> { calls++; return emptyList() }
    }

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

    private class Rig(scope: TestScope, val store: InMemoryCompanionStore, val fake: FakeTvSession, var fp: NetworkFingerprint?) {
        val scanner = CountingScanner()
        val source = FingerprintSource { fp }
        val connector = fake.connector()
        val upkeep = Upkeep(store, source, connector, HelperKeeper(), scanner) { 1_000_000L }
        val vm = CompanionViewModel(store, source, upkeep, connector, scanner, scope, { 1_000_000L }, { "tv-1" })
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
        assertEquals(0, r.scanner.calls)
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
    fun addOutsideTrustedSubnetOrBadAddressConnectsToNothing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = true)
        r.vm.addTvByAddress("8.8.8.8")
        assertEquals(Copy.OUTSIDE_NETWORK, r.vm.state.value.error)
        r.vm.addTvByAddress("not-an-ip")
        assertEquals(Copy.BAD_ADDRESS, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
        assertTrue(r.store.tvs().isEmpty())
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

    private class GateScanner(private val result: List<String>) : Scanner {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun scan(subnet: String, port: Int): List<String> { calls++; gate.await(); return result }
    }

    private fun TestScope.scanRig(scanner: Scanner): CompanionViewModel {
        val r = rig(trusted = true)
        return CompanionViewModel(r.store, r.source, r.upkeep, r.connector, scanner, this, { 1_000_000L }, { "tv-1" })
    }

    @Test
    fun scanningIsTrueWhileScanRunsAndRepeatPressesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val scanner = GateScanner(listOf("192.168.5.30"))
        val vm = scanRig(scanner)
        vm.findTvs()
        assertTrue(vm.state.value.scanning)
        vm.findTvs()
        scanner.gate.complete(Unit)
        assertEquals(1, scanner.calls)
        assertEquals(false, vm.state.value.scanning)
        assertEquals(listOf("192.168.5.30"), vm.state.value.found)
        assertEquals(false, vm.state.value.noneFound)
    }

    @Test
    fun emptyScanSetsNoneFoundAndNextScanClearsIt() = runTest(UnconfinedTestDispatcher()) {
        val scanner = GateScanner(emptyList())
        val vm = scanRig(scanner)
        scanner.gate.complete(Unit)
        vm.findTvs()
        assertEquals(false, vm.state.value.scanning)
        assertTrue(vm.state.value.noneFound)
    }

    @Test
    fun untrustedNetworkDoesNotLeaveScanningSet() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(trusted = false)
        r.vm.findTvs()
        assertEquals(false, r.vm.state.value.scanning)
        assertEquals(false, r.vm.state.value.noneFound)
    }
}
