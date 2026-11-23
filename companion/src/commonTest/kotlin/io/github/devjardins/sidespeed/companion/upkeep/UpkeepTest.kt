package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.PromptedHost
import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpkeepTest {
    private val apk = "/data/app/x/base.apk"
    private val home = NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))
    private val cafe = NetworkFingerprint("10.0.0.1", "10.0.0.0/24", setOf("10.0.0.1"))
    private val homeNet = TrustedNetwork("home", "Trusted Network 1", home)
    private val otherNet = TrustedNetwork("other", "Trusted Network 2", cafe)
    private val hour = 60 * 60 * 1000L

    private fun tv(id: String, address: String, serial: String = "SER-$id", net: String = "home", name: String = "TV $id") =
        SavedTv(
            id = id, name = name, address = address, serial = serial, trustedNetworkId = net,
            link = TvLink.Classic(ackAtEpochMs = 1L),
        )

    /** A TV whose helper runs and is current. */
    private fun runningTv(serial: String) = FakeTvSession(
        mapOf(
            TvCommands.SERIAL to serial,
            TvCommands.PKG_PATH to "package:$apk",
            TvCommands.PIDOF to "123",
            TvCommands.cmdline(123) to "app_process\u0000--apk=$apk",
        ),
    )

    /** A TV whose helper is missing and appears once LAUNCH is sent. */
    private class MissingHelper(val fake: FakeTvSession) : TvSession by fake {
        override suspend fun run(command: String): String {
            if (command == TvCommands.LAUNCH) fake.outputs[TvCommands.PIDOF] = "99"
            return fake.run(command)
        }
    }

    /** Hands out sessions by address; an address with no session (or a failing one) is unreachable. */
    private class FakeNet {
        val sessions = mutableMapOf<String, FakeTvSession>()
        val wrappers = mutableMapOf<String, TvSession>()
        val opened = mutableListOf<String>()
        var onOpen: suspend (String) -> Unit = {}
        val connector = TvConnector { address, port ->
            opened += address
            onOpen(address)
            assertEquals(TvConnector.DEFAULT_PORT, port)
            val s = sessions[address] ?: throw TvUnreachable(null)
            s.connector().open(address, port)
            wrappers[address] ?: s
        }
    }

    private class FakeScanner(var answer: List<String> = emptyList()) : Scanner {
        val calls = mutableListOf<String>()
        val nears = mutableListOf<String?>()
        var onScan: () -> Unit = {}
        override suspend fun scan(subnet: String, port: Int, near: String?): List<String> {
            calls += "$subnet:$port"
            nears += near
            onScan()
            return answer
        }
    }

    /** Returns the given fingerprints in order, repeating the last one. Counts calls. */
    private class ScriptedSource(vararg val fps: NetworkFingerprint?) : FingerprintSource {
        var calls = 0
        override suspend fun current(): NetworkFingerprint? = fps[minOf(calls++, fps.size - 1)]
    }

    private class MutableSource(var fp: NetworkFingerprint?) : FingerprintSource {
        override suspend fun current(): NetworkFingerprint? = fp
    }

    private var now = 1_000_000L

    private fun upkeep(
        store: InMemoryCompanionStore,
        net: FakeNet,
        scanner: FakeScanner = FakeScanner(),
        source: FingerprintSource = FingerprintSource { home },
    ) = Upkeep(store, source, net.connector, HelperKeeper(pollIntervalMs = 100, pollTimeoutMs = 1_000), scanner) { now }

    @Test fun untrustedFingerprintIsGated() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet().also { it.sessions["192.168.5.50"] = runningTv("SER-a") }
        val scanner = FakeScanner()
        val report = upkeep(store, net, scanner, FingerprintSource { cafe }).runOnce(Trigger.RESTART_NOW)
        assertTrue(report.gated)
        assertTrue(report.visited.isEmpty())
        assertTrue(net.opened.isEmpty())
        assertTrue(scanner.calls.isEmpty())
    }

    @Test fun nullFingerprintIsGated() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet().also { it.sessions["192.168.5.50"] = runningTv("SER-a") }
        val scanner = FakeScanner()
        val report = upkeep(store, net, scanner, FingerprintSource { null }).runOnce(Trigger.APP_OPEN)
        assertTrue(report.gated)
        assertTrue(net.opened.isEmpty())
        assertTrue(scanner.calls.isEmpty())
    }

    @Test fun noTrustedNetworkChosenIsGated() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), networks = emptyList())
        val net = FakeNet().also { it.sessions["192.168.5.50"] = runningTv("SER-a") }
        val report = upkeep(store, net).runOnce(Trigger.APP_OPEN)
        assertTrue(report.gated)
        assertTrue(net.opened.isEmpty())
    }

    @Test fun tvOnAnotherTrustedNetworkIsNotVisited() = runTest {
        val store = InMemoryCompanionStore(
            listOf(tv("a", "192.168.5.50"), tv("b", "10.0.0.9", net = "other")),
            listOf(homeNet, otherNet),
        )
        val net = FakeNet().also {
            it.sessions["192.168.5.50"] = runningTv("SER-a")
            it.sessions["10.0.0.9"] = runningTv("SER-b")
        }
        val report = upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertFalse(report.gated)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("192.168.5.50"), net.opened)
        assertEquals(TvStatus.UNKNOWN, store.tv("b").status)
        assertEquals(now, store.tv("a").lastCheckedEpochMs)
    }

    @Test fun missingHelperIsStartedAndReported() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50", name = "Living room")), listOf(homeNet))
        val fake = runningTv("SER-a").also {
            it.outputs[TvCommands.PIDOF] = ""
            it.outputs[TvCommands.LAUNCH] = ""
        }
        val net = FakeNet().also {
            it.sessions["192.168.5.50"] = fake
            it.wrappers["192.168.5.50"] = MissingHelper(fake)
        }
        val report = upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("Living room"), report.restartedNames)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
        assertTrue(fake.closed, "session is closed after the visit")
    }

    @Test fun readOnlyStoreStillChecksButSavesNothing() = runTest {
        val before = tv("a", "192.168.5.50", name = "Living room")
        val store = InMemoryCompanionStore(listOf(before), listOf(homeNet), savedByNewerVersion = true)
        val net = FakeNet().also { it.sessions["192.168.5.50"] = runningTv("SER-a") }
        val report = upkeep(store, net).runOnce(Trigger.APP_OPEN)
        assertTrue(net.opened.isNotEmpty(), "upkeep still checks the TV")
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited, "the check still counts as visited")
        assertEquals(before, store.tv("a"), "a read-only store saves nothing")
        assertTrue(store.promptedHosts().isEmpty())
    }

    @Test fun threeUnreachableRunsGoOfflineThenRefindByScan() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(status = TvStatus.RUNNING)), listOf(homeNet))
        val net = FakeNet()
        val scanner = FakeScanner(listOf("192.168.5.77"))
        val u = upkeep(store, net, scanner)

        u.runOnce(Trigger.BACKGROUND)
        assertEquals(1, store.tv("a").offlineCount)
        assertEquals(TvStatus.RUNNING, store.tv("a").status, "last known status kept before 3 failures")
        now += 15 * 60_000
        u.runOnce(Trigger.BACKGROUND)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
        assertTrue(scanner.calls.isEmpty())

        net.sessions["192.168.5.77"] = runningTv("SER-a")
        now += 15 * 60_000
        val report = u.runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.0/24:5555"), scanner.calls)
        val saved = store.tv("a")
        assertEquals("192.168.5.77", saved.address)
        assertEquals(TvStatus.RUNNING, saved.status)
        assertEquals(0, saved.offlineCount)
        assertEquals(now, saved.lastRefindEpochMs)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        // Opened .50 (failed), .77 for the serial, .77 again for the visit.
        assertEquals(listOf("192.168.5.50", "192.168.5.50", "192.168.5.50", "192.168.5.77", "192.168.5.77"), net.opened)
    }

    /** Emits the given hosts one at a time, stalling after each; records how many got out. */
    private class FlowScanner(val hosts: List<String>) : Scanner {
        var emitted = 0
        override suspend fun scan(subnet: String, port: Int, near: String?): List<String> = emptyList()
        override fun hosts(subnet: String, port: Int, near: String?): Flow<String> = flow {
            for (h in hosts) {
                emitted++
                emit(h)
                delay(60_000)
            }
        }
    }

    @Test fun refindStopsAtTheFirstSerialMatch() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(homeNet))
        val net = FakeNet().also { it.sessions["192.168.5.77"] = runningTv("SER-a") }
        val scanner = FlowScanner(listOf("192.168.5.77", "192.168.5.78", "192.168.5.79"))
        val u = Upkeep(store, FingerprintSource { home }, net.connector, HelperKeeper(pollIntervalMs = 100, pollTimeoutMs = 1_000), scanner) { now }
        val report = u.runOnce(Trigger.BACKGROUND)
        assertEquals("192.168.5.77", store.tv("a").address)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertEquals(listOf("192.168.5.50", "192.168.5.77", "192.168.5.77"), net.opened)
        assertEquals(1, scanner.emitted, "sweep cancelled before the second host")
    }

    /** Emits nothing and suspends until the collection is cancelled. */
    private class HangingScanner : Scanner {
        var cancelled = false
        override suspend fun scan(subnet: String, port: Int, near: String?): List<String> = emptyList()
        override fun hosts(subnet: String, port: Int, near: String?): Flow<String> = flow {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
    }

    @Test fun refindStopsWhenTheGateClosesMidSweep() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(homeNet))
        val net = FakeNet()
        val scanner = HangingScanner()
        // Three reads happen before the sweep starts (runOnce scope, LAN branch gate, check gate);
        // the watcher's first recheck 10 s in is the first to see the network change.
        val u = Upkeep(store, ScriptedSource(home, home, home, cafe), net.connector, HelperKeeper(pollIntervalMs = 100, pollTimeoutMs = 1_000), scanner) { now }
        val report = u.runOnce(Trigger.BACKGROUND)
        assertTrue(report.gated)
        assertTrue(scanner.cancelled, "sweep flow was cancelled")
        assertEquals(listOf("192.168.5.50"), net.opened, "no connection made")
        assertEquals("192.168.5.50", store.tv("a").address)
    }

    @Test fun refindRunsAtMostOncePerHour() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet()
        val scanner = FakeScanner(emptyList())
        val u = upkeep(store, net, scanner)
        repeat(3) { u.runOnce(Trigger.BACKGROUND); now += 15 * 60_000 }
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
        assertEquals(1, scanner.calls.size)

        u.runOnce(Trigger.BACKGROUND) // 4th, within the hour
        assertEquals(1, scanner.calls.size)
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
        assertEquals(4, store.tv("a").offlineCount)

        now = store.tv("a").lastRefindEpochMs!! + hour
        u.runOnce(Trigger.BACKGROUND)
        assertEquals(2, scanner.calls.size)
    }

    @Test fun refindSkipsHostWithOtherSerialAndSendsItNothingElse() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(homeNet))
        val stranger = FakeTvSession(mapOf(TvCommands.SERIAL to "SOMEONE-ELSE"))
        val blank = FakeTvSession(mapOf(TvCommands.SERIAL to ""))
        val net = FakeNet().also {
            it.sessions["192.168.5.23"] = stranger
            it.sessions["192.168.5.24"] = blank
        }
        val scanner = FakeScanner(listOf("192.168.5.23", "192.168.5.24"))
        val report = upkeep(store, net, scanner).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf(TvCommands.SERIAL), stranger.sent)
        assertEquals(listOf(TvCommands.SERIAL), blank.sent)
        assertTrue(stranger.closed && blank.closed)
        assertEquals("192.168.5.50", store.tv("a").address)
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
        assertEquals(mapOf("a" to TvStatus.OFFLINE), report.visited)
    }

    @Test fun authPendingWaitsForUserAction() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val fake = runningTv("SER-a").also { it.authPending = true }
        val net = FakeNet().also { it.sessions["192.168.5.50"] = fake }
        val u = upkeep(store, net)

        val first = u.runOnce(Trigger.BACKGROUND)
        assertEquals(mapOf("a" to TvStatus.WAITING_FOR_ALLOW), first.visited)
        assertTrue(store.tv("a").skipBackgroundUntilUserAction)
        assertEquals(1, net.opened.size)

        u.runOnce(Trigger.BACKGROUND)
        u.runOnce(Trigger.NETWORK_JOINED)
        assertEquals(1, net.opened.size, "background runs do not reconnect (would pop Allow again)")

        fake.authPending = false
        val open = u.runOnce(Trigger.APP_OPEN)
        assertEquals(2, net.opened.size)
        assertEquals(mapOf("a" to TvStatus.RUNNING), open.visited)
        assertFalse(store.tv("a").skipBackgroundUntilUserAction)
    }

    @Test fun networkChangeBetweenTvsStopsTheRun() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50"), tv("b", "192.168.5.51")), listOf(homeNet))
        val net = FakeNet().also {
            it.sessions["192.168.5.50"] = runningTv("SER-a")
            it.sessions["192.168.5.51"] = runningTv("SER-b")
        }
        val report = upkeep(store, net, source = ScriptedSource(home, home, cafe)).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.50"), net.opened)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertTrue(report.gated)
    }

    @Test fun networkChangeBeforeScanSkipsTheScan() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(homeNet))
        val net = FakeNet()
        val scanner = FakeScanner(listOf("192.168.5.77"))
        val report = upkeep(store, net, scanner, ScriptedSource(home, home, cafe)).runOnce(Trigger.BACKGROUND)
        assertTrue(scanner.calls.isEmpty())
        assertTrue(report.gated)
        assertEquals(null, store.tv("a").lastRefindEpochMs)
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
        assertEquals(mapOf("a" to TvStatus.OFFLINE), report.visited)
    }

    @Test fun lostConnectionCountsAsFailedCheck() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(status = TvStatus.RUNNING)), listOf(homeNet))
        val fake = runningTv("SER-a").also { it.loseConnectionOn = TvCommands.PKG_PATH }
        val net = FakeNet().also { it.sessions["192.168.5.50"] = fake }
        val scanner = FakeScanner()
        val u = upkeep(store, net, scanner)
        u.runOnce(Trigger.BACKGROUND)
        assertEquals(1, store.tv("a").offlineCount)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
        u.runOnce(Trigger.BACKGROUND)
        u.runOnce(Trigger.BACKGROUND)
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
        assertTrue(scanner.calls.isEmpty(), "the address answered ADB, so no re-find")
    }

    @Test fun statusMapping() = runTest {
        val store = InMemoryCompanionStore(
            listOf(tv("w", "192.168.5.60"), tv("n", "192.168.5.61")),
            listOf(homeNet),
        )
        val net = FakeNet().also {
            it.sessions["192.168.5.60"] = runningTv("NOT-MINE")
            it.sessions["192.168.5.61"] = runningTv("SER-n").also { s -> s.outputs[TvCommands.PKG_PATH] = "" }
        }
        val report = upkeep(store, net).runOnce(Trigger.RESTART_NOW)
        assertEquals(mapOf("w" to TvStatus.WRONG_DEVICE, "n" to TvStatus.NOT_INSTALLED), report.visited)
        assertTrue(report.restartedNames.isEmpty())
    }

    @Test fun aReachedLanTvRecordsLanReach() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet().also { it.sessions["192.168.5.50"] = runningTv("SER-a") }
        upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertEquals(Reach.REACHED_LAN, store.tv("a").reach)
    }

    @Test fun aTvWithoutTheHelperInstalledStillCountsAsReached() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("n", "192.168.5.61")), listOf(homeNet))
        val net = FakeNet().also {
            it.sessions["192.168.5.61"] = runningTv("SER-n").also { s -> s.outputs[TvCommands.PKG_PATH] = "" }
        }
        upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertEquals(TvStatus.NOT_INSTALLED, store.tv("n").status)
        assertEquals(Reach.REACHED_LAN, store.tv("n").reach)
    }

    @Test fun anUnreachableTvRecordsUnreachableReach() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        upkeep(store, FakeNet()).runOnce(Trigger.BACKGROUND)
        assertEquals(Reach.UNREACHABLE, store.tv("a").reach)
    }

    @Test fun aRefoundTvRecordsReachAtTheNewAddress() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(homeNet))
        val net = FakeNet().also { it.sessions["192.168.5.77"] = runningTv("SER-a") }
        upkeep(store, net, FakeScanner(listOf("192.168.5.77"))).runOnce(Trigger.BACKGROUND)
        assertEquals("192.168.5.77", store.tv("a").address)
        assertEquals(Reach.REACHED_LAN, store.tv("a").reach)
    }

    @Test fun aBareTailscaleAddressOnTheHomeNetworkRecordsTailnetReach() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "100.101.1.9")), listOf(homeNet))
        val net = FakeNet().also { it.sessions["100.101.1.9"] = runningTv("SER-a") }
        upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertEquals(Reach.REACHED_TAILNET, store.tv("a").reach)
    }

    // --- Fix round 1 ---------------------------------------------------------------------------------------

    private fun offlineTv() = tv("a", "192.168.5.50").copy(offlineCount = 2)

    @Test fun gateIsCheckedBeforeEachRefindConnect() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        val source = MutableSource(home)
        val net = FakeNet().also {
            it.sessions["192.168.5.23"] = FakeTvSession(mapOf(TvCommands.SERIAL to "OTHER-1"))
            it.sessions["192.168.5.24"] = FakeTvSession(mapOf(TvCommands.SERIAL to "OTHER-2"))
            it.onOpen = { address -> if (address == "192.168.5.23") source.fp = cafe }
        }
        val scanner = FakeScanner(listOf("192.168.5.23", "192.168.5.24"))
        val report = upkeep(store, net, scanner, source).runOnce(Trigger.BACKGROUND)
        assertTrue("192.168.5.24" !in net.opened, "opened: ${net.opened}")
        assertTrue(report.gated)
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
        assertEquals(now, store.tv("a").lastRefindEpochMs)
    }

    @Test fun gateClosingRightAfterTheScanOpensNoHost() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        val source = MutableSource(home)
        val net = FakeNet().also { it.sessions["192.168.5.23"] = FakeTvSession(mapOf(TvCommands.SERIAL to "SER-a")) }
        val scanner = FakeScanner(listOf("192.168.5.23")).also { it.onScan = { source.fp = cafe } }
        val report = upkeep(store, net, scanner, source).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.50"), net.opened)
        assertTrue(report.gated)
    }

    @Test fun gateClosingBetweenFindingAndVisitingKeepsTheAddressButDoesNotVisit() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        val source = MutableSource(home)
        val found = runningTv("SER-a")
        val net = FakeNet().also {
            it.sessions["192.168.5.77"] = found
            it.onOpen = { address -> if (address == "192.168.5.77") source.fp = cafe }
        }
        val report = upkeep(store, net, FakeScanner(listOf("192.168.5.77")), source).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.50", "192.168.5.77"), net.opened)
        assertEquals(listOf(TvCommands.SERIAL), found.sent)
        assertTrue(report.gated)
        assertEquals("192.168.5.77", store.tv("a").address)
        assertEquals(TvStatus.OFFLINE, store.tv("a").status)
    }

    @Test fun skipFlaggedFirstTvThenGateFlipVisitsNothing() = runTest {
        val store = InMemoryCompanionStore(
            listOf(tv("s", "192.168.5.40").copy(skipBackgroundUntilUserAction = true), tv("b", "192.168.5.51")),
            listOf(homeNet),
        )
        val net = FakeNet().also {
            it.sessions["192.168.5.40"] = runningTv("SER-s")
            it.sessions["192.168.5.51"] = runningTv("SER-b")
        }
        val report = upkeep(store, net, source = ScriptedSource(home, cafe)).runOnce(Trigger.BACKGROUND)
        assertTrue(net.opened.isEmpty())
        assertTrue(report.gated)
    }

    @Test fun refindCandidateLosingConnectionIsSkipped() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        val flaky = FakeTvSession(mapOf(TvCommands.SERIAL to "SER-a"), loseConnectionOn = TvCommands.SERIAL)
        val net = FakeNet().also {
            it.sessions["192.168.5.23"] = flaky
            it.sessions["192.168.5.77"] = runningTv("SER-a")
        }
        upkeep(store, net, FakeScanner(listOf("192.168.5.23", "192.168.5.77"))).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf(TvCommands.SERIAL), flaky.sent)
        assertEquals("192.168.5.77", store.tv("a").address)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
    }

    @Test fun promptedForeignHostIsSkippedUntilUserActionOrExpiry() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        val foreign = FakeTvSession(authPending = true)
        val net = FakeNet().also { it.sessions["192.168.5.23"] = foreign }
        val scanner = FakeScanner(listOf("192.168.5.23"))
        val u = upkeep(store, net, scanner)
        val opens = { net.opened.count { it == "192.168.5.23" } }

        u.runOnce(Trigger.BACKGROUND)
        assertEquals(1, opens())
        assertEquals(listOf("192.168.5.23"), store.prompted.map { it.address })

        now += hour
        u.runOnce(Trigger.BACKGROUND)
        u.runOnce(Trigger.NETWORK_JOINED) // not due again within the hour anyway
        now += hour
        u.runOnce(Trigger.NETWORK_JOINED)
        assertEquals(3, scanner.calls.size)
        assertEquals(1, opens(), "a prompted device is not bothered again by background re-finds")

        now += hour
        u.runOnce(Trigger.APP_OPEN)
        assertEquals(2, opens(), "a user action clears the list")

        now = store.prompted.single().atEpochMs + PromptedHost.PROMPTED_HOST_TTL_MS
        u.runOnce(Trigger.BACKGROUND)
        assertEquals(3, opens(), "entries expire after 24 h")
    }

    @Test fun savingATvClearsPromptedHosts() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        store.prompted = listOf(PromptedHost("home", "192.168.5.23", now))
        store.saveTv(tv("b", "192.168.5.51"))
        assertTrue(store.promptedHosts().isEmpty())
    }

    @Test fun wrongDeviceThreeTimesStartsRefind() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val stranger = runningTv("NOT-MINE")
        val net = FakeNet().also {
            it.sessions["192.168.5.50"] = stranger
            it.sessions["192.168.5.77"] = runningTv("SER-a")
        }
        val scanner = FakeScanner(listOf("192.168.5.50", "192.168.5.77"))
        val u = upkeep(store, net, scanner)
        u.runOnce(Trigger.BACKGROUND)
        u.runOnce(Trigger.BACKGROUND)
        assertEquals(TvStatus.WRONG_DEVICE, store.tv("a").status)
        assertEquals(2, store.tv("a").offlineCount)
        assertTrue(scanner.calls.isEmpty())

        u.runOnce(Trigger.BACKGROUND)
        assertEquals(1, scanner.calls.size)
        assertEquals("192.168.5.77", store.tv("a").address)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
        assertTrue(stranger.sent.all { it == TvCommands.SERIAL })
    }

    @Test fun tvRemovedDuringVisitIsNotResurrected() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet().also {
            it.sessions["192.168.5.50"] = runningTv("SER-a")
            it.onOpen = { store.removeTv("a") }
        }
        val report = upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertTrue(store.tvs().isEmpty())
        assertTrue(report.visited.isEmpty())
    }

    @Test fun tvRenamedDuringVisitKeepsTheRename() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet().also {
            it.sessions["192.168.5.50"] = runningTv("SER-a")
            it.onOpen = { store.saveTv(store.tv("a").copy(name = "Bedroom")) }
        }
        upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertEquals("Bedroom", store.tv("a").name)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
        assertEquals(now, store.tv("a").lastCheckedEpochMs)
    }

    @Test fun clockMovedBackMakesRefindDue() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv().copy(lastRefindEpochMs = now + hour)), listOf(homeNet))
        val scanner = FakeScanner()
        upkeep(store, FakeNet(), scanner).runOnce(Trigger.BACKGROUND)
        assertEquals(1, scanner.calls.size)
        assertEquals(now, store.tv("a").lastRefindEpochMs)
    }

    @Test fun scannerResultsOutsideTheSubnetAreIgnored() = runTest {
        val store = InMemoryCompanionStore(listOf(offlineTv()), listOf(homeNet))
        val net = FakeNet()
        val bogus = listOf("10.0.0.5", "192.168.5.0", "192.168.5.255", "192.168.6.7", "example.com", "192.168.5.x")
        upkeep(store, net, FakeScanner(bogus)).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.50"), net.opened)
    }

    @Test fun refindCoversASlash22TrustedNetwork() = runTest {
        val wide = NetworkFingerprint("192.168.4.1", "192.168.4.0/22", setOf("192.168.4.1"))
        val wideNet = TrustedNetwork("home", "Trusted Network 1", wide)
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(wideNet))
        val net = FakeNet().also { it.sessions["192.168.6.77"] = runningTv("SER-a") }
        val scanner = FakeScanner(listOf("192.168.6.77"))
        val report = upkeep(store, net, scanner, FingerprintSource { wide }).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.4.0/22:5555"), scanner.calls)
        assertEquals(listOf<String?>("192.168.5.50"), scanner.nears)
        assertEquals("192.168.6.77", store.tv("a").address)
        assertEquals(TvStatus.RUNNING, store.tv("a").status)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
    }

    @Test fun refindOnASlash8ScansTheSlash16AroundTheTv() = runTest {
        val big = NetworkFingerprint("10.0.0.1", "10.0.0.0/8", setOf("10.0.0.1"))
        val store = InMemoryCompanionStore(
            listOf(tv("a", "10.20.30.40").copy(offlineCount = 2)),
            listOf(TrustedNetwork("home", "Office", big)),
        )
        val scanner = FakeScanner()
        upkeep(store, FakeNet(), scanner, FingerprintSource { big }).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("10.20.0.0/16:5555"), scanner.calls)
    }

    @Test fun refindDropsTheScopesNetworkAndBroadcast() = runTest {
        val wide = NetworkFingerprint("192.168.4.1", "192.168.4.0/22", setOf("192.168.4.1"))
        val wideNet = TrustedNetwork("home", "Trusted Network 1", wide)
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(wideNet))
        val net = FakeNet()
        val scanner = FakeScanner(listOf("192.168.4.0", "192.168.7.255", "192.168.8.1"))
        upkeep(store, net, scanner, FingerprintSource { wide }).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.50"), net.opened)
    }

    @Test fun refindNeverScansASubnetOutsidePrivateSpace() = runTest {
        val pub = NetworkFingerprint("8.8.8.1", "8.8.8.0/24", setOf("8.8.8.1"))
        val store = InMemoryCompanionStore(
            listOf(tv("a", "8.8.8.50").copy(trustedNetworkId = "pub", offlineCount = 3)),
            listOf(TrustedNetwork("pub", "Pub", pub)),
        )
        val scanner = FakeScanner(listOf("8.8.8.77"))
        upkeep(store, FakeNet(), scanner, FingerprintSource { pub }).runOnce(Trigger.BACKGROUND)
        assertTrue(scanner.calls.isEmpty(), "scanned ${scanner.calls}")
    }

    @Test fun anUnacknowledgedClassicTvIsNotContacted() = runTest {
        val store = InMemoryCompanionStore(
            listOf(tv("a", "192.168.5.50").copy(link = TvLink.Classic(null))),
            listOf(homeNet),
        )
        val net = FakeNet()
        upkeep(store, net).runOnce(Trigger.BACKGROUND)
        assertTrue(net.opened.isEmpty())
        assertEquals(TvStatus.NEEDS_ATTENTION, store.tv("a").status)
        assertEquals(false, store.tv("a").skipBackgroundUntilUserAction)
    }

    @Test fun classicIsLeftAloneWhenTheTvOffersTls() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50")), listOf(homeNet))
        val net = FakeNet().also { it.sessions["192.168.5.50"] = runningTv("SER-a") }
        val check = Upkeep(
            store, FingerprintSource { home }, net.connector, HelperKeeper(), FakeScanner(),
            classicTls = { true },
        ) { now }
        check.runOnce(Trigger.BACKGROUND)
        assertTrue(net.opened.isEmpty())
        assertEquals(TvStatus.NEEDS_ATTENTION, store.tv("a").status)
        assertEquals(false, store.tv("a").skipBackgroundUntilUserAction)
    }

    @Test fun anEncryptedTvWithNoPortNeedsAttentionAndIsNotRefound() = runTest {
        val store = InMemoryCompanionStore(
            listOf(tv("a", "192.168.5.50").copy(link = TvLink.Encrypted(null), offlineCount = 5)),
            listOf(homeNet),
        )
        val net = FakeNet()
        val scanner = FakeScanner(listOf("192.168.5.77"))
        upkeep(store, net, scanner).runOnce(Trigger.BACKGROUND)
        assertTrue(net.opened.isEmpty())
        assertTrue(scanner.calls.isEmpty())
        assertEquals(TvStatus.NEEDS_ATTENTION, store.tv("a").status)
        assertEquals(false, store.tv("a").skipBackgroundUntilUserAction)
    }
}
