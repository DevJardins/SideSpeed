package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryTest {
    private fun ip(s: String) = Ipv4.parse(s)!!
    private fun cidr(s: String) = Cidr.parse(s)!!
    private fun plan(subnet: String, own: String? = null, gateway: String? = null, port: Int = 5555) =
        DiscoveryPlan(cidr(subnet), own?.let { ip(it) }, gateway?.let { ip(it) }, port)
    private fun sweepDiscovery(lan: FakeLan) = Discovery(listOf(SweepSource), lan)

    @Test
    fun sourcesAreAskedTogether() = runTest {
        var firstCallAt = -1L
        val probe = AdbProbe { _, _, _ ->
            if (firstCallAt < 0) firstCallAt = currentTime
            ProbeResult.AdbAuthRequired
        }
        val a = HostSource { delay(1500); listOf(ip("192.168.5.20")) }
        val b = HostSource { delay(1500); listOf(ip("192.168.5.21")) }
        Discovery(listOf(a, b), probe).run(plan("192.168.5.0/24")).toList()
        assertEquals(1500, firstCallAt)
    }

    @Test
    fun sourceOrderIsKeptAfterConcurrentAsk() = runTest {
        val a = HostSource { delay(100); listOf(ip("192.168.5.30")) }
        val b = HostSource { listOf(ip("192.168.5.31")) }
        val script = mapOf(
            ip("192.168.5.30") to listOf(ProbeResult.AdbOpen),
            ip("192.168.5.31") to listOf(ProbeResult.AdbOpen),
        )
        val lan = FakeLan(script)
        Discovery(listOf(a, b), lan).run(plan("192.168.5.0/24")).toList()
        assertEquals(listOf(ip("192.168.5.30"), ip("192.168.5.31")), lan.calls.take(2).map { it.address })
    }

    @Test
    fun aRefusedPlanNeverAsksAnySource() = runTest {
        val never = HostSource { error("must not be called") }
        val events = Discovery(listOf(never), FakeLan()).run(plan("100.64.0.0/16")).toList()
        assertTrue(events.isEmpty())
    }

    @Test
    fun nonSweepableSubnetOrWrongPortProbesNothing() = runTest {
        for (bad in listOf("8.8.8.0/24", "10.0.0.0/8", "192.168.1.4/31", "192.168.1.4/32")) {
            val lan = FakeLan()
            val events = Discovery(listOf(SweepSource), lan).run(DiscoveryPlan(cidr(bad))).toList()
            assertTrue(events.isEmpty(), bad)
            assertTrue(lan.calls.isEmpty(), bad)
        }
        val lan = FakeLan()
        val events = sweepDiscovery(lan).run(plan("192.168.1.0/24", port = 1234)).toList()
        assertTrue(events.isEmpty())
        assertTrue(lan.calls.isEmpty())
    }

    @Test
    fun neverProbesOwnNetworkOrBroadcastAndOnlyPort5555() = runTest {
        val lan = FakeLan()
        sweepDiscovery(lan).run(plan("192.168.1.0/24", own = "192.168.1.5", gateway = "192.168.1.1")).toList()
        val probed = lan.calls.map { it.address }.toSet()
        assertTrue(ip("192.168.1.5") !in probed)
        assertTrue(ip("192.168.1.0") !in probed)
        assertTrue(ip("192.168.1.255") !in probed)
        assertEquals(253, probed.size)
        assertTrue(lan.calls.all { it.port == 5555 })
        assertTrue(probed.all { it.toString().startsWith("192.168.1.") })
    }

    @Test
    fun firstPassStreamsAdbHostsWithProgress() = runTest {
        // Every host answers, so nothing is silent and there is no second pass.
        val script = cidr("192.168.1.0/24").hosts()
            .associateWith { listOf(ProbeResult.Refused) }.toMutableMap()
        script[ip("192.168.1.10")] = listOf(ProbeResult.AdbAuthRequired)
        script[ip("192.168.1.11")] = listOf(ProbeResult.AdbOpen)
        val lan = FakeLan(script)
        val events = sweepDiscovery(lan).run(plan("192.168.1.0/24")).toList()
        val found = events.filterIsInstance<DiscoveryEvent.Found>()
        assertEquals(
            setOf(ip("192.168.1.10"), ip("192.168.1.11")),
            found.map { it.address }.toSet(),
        )
        val progress = events.filterIsInstance<DiscoveryEvent.Progress>()
        assertTrue(progress.isNotEmpty())
        assertEquals(DiscoveryEvent.Progress(254, 254), progress.last())
        assertTrue(progress.all { it.total == 254 && it.scanned in 1..254 })
    }

    @Test
    fun progressNeverExceedsTheHostCountWhenEveryHostIsReprobed() = runTest {
        // Every host is silent, so all of them get a second pass: that must not inflate the count.
        val events = sweepDiscovery(FakeLan()).run(plan("192.168.1.0/24")).toList()
        val progress = events.filterIsInstance<DiscoveryEvent.Progress>()
        assertTrue(progress.all { it.total == 254 && it.scanned in 1..254 })
        assertEquals(DiscoveryEvent.Progress(254, 254), progress.last())
    }

    @Test
    fun everyPrefixFrom16To30OnEveryPrivateRangeProbesExactlyTheUsableHostsExceptOwn() = runTest {
        val bases = listOf("10.20.0.0", "10.255.255.0", "172.16.0.0", "172.31.240.0", "192.168.0.0", "192.168.250.0")
        for (base in bases) {
            for (prefix in 16..30) {
                val subnet = Cidr.parse("$base/$prefix")!!
                val hosts = subnet.hosts().toList()
                val owns = listOf(null, hosts.first(), hosts.last(), hosts[hosts.size / 2], ip("8.8.8.8"))
                for (own in owns) {
                    val where = "$subnet own=$own"
                    val lan = FakeLan(hosts.associateWith { listOf(ProbeResult.Refused) })
                    val gateway = hosts.first()
                    val events = sweepDiscovery(lan).run(DiscoveryPlan(subnet, own, gateway)).toList()
                    val probed = lan.calls.map { it.address }
                    val expected = hosts.filter { it != own }
                    val cap = SweepOrder.DEFAULT_LIMIT
                    // Never a duplicate, never outside the subnet, never network, broadcast or own, always :5555.
                    assertEquals(probed.size, probed.toSet().size, where)
                    assertTrue(probed.all { it in subnet && it != subnet.network && it != subnet.broadcast && it != own }, where)
                    assertTrue(lan.calls.all { it.port == 5555 }, where)
                    assertEquals(minOf(expected.size, cap), probed.size, where)
                    if (expected.size <= cap) assertEquals(expected.toSet(), probed.toSet(), where)
                    val progress = events.filterIsInstance<DiscoveryEvent.Progress>()
                    assertEquals(DiscoveryEvent.Progress(probed.size, probed.size), progress.last(), where)
                    val done = events.filterIsInstance<DiscoveryEvent.Done>().single()
                    assertEquals(expected.size > cap, done.summary.truncated, where)
                }
            }
        }
    }

    @Test
    fun aSlash16IsCoveredCompletelyByResumingAndNeverRepeatsAHost() = runTest {
        val subnet = cidr("10.0.0.0/16")
        val own = ip("10.0.7.9")
        val lan = FakeLan(subnet.hosts().associateWith { listOf(ProbeResult.Refused) })
        val skip = mutableSetOf<Ipv4>()
        var runs = 0
        do {
            val events = sweepDiscovery(lan)
                .run(DiscoveryPlan(subnet, own, ip("10.0.1.1"), skip = skip.toSet())).toList()
            skip += events.filterIsInstance<DiscoveryEvent.Probed>().map { it.address }
            runs++
            val truncated = events.filterIsInstance<DiscoveryEvent.Done>().single().summary.truncated
        } while (truncated && runs < 40)
        val probed = lan.calls.map { it.address }
        assertEquals(probed.size, probed.toSet().size)
        assertEquals(subnet.hosts().toSet() - own, probed.toSet())
        assertEquals(16, runs)
    }

    @Test
    fun ownAndGatewaySlash24sAreSweptFirstOnABigSubnet() = runTest {
        val subnet = cidr("172.16.0.0/16")
        val lan = FakeLan(subnet.hosts().associateWith { listOf(ProbeResult.Refused) })
        sweepDiscovery(lan).run(DiscoveryPlan(subnet, ip("172.16.200.9"), ip("172.16.3.1"))).toList()
        val probed = lan.calls.map { it.address }.toSet()
        for (third in listOf(200, 3)) {
            for (host in 1..254) {
                val a = ip("172.16.$third.$host")
                if (a != ip("172.16.200.9")) assertTrue(a in probed, a.toString())
            }
        }
    }

    @Test
    fun aPeerSourceMayListOverlayAddressesButNeverPublicOnes() = runTest {
        val peer = ip("100.101.102.103")
        val lan = FakeLan(mapOf(peer to listOf(ProbeResult.AdbAuthRequired)))
        val peers = HostSource { listOf(peer, ip("8.8.8.8"), ip("100.128.0.1")) }
        val events = Discovery(listOf(peers, SweepSource), lan).run(plan("192.168.1.0/30")).toList()
        val found = events.filterIsInstance<DiscoveryEvent.Found>().map { it.address }
        assertEquals(listOf(peer), found)
        val probed = lan.calls.map { it.address }.toSet()
        assertTrue(ip("8.8.8.8") !in probed && ip("100.128.0.1") !in probed)
    }

    @Test
    fun anOverlaySubnetIsNeverSweptEvenWithAPeerSource() = runTest {
        val lan = FakeLan()
        val peers = HostSource { listOf(ip("100.101.102.103")) }
        val events = Discovery(listOf(peers, SweepSource), lan).run(plan("100.64.0.0/16")).toList()
        assertTrue(events.isEmpty())
        assertTrue(lan.calls.isEmpty())
    }

    @Test
    fun secondPassUses1500msOnlyOnSilentHosts() = runTest {
        val slow = ip("192.168.1.1")
        val live = ip("192.168.1.2")
        val lan = FakeLan(
            mapOf(
                slow to listOf(ProbeResult.Timeout, ProbeResult.AdbAuthRequired),
                live to listOf(ProbeResult.Refused),
            ),
        )
        val events = sweepDiscovery(lan).run(plan("192.168.1.0/30")).toList()
        assertEquals(listOf(400, 1500), lan.calls.filter { it.address == slow }.map { it.timeoutMs })
        assertEquals(listOf(400), lan.calls.filter { it.address == live }.map { it.timeoutMs })
        assertTrue(events.filterIsInstance<DiscoveryEvent.Found>().map { it.address }.contains(slow))
    }

    @Test
    fun overlappingSourcesProbeEachHostOnce() = runTest {
        val lan = FakeLan(
            mapOf(
                ip("192.168.1.1") to listOf(ProbeResult.AdbOpen),
                ip("192.168.1.2") to listOf(ProbeResult.Refused),
            ),
        )
        val a = HostSource { listOf(ip("192.168.1.1"), ip("192.168.1.2")) }
        val b = HostSource { listOf(ip("192.168.1.2"), ip("192.168.1.1")) }
        val found = Discovery(listOf(a, b), lan).run(plan("192.168.1.0/30")).toList()
            .filterIsInstance<DiscoveryEvent.Found>()
        assertEquals(listOf(ip("192.168.1.1")), found.map { it.address })
        assertEquals(1, lan.attemptsOf(ip("192.168.1.1")))
        assertEquals(1, lan.attemptsOf(ip("192.168.1.2")))
    }

    @Test
    fun probeOrderFollowsSweepOrder() = runTest {
        val lan = FakeLan()
        sweepDiscovery(lan).run(plan("192.168.4.0/22", own = "192.168.5.140", gateway = "192.168.4.1")).toList()
        val expected = SweepOrder.of(cidr("192.168.4.0/22"), ip("192.168.5.140"), ip("192.168.4.1"))
        // First-pass invocations follow the sweep order; the silent second pass repeats it.
        assertEquals(expected, lan.calls.take(expected.size).map { it.address })
    }

    @Test
    fun doneReportsLiveNonAdbCount() = runTest {
        val lan = FakeLan(
            mapOf(
                ip("192.168.1.1") to listOf(ProbeResult.AdbAuthRequired),
                ip("192.168.1.2") to listOf(ProbeResult.Refused),
            ),
        )
        val events = sweepDiscovery(lan).run(plan("192.168.1.0/30")).toList()
        val done = events.filterIsInstance<DiscoveryEvent.Done>()
        assertEquals(1, done.size)
        assertEquals(setOf(ip("192.168.1.1"), ip("192.168.1.2")), done.single().summary.scanned)
        assertEquals(1, done.single().summary.liveNonAdb)
        assertEquals(false, done.single().summary.truncated)
    }

    @Test
    fun doneReportsTruncationWhenTheLimitCutsTheSweepShort() = runTest {
        val lan = FakeLan()
        val events = sweepDiscovery(lan)
            .run(plan("192.168.1.0/24").copy(limit = 1))
            .toList()
        val done = events.filterIsInstance<DiscoveryEvent.Done>()
        assertEquals(1, done.size)
        assertEquals(1, done.single().summary.scanned.size)
        assertEquals(true, done.single().summary.truncated)
    }

    @Test
    fun collectsOnARealDispatcherWithoutCrashing(): Unit = runBlocking {
        val lan = FakeLan(
            mapOf(
                ip("192.168.1.1") to listOf(ProbeResult.AdbOpen),
                ip("192.168.1.2") to listOf(ProbeResult.Refused),
            ),
        )
        @OptIn(ExperimentalCoroutinesApi::class)
        val io = Dispatchers.IO.limitedParallelism(2)
        val found = Discovery(listOf(SweepSource), lan, io).run(plan("192.168.1.0/30"))
            .filterIsInstance<DiscoveryEvent.Found>().toList()
        assertEquals(listOf(ip("192.168.1.1")), found.map { it.address })
    }

    @Test
    fun skipSetIsNeverProbedAndReportedInSummary() = runTest {
        val lan = FakeLan(mapOf(ip("192.168.1.2") to listOf(ProbeResult.Refused)))
        val events = sweepDiscovery(lan).run(plan("192.168.1.0/30").copy(skip = setOf(ip("192.168.1.1")))).toList()
        assertTrue(lan.calls.none { it.address == ip("192.168.1.1") })
        val done = events.filterIsInstance<DiscoveryEvent.Done>()
        assertEquals(1, done.size)
        assertEquals(setOf(ip("192.168.1.2")), done.single().summary.scanned)
    }

    @Test
    fun cancellationStopsTheSweep() = runTest {
        val lan = FakeLan(delayMs = 1000)
        val events = mutableListOf<DiscoveryEvent>()
        val job = launch { sweepDiscovery(lan).run(plan("192.168.1.0/24")).toList(events) }
        runCurrent()
        advanceTimeBy(500)
        job.cancel()
        runCurrent()
        val callsAfterCancel = lan.calls.size
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(callsAfterCancel in 1 until 253, "calls: $callsAfterCancel")
        assertEquals(callsAfterCancel, lan.calls.size)
        assertTrue(job.isCancelled)
    }

    @Test
    fun probedEventsCarryTheResult() = runTest {
        val lan = FakeLan(mapOf(ip("192.168.1.1") to listOf(ProbeResult.AdbTls), ip("192.168.1.2") to listOf(ProbeResult.NotAdb)))
        val probed = sweepDiscovery(lan).run(plan("192.168.1.0/30")).toList().filterIsInstance<DiscoveryEvent.Probed>()
        assertEquals(
            mapOf(ip("192.168.1.1") to ProbeResult.AdbTls, ip("192.168.1.2") to ProbeResult.NotAdb),
            probed.associate { it.address to it.result },
        )
    }

    @Test
    fun aResumeWithNothingLeftStillReportsDone() = runTest {
        val lan = FakeLan()
        val skip = setOf(ip("192.168.1.1"), ip("192.168.1.2"))
        val events = sweepDiscovery(lan).run(plan("192.168.1.0/30").copy(skip = skip)).toList()
        assertTrue(lan.calls.isEmpty())
        assertEquals(1, events.filterIsInstance<DiscoveryEvent.Done>().size)
    }

    @Test
    fun aTlsHostIsNotCountedAsLiveWithoutAdb() = runTest {
        val lan = FakeLan(mapOf(ip("192.168.1.1") to listOf(ProbeResult.AdbTls), ip("192.168.1.2") to listOf(ProbeResult.NotAdb)))
        val done = sweepDiscovery(lan).run(plan("192.168.1.0/30")).toList().filterIsInstance<DiscoveryEvent.Done>().single()
        assertEquals(1, done.summary.liveNonAdb)
    }
}
