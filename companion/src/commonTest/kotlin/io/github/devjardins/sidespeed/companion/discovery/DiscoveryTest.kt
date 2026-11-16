package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
}
