package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.OverlayPeer
import io.github.devjardins.sidespeed.companion.net.OverlaySnapshot
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.upkeep.EgressCheck
import io.github.devjardins.sidespeed.companion.upkeep.EgressRoute
import io.github.devjardins.sidespeed.companion.upkeep.OverlayAccess
import io.github.devjardins.sidespeed.companion.upkeep.OverlaySource
import io.github.devjardins.sidespeed.companion.upkeep.TcpScanner
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import io.github.devjardins.sidespeed.companion.discovery.FakeLan
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayViewModelTest {
    private val apk = "/data/app/x/base.apk"
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val hotel = NetworkFingerprint("10.9.9.1", "10.9.9.0/24", setOf("10.9.9.1"))
    private val homeNet = TrustedNetwork("home", "Home", home)
    private val trusted = TrustedOverlay("overlay-1", "Tailnet", "me@example.com", "nSELF")
    private fun ip(s: String) = Ipv4.parse(s)!!

    private fun peer(id: String, ip: String, shared: Boolean = false) = OverlayPeer(id, "h", "android", listOf(ip(ip)), true, shared)
    private fun snap(vararg peers: OverlayPeer, running: Boolean = true, tun: Boolean = true, tailnet: String = "me@example.com", self: String = "nSELF") =
        OverlaySnapshot(tailnet, self, listOf(ip("100.100.1.1")), running, tun, peers.toList())

    private fun tv(serial: String = "SER-1") = FakeTvSession(
        mapOf(
            TvCommands.SERIAL to serial, TvCommands.MODEL to "Fire TV Cube", TvCommands.MANUFACTURER to "Amazon",
            TvCommands.PKG_PATH to "package:$apk", TvCommands.PIDOF to "123",
            TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
        ),
    )

    private class Rig(scope: TestScope, val store: InMemoryCompanionStore, val fake: FakeTvSession, var fp: NetworkFingerprint?, var snapshot: OverlaySnapshot?) {
        var egress: (Ipv4) -> EgressRoute? = { EgressRoute("utun4", listOf(Ipv4.parse("100.100.1.1")!!)) }
        private val source = FingerprintSource { fp }
        private val access = OverlayAccess(OverlaySource { snapshot }, EgressCheck { egress(it) })
        private val connector = fake.connector()
        private val upkeep = Upkeep(store, source, connector, HelperKeeper(), TcpScanner(), access) { 1_000_000L }
        private var n = 1
        val vm = CompanionViewModel(
            store, source, upkeep, connector, Discovery(listOf(SweepSource), FakeLan()), scope, { 1_000_000L }, { "tv-" + (n++) }, access,
        )
    }

    private fun TestScope.rig(
        fp: NetworkFingerprint? = home,
        snapshot: OverlaySnapshot? = snap(peer("nCUBE", "100.101.1.1")),
        lan: Boolean = true,
        overlay: Boolean = true,
        fake: FakeTvSession = tv(),
    ): Rig {
        val store = InMemoryCompanionStore(networks = if (lan) listOf(homeNet) else emptyList(), overlays = if (overlay) listOf(trusted) else emptyList())
        return Rig(this, store, fake, fp, snapshot)
    }

    @Test fun trustCurrentOverlaySavesTheTailnetAndNode() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(overlay = false)
        r.vm.trustCurrentOverlay("My tailnet")
        val saved = r.store.trustedOverlays().single()
        assertEquals("My tailnet", saved.name)
        assertEquals("me@example.com", saved.tailnet)
        assertEquals("nSELF", saved.selfId)
        assertTrue(saved.id.startsWith("overlay-"))
        assertEquals(1, r.vm.state.value.trustedOverlays.size)
        assertEquals(true, r.vm.state.value.tailnet?.trusted)
    }

    @Test fun trustingTheSameTailnetTwiceKeepsTheFirstEntry() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(overlay = false)
        r.vm.trustCurrentOverlay("First")
        r.vm.trustCurrentOverlay("Second")
        assertEquals(listOf("First"), r.store.trustedOverlays().map { it.name })
    }

    @Test fun anOverlayThatIsNotUsableCannotBeTrusted() = runTest(UnconfinedTestDispatcher()) {
        for (s in listOf(null, snap(running = false), snap(tun = false))) {
            val r = rig(overlay = false, snapshot = s)
            r.vm.trustCurrentOverlay("x")
            assertTrue(r.store.trustedOverlays().isEmpty())
            assertEquals(Copy.NO_OVERLAY, r.vm.state.value.error)
        }
    }

    @Test fun stateTellsWhatTailscaleIsDoing() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(overlay = false)
        r.vm.refreshNow()
        assertEquals(TailnetState("me@example.com", trusted = false, ready = true), r.vm.state.value.tailnet)
        r.snapshot = snap(tun = false)
        r.vm.refreshNow()
        assertEquals(TailnetState("me@example.com", trusted = false, ready = false), r.vm.state.value.tailnet)
        r.snapshot = null
        r.vm.refreshNow()
        assertNull(r.vm.state.value.tailnet)
    }

    @Test fun aPeerAddressBindsTheTvToTheTailnetEvenAwayFromHome() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(fp = hotel, lan = false)
        r.vm.addTvByAddress("100.101.1.1")
        val saved = r.store.tvs().single()
        assertEquals(trusted.id, saved.trustedNetworkId)
        assertEquals("nCUBE", saved.overlayPeerId)
        assertEquals("100.101.1.1", saved.address)
    }

    @Test fun aPeerAddressPrefersTheTailnetOverTheHomeNetwork() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.vm.addTvByAddress("100.101.1.1")
        assertEquals(trusted.id, r.store.tvs().single().trustedNetworkId)
    }

    @Test fun anAddressNoPeerHasStaysOnTheTrustedNetwork() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.vm.addTvByAddress("100.101.1.9")
        val saved = r.store.tvs().single()
        assertEquals("home", saved.trustedNetworkId)
        assertNull(saved.overlayPeerId)
    }

    @Test fun privateAddressesNeverBindToTheTailnet() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(snapshot = snap(peer("nCUBE", "192.168.5.20")))
        r.vm.addTvByAddress("192.168.5.20")
        assertEquals("home", r.store.tvs().single().trustedNetworkId)
    }

    @Test fun aSharedInPeerIsRefusedEvenAtHome() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(snapshot = snap(peer("nCUBE", "100.101.1.1", shared = true)))
        r.vm.addTvByAddress("100.101.1.1")
        assertTrue(r.store.tvs().isEmpty())
        assertEquals(Copy.SHARED_PEER, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
    }

    @Test fun aClosedRouteAwayFromHomeIsNotBound() = runTest(UnconfinedTestDispatcher()) {
        val routed = rig(fp = hotel, lan = false)
        routed.egress = { EgressRoute("en0", listOf(ip("10.9.9.20"))) }
        routed.vm.addTvByAddress("100.101.1.1")
        assertTrue(routed.store.tvs().isEmpty())
        assertEquals(Copy.NOT_TRUSTED_ADD, routed.vm.state.value.error)
        assertTrue(routed.fake.opened.isEmpty())
    }

    @Test fun awayFromHomeWithoutTailscaleNothingCanBeAdded() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(fp = hotel, snapshot = null)
        r.vm.addTvByAddress("100.101.1.1")
        assertTrue(r.store.tvs().isEmpty())
        assertEquals(Copy.NOT_TRUSTED_ADD, r.vm.state.value.error)
    }

    @Test fun theSameSerialOnTheTailnetAndTheHomeNetworkAreTwoTvs() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(snapshot = snap(peer("nCUBE", "100.101.1.1")))
        r.vm.addTvByAddress("192.168.5.20")
        r.vm.addTvByAddress("100.101.1.1")
        val tvs = r.store.tvs()
        assertEquals(2, tvs.size)
        assertEquals(setOf("home", trusted.id), tvs.map { it.trustedNetworkId }.toSet())
    }

    @Test fun onOpenAwayFromHomeChecksTailnetTvsAndClearsTheAllowSkip() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(fp = hotel, lan = false)
        r.store.saveTv(
            SavedTv(
                "o", "Cube", "100.101.1.1", "SER-1", trusted.id,
                status = TvStatus.WAITING_FOR_ALLOW, skipBackgroundUntilUserAction = true, overlayPeerId = "nCUBE",
            ),
        )
        r.vm.onOpen()
        assertTrue(r.fake.opened.isNotEmpty())
        assertFalse(r.store.tv("o").skipBackgroundUntilUserAction)
        assertEquals(TvStatus.RUNNING, r.store.tv("o").status)
    }

    @Test fun reAddingOnASecondTrustedNetworkMovesTheTvInsteadOfDuplicating() = runTest(UnconfinedTestDispatcher()) {
        val cabin = NetworkFingerprint("10.1.1.1", "10.1.1.0/24", setOf("10.1.1.1"))
        val store = InMemoryCompanionStore(networks = listOf(homeNet, TrustedNetwork("cabin", "Cabin", cabin)))
        val r = Rig(this, store, tv(), home, null)
        r.vm.addTvByAddress("192.168.5.20")
        r.fp = cabin
        r.vm.addTvByAddress("10.1.1.20")
        val saved = r.store.tvs().single()
        assertEquals("cabin", saved.trustedNetworkId)
        assertEquals("10.1.1.20", saved.address)
    }

    @Test fun anAddressThatChangedPeerDuringTheConnectIsNotSaved() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(fp = hotel, lan = false)
        var routes = 0
        r.egress = {
            // The first route check is the binding before the connect; the address then moves to another node.
            if (++routes == 1) r.snapshot = snap(peer("nOTHER", "100.101.1.1"))
            EgressRoute("utun4", listOf(ip("100.100.1.1")))
        }
        r.vm.addTvByAddress("100.101.1.1")
        assertTrue(r.store.tvs().isEmpty())
        assertEquals(Copy.NOT_TRUSTED_ADD, r.vm.state.value.error)
    }

    @Test fun aBareTailscaleAddressSaysWhichNetworkKeepsItAlive() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.vm.addTvByAddress("100.101.1.9")   // no peer has it: saved under "home"
        assertEquals("Kept alive while on Home", r.vm.state.value.tvs.single().scopeNote)
    }

    @Test fun peerBoundAndLanTvsCarryNoScopeNote() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.vm.addTvByAddress("100.101.1.1")   // peer-bound
        assertNull(r.vm.state.value.tvs.single().scopeNote)
        val lan = rig()
        lan.vm.addTvByAddress("192.168.5.20")
        assertNull(lan.vm.state.value.tvs.single().scopeNote)
    }

    @Test fun aScopeNoteNeedsTheNetworkToExist() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.store.saveTv(SavedTv("x", "TV", "100.101.1.9", "SER-9", trustedNetworkId = "gone"))
        r.vm.refreshNow()
        assertNull(r.vm.state.value.tvs.single().scopeNote)
    }

    @Test fun noRouteAnswerMeansNoBinding() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(fp = hotel, lan = false)
        r.egress = { null }
        r.vm.addTvByAddress("100.101.1.1")
        assertTrue(r.store.tvs().isEmpty())
        assertEquals(Copy.NOT_TRUSTED_ADD, r.vm.state.value.error)
        assertTrue(r.fake.opened.isEmpty())
    }

    @Test fun removingAnOverlayRemovesItsTvsAndKeepsOthers() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.store.saveTv(SavedTv("o", "O", "100.101.1.1", "S-O", trusted.id, overlayPeerId = "nCUBE"))
        r.store.saveTv(SavedTv("l", "L", "192.168.5.20", "S-L", "home"))
        r.vm.removeTrustedOverlay(trusted.id)
        assertEquals(listOf("l"), r.store.tvs().map { it.id })
        assertTrue(r.store.trustedOverlays().isEmpty())
    }

    @Test fun aTailnetTvIsCheckableWhileTheTailnetIsTrusted() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.store.saveTv(SavedTv("o", "Cube", "100.101.1.1", "SER-1", trusted.id, overlayPeerId = "nCUBE"))
        r.vm.refreshNow()
        assertTrue(r.vm.state.value.tvs.single().checkableHere)
        r.snapshot = snap(peer("nCUBE", "100.101.1.1"), tailnet = "someone-else@example.com")
        r.vm.refreshNow()
        assertFalse(r.vm.state.value.tvs.single().checkableHere)
    }
}
