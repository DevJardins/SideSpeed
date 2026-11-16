package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.store.InMemoryCompanionStore
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.FakeTvSession
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
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
        SavedTv(id = id, name = name, address = address, serial = serial, trustedNetworkId = net)

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
        val connector = TvConnector { address, port ->
            opened += address
            assertEquals(TvConnector.DEFAULT_PORT, port)
            val s = sessions[address] ?: throw TvUnreachable(null)
            s.connector().open(address, port)
            wrappers[address] ?: s
        }
    }

    private class FakeScanner(var answer: List<String> = emptyList()) : Scanner {
        val calls = mutableListOf<String>()
        override suspend fun scan(subnet: String, port: Int): List<String> {
            calls += "$subnet:$port"
            return answer
        }
    }

    /** Returns the given fingerprints in order, repeating the last one. Counts calls. */
    private class ScriptedSource(vararg val fps: NetworkFingerprint?) : FingerprintSource {
        var calls = 0
        override suspend fun current(): NetworkFingerprint? = fps[minOf(calls++, fps.size - 1)]
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
        val report = upkeep(store, net, source = ScriptedSource(home, cafe)).runOnce(Trigger.BACKGROUND)
        assertEquals(listOf("192.168.5.50"), net.opened)
        assertEquals(mapOf("a" to TvStatus.RUNNING), report.visited)
        assertTrue(report.gated)
    }

    @Test fun networkChangeBeforeScanSkipsTheScan() = runTest {
        val store = InMemoryCompanionStore(listOf(tv("a", "192.168.5.50").copy(offlineCount = 2)), listOf(homeNet))
        val net = FakeNet()
        val scanner = FakeScanner(listOf("192.168.5.77"))
        val report = upkeep(store, net, scanner, ScriptedSource(home, cafe)).runOnce(Trigger.BACKGROUND)
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
}
