package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.OverlayPeer
import io.github.devjardins.sidespeed.companion.net.OverlaySnapshot
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayUpkeepTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private val apk = "/data/app/x/base.apk"
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val hotel = NetworkFingerprint("10.9.9.1", "10.9.9.0/24", setOf("10.9.9.1"))
    private val homeNet = TrustedNetwork("home", "Home", home)
    private val overlay = TrustedOverlay("overlay-1", "Home tailnet", "me@example.com", "nSELF")
    private var now = 1_000_000L

    private fun lanTv(id: String, address: String) = SavedTv(id, "TV $id", address, "SER-$id", "home")
    private fun ovTv(id: String, address: String, peer: String) =
        SavedTv(id, "TV $id", address, "SER-$id", overlay.id, overlayPeerId = peer)

    private fun peer(id: String, vararg ips: String, shared: Boolean = false) =
        OverlayPeer(id, "host-$id", "android", ips.map(::ip), online = true, shared = shared)

    private fun snap(
        vararg peers: OverlayPeer,
        tailnet: String = overlay.tailnet,
        running: Boolean = true,
        tun: Boolean = true,
    ) = OverlaySnapshot(tailnet, overlay.selfId, listOf(ip("100.100.1.1")), running, tun, peers.toList())

    private fun running(serial: String) = FakeTvSession(
        mapOf(
            TvCommands.SERIAL to serial,
            TvCommands.PKG_PATH to "package:$apk",
            TvCommands.PIDOF to "123",
            TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
        ),
    )

    private class Net {
        val sessions = mutableMapOf<String, FakeTvSession>()
        val opened = mutableListOf<String>()
        var onOpen: suspend (String) -> Unit = {}
        val connector = TvConnector { address, port ->
            opened += address
            onOpen(address)
            val s = sessions[address] ?: throw TvUnreachable(null)
            s.connector().open(address, port)
            s
        }
    }

    private class Scan : Scanner {
        val calls = mutableListOf<String>()
        override suspend fun scan(subnet: String, port: Int, near: String?): List<String> { calls += subnet; return emptyList() }
    }

    private class Snapshots(var current: OverlaySnapshot?) : OverlaySource {
        var calls = 0
        override suspend fun snapshot(): OverlaySnapshot? { calls++; return current }
    }

    /** Every address leaves through the tailnet's own interface unless told otherwise. */
    private class Egress(var decide: (Ipv4) -> EgressRoute? = { EgressRoute("utun4", listOf(Ipv4.parse("100.100.1.1")!!)) }) : EgressCheck {
        override suspend fun route(address: Ipv4): EgressRoute? = decide(address)
    }

    private fun upkeep(
        store: InMemoryCompanionStore,
        net: Net,
        snapshots: Snapshots?,
        egress: Egress = Egress(),
        fingerprint: NetworkFingerprint? = hotel,
        scanner: Scan = Scan(),
    ) = Upkeep(
        store, FingerprintSource { fingerprint }, net.connector,
        HelperKeeper(pollIntervalMs = 100, pollTimeoutMs = 1_000), scanner,
        overlay = snapshots?.let { OverlayAccess(it, egress) },
    ) { now }

    private suspend fun store(vararg tvs: SavedTv) =
        InMemoryCompanionStore(tvs.toList(), listOf(homeNet)).also { it.saveTrustedOverlay(overlay) }

    @Test fun anOverlayTvIsKeptAliveAwayFromHome() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"))
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
        val report = upkeep(st, net, Snapshots(snap(peer("nA", "100.101.1.1")))).runOnce(Trigger.BACKGROUND)
        assertFalse(report.gated)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("100.101.1.1"), net.opened)
    }

    @Test fun aReachedOverlayTvRecordsTailnetReach() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"))
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
        upkeep(st, net, Snapshots(snap(peer("nA", "100.101.1.1")))).runOnce(Trigger.BACKGROUND)
        assertEquals(Reach.REACHED_TAILNET, st.tv("a").reach)
    }

    @Test fun anUnreachableOverlayTvRecordsUnreachableReach() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"))
        upkeep(st, Net(), Snapshots(snap(peer("nA", "100.101.1.1")))).runOnce(Trigger.BACKGROUND)
        assertEquals(Reach.UNREACHABLE, st.tv("a").reach)
    }

    @Test fun aTvWhosePeerIsGoneRecordsUnreachableReach() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"))
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
        upkeep(st, net, Snapshots(snap())).runOnce(Trigger.BACKGROUND)
        assertEquals(Reach.UNREACHABLE, st.tv("a").reach)
        assertTrue(net.opened.isEmpty())
    }

    @Test fun aClosedLanGateStillVisitsTailnetTvs() = runTest {
        val st = store(lanTv("l", "192.168.5.50"), ovTv("o", "100.101.1.1", "nA"))
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-o") }
        // Trusted when the run starts, gone by the LAN TV's gate check; the tailnet part never re-reads it.
        var reads = 0
        val source = FingerprintSource { if (reads++ == 0) home else hotel }
        val report = Upkeep(
            st, source, net.connector,
            HelperKeeper(pollIntervalMs = 100, pollTimeoutMs = 1_000), Scan(),
            overlay = OverlayAccess(Snapshots(snap(peer("nA", "100.101.1.1"))), Egress()),
        ) { now }.runOnce(Trigger.BACKGROUND)
        assertEquals(mapOf("o" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("100.101.1.1"), net.opened)
        assertTrue(report.gated)
    }

    @Test fun aClosedOverlayGatesTheRunWithoutAConnection() = runTest {
        val tv = ovTv("a", "100.101.1.1", "nA")
        val cases = mapOf(
            "tailscale stopped" to snap(peer("nA", "100.101.1.1"), running = false),
            "userspace mode" to snap(peer("nA", "100.101.1.1"), tun = false),
            "another tailnet" to snap(peer("nA", "100.101.1.1"), tailnet = "other@example.com"),
            "no snapshot" to null,
        )
        for ((why, s) in cases) {
            val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
            val report = upkeep(store(tv), net, Snapshots(s)).runOnce(Trigger.RESTART_NOW)
            assertTrue(report.gated, why)
            assertTrue(net.opened.isEmpty(), why)
        }
    }

    @Test fun aSharedOrUnknownPeerIsNeverContacted() = runTest {
        val tv = ovTv("a", "100.101.1.1", "nA")
        for (s in listOf(snap(peer("nA", "100.101.1.1", shared = true)), snap(peer("nB", "100.101.1.1")), snap())) {
            val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
            val report = upkeep(store(tv), net, Snapshots(s)).runOnce(Trigger.BACKGROUND)
            assertFalse(report.gated)
            assertTrue(net.opened.isEmpty())
            assertTrue(report.visited.isEmpty())
        }
    }

    @Test fun aMissingPeerDoesNotStopTheRunAndCountsAsAFailedCheck() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nGONE"), ovTv("b", "100.101.1.2", "nB"))
        val net = Net().also { it.sessions["100.101.1.2"] = running("SER-b") }
        val report = upkeep(st, net, Snapshots(snap(peer("nB", "100.101.1.2")))).runOnce(Trigger.BACKGROUND)
        assertFalse(report.gated)
        assertEquals(mapOf("b" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("100.101.1.2"), net.opened)
        assertEquals(1, st.tv("a").offlineCount)
    }

    @Test fun aMissingPeerBeforeALanTvStillLetsTheLanTvRun() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nGONE"), lanTv("l", "192.168.5.50"))
        val net = Net().also { it.sessions["192.168.5.50"] = running("SER-l") }
        val report = upkeep(st, net, Snapshots(snap()), fingerprint = home).runOnce(Trigger.BACKGROUND)
        assertFalse(report.gated)
        assertEquals(mapOf("l" to TvStatus.RUNNING), report.visited)
    }

    @Test fun aClosedOverlayStillLetsLanTvsRun() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"), lanTv("l", "192.168.5.50"))
        val net = Net().also {
            it.sessions["100.101.1.1"] = running("SER-a")
            it.sessions["192.168.5.50"] = running("SER-l")
        }
        val lanRoute = Egress { EgressRoute("en0", listOf(ip("192.168.5.9"))) }
        val report = upkeep(st, net, Snapshots(snap(peer("nA", "100.101.1.1"))), lanRoute, fingerprint = home)
            .runOnce(Trigger.BACKGROUND)
        assertTrue(report.gated, "the overlay part was gated")
        assertEquals(mapOf("l" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("192.168.5.50"), net.opened)
    }

    @Test fun aPeerWhoseOnlyAddressIsQuad100IsNeverContacted() = runTest {
        val st = store(ovTv("a", "100.100.100.100", "nA"))
        val net = Net().also { it.sessions["100.100.100.100"] = running("SER-a") }
        val report = upkeep(st, net, Snapshots(snap(peer("nA", "100.100.100.100")))).runOnce(Trigger.BACKGROUND)
        assertTrue(net.opened.isEmpty())
        assertFalse(report.gated)
        assertEquals(1, st.tv("a").offlineCount)
    }

    @Test fun aRouteOutAPhysicalInterfaceClosesTheGate() = runTest {
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
        val hostileLan = Egress { EgressRoute("en0", listOf(ip("10.9.9.20"))) }
        val report = upkeep(store(ovTv("a", "100.101.1.1", "nA")), net, Snapshots(snap(peer("nA", "100.101.1.1"))), hostileLan)
            .runOnce(Trigger.BACKGROUND)
        assertTrue(report.gated)
        assertTrue(net.opened.isEmpty())
        val unknown = upkeep(store(ovTv("a", "100.101.1.1", "nA")), net, Snapshots(snap(peer("nA", "100.101.1.1"))), Egress { null })
            .runOnce(Trigger.BACKGROUND)
        assertTrue(unknown.gated)
        assertTrue(net.opened.isEmpty())
    }

    @Test fun aPeerThatMovedIsFollowedOnlyOnceTheSerialMatches() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"))
        val net = Net().also { it.sessions["100.101.1.2"] = running("SER-a") }
        val report = upkeep(st, net, Snapshots(snap(peer("nA", "100.101.1.2")))).runOnce(Trigger.BACKGROUND)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertEquals("100.101.1.2", st.tv("a").address)
        assertEquals(listOf("100.101.1.2"), net.opened)

        val wrong = store(ovTv("a", "100.101.1.1", "nA"))
        val net2 = Net().also { it.sessions["100.101.1.2"] = running("SOMEONE-ELSE") }
        upkeep(wrong, net2, Snapshots(snap(peer("nA", "100.101.1.2")))).runOnce(Trigger.BACKGROUND)
        assertEquals("100.101.1.1", wrong.tv("a").address)
        assertEquals(TvStatus.WRONG_DEVICE, wrong.tv("a").status)
    }

    @Test fun aMovedPeerOutsideTheOverlayRangeIsNotFollowed() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"))
        val net = Net().also { it.sessions["8.8.8.8"] = running("SER-a") }
        val report = upkeep(st, net, Snapshots(snap(peer("nA", "8.8.8.8")))).runOnce(Trigger.BACKGROUND)
        assertFalse(report.gated)
        assertTrue(net.opened.isEmpty())
        assertEquals(1, st.tv("a").offlineCount)
    }

    @Test fun anUnreachableOverlayTvIsNeverRefoundByAScan() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA").copy(offlineCount = 5))
        val scan = Scan()
        val report = upkeep(st, Net(), Snapshots(snap(peer("nA", "100.101.1.1"))), scanner = scan).runOnce(Trigger.BACKGROUND)
        assertFalse(report.gated)
        assertTrue(scan.calls.isEmpty())
        assertEquals(6, st.tv("a").offlineCount)
        assertEquals(TvStatus.OFFLINE, st.tv("a").status)
    }

    @Test fun bothScopesRunWhenBothMatchAndLanTvsStayHomeOnly() = runTest {
        val st = store(lanTv("l", "192.168.5.50"), ovTv("o", "100.101.1.1", "nA"))
        val net = Net().also {
            it.sessions["192.168.5.50"] = running("SER-l")
            it.sessions["100.101.1.1"] = running("SER-o")
        }
        val both = upkeep(st, net, Snapshots(snap(peer("nA", "100.101.1.1"))), fingerprint = home).runOnce(Trigger.BACKGROUND)
        assertEquals(setOf("l", "o"), both.visited.keys)

        val away = Net().also { it.sessions["100.101.1.1"] = running("SER-o"); it.sessions["192.168.5.50"] = running("SER-l") }
        val awayReport = upkeep(st, away, Snapshots(snap(peer("nA", "100.101.1.1"))), fingerprint = hotel).runOnce(Trigger.BACKGROUND)
        assertEquals(setOf("o"), awayReport.visited.keys)
        assertEquals(listOf("100.101.1.1"), away.opened)
    }

    @Test fun theGateIsReadAgainBetweenOverlayTvs() = runTest {
        val st = store(ovTv("a", "100.101.1.1", "nA"), ovTv("b", "100.101.1.2", "nB"))
        val snapshots = Snapshots(snap(peer("nA", "100.101.1.1"), peer("nB", "100.101.1.2")))
        val net = Net().also {
            it.sessions["100.101.1.1"] = running("SER-a")
            it.sessions["100.101.1.2"] = running("SER-b")
            it.onOpen = { snapshots.current = snap(running = false) }
        }
        val report = upkeep(st, net, snapshots).runOnce(Trigger.BACKGROUND)
        assertTrue(report.gated)
        assertEquals(setOf("a"), report.visited.keys)
        assertEquals(listOf("100.101.1.1"), net.opened)
    }

    @Test fun withoutOverlaySupportOverlayTvsAreSimplyNotChecked() = runTest {
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
        val report = upkeep(store(ovTv("a", "100.101.1.1", "nA")), net, snapshots = null).runOnce(Trigger.BACKGROUND)
        assertTrue(report.gated)
        assertTrue(net.opened.isEmpty())
    }

    @Test fun anOverlayTvWithoutAPeerBindingIsNeverContacted() = runTest {
        val unbound = SavedTv("a", "TV a", "100.101.1.1", "SER-a", overlay.id)
        val net = Net().also { it.sessions["100.101.1.1"] = running("SER-a") }
        val report = upkeep(store(unbound), net, Snapshots(snap(peer("nA", "100.101.1.1")))).runOnce(Trigger.BACKGROUND)
        assertTrue(net.opened.isEmpty())
        assertTrue(report.visited.isEmpty())
    }

    @Test fun overlayTvsWithNoTrustedOverlayStoredNeverReadTailscale() = runTest {
        val st = InMemoryCompanionStore(listOf(lanTv("l", "192.168.5.50")), listOf(homeNet))
        val snapshots = Snapshots(snap())
        val net = Net().also { it.sessions["192.168.5.50"] = running("SER-l") }
        upkeep(st, net, snapshots, fingerprint = home).runOnce(Trigger.BACKGROUND)
        assertEquals(0, snapshots.calls, "no overlay is trusted, so Tailscale is never asked")
    }
}
