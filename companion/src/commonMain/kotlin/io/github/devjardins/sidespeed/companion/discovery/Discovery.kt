package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What to sweep for TVs. Only a Trusted Network's own subnet is ever swept (see [run]). */
data class DiscoveryPlan(
    val subnet: Cidr,
    val own: Ipv4? = null,
    val gateway: Ipv4? = null,
    val port: Int = 5555,
    val firstPassTimeoutMs: Int = 400,
    val secondPassTimeoutMs: Int = 1500,
    val limit: Int = SweepOrder.DEFAULT_LIMIT,
)

/**
 * One way of listing hosts to probe; the sweep source is the only one so far. Return the full ordered
 * list: [Discovery] applies [DiscoveryPlan.limit] itself, so it can report when the cap cuts the sweep short.
 */
fun interface HostSource {
    fun hosts(plan: DiscoveryPlan): List<Ipv4>
}

/** Hosts in sweep order: own /24, gateway /24, then outward (see [SweepOrder]). */
object SweepSource : HostSource {
    override fun hosts(plan: DiscoveryPlan): List<Ipv4> =
        SweepOrder.of(plan.subnet, plan.own, plan.gateway, limit = Int.MAX_VALUE)
}

/** What a run reports as it goes; the flow completing is the end of the run. */
sealed interface DiscoveryEvent {
    /** Another probe finished: [scanned] of [total] probes so far. */
    data class Progress(val scanned: Int, val total: Int) : DiscoveryEvent

    /** An ADB-speaking host: a TV candidate for the Add screen. */
    data class Found(val address: Ipv4, val result: ProbeResult) : DiscoveryEvent

    /** The sweep ran to the end: how much of it answered. Never emitted when cancelled. */
    data class Done(val summary: DiscoverySummary) : DiscoveryEvent
}

/**
 * Sweeps a Trusted Network's own subnet for ADB. Two passes: every host is probed once with the short
 * timeout, then hosts that stayed silent are probed again with the long one. Each address is probed at most
 * once per pass no matter how many sources list it. Cancelling the collection stops the sweep.
 *
 * Never probes anything but port 5555 on RFC 1918 addresses inside [DiscoveryPlan.subnet]: never the own
 * address, the network address or the broadcast address. A plan outside those bounds runs to an empty flow.
 *
 * @param dispatcher where the probes run: production passes an IO limiter (see [PARALLELISM]); null runs in
 * the collector's context, which is what virtual-time tests use.
 */
class Discovery(
    private val sources: List<HostSource>,
    private val probe: AdbProbe,
    private val dispatcher: CoroutineDispatcher? = null,
) {
    fun run(plan: DiscoveryPlan): Flow<DiscoveryEvent> = flow {
        if (plan.port != 5555 || !AddressPolicy.sweepable(plan.subnet)) return@flow
        val candidates = sources.flatMap { it.hosts(plan) }
            .distinct()
            .filter { it != plan.own && it != plan.subnet.network && it != plan.subnet.broadcast }
            .filter { AddressPolicy.isPrivate(it) }
        val targets = candidates.take(plan.limit)
        if (targets.isEmpty()) return@flow
        val truncated = candidates.size > targets.size
        val inDispatcher = dispatcher
        if (inDispatcher != null) withContext(inDispatcher) { sweep(plan, targets, truncated) } else sweep(plan, targets, truncated)
    }

    private suspend fun FlowCollector<DiscoveryEvent>.sweep(plan: DiscoveryPlan, targets: List<Ipv4>, truncated: Boolean) {
        var scanned = 0
        var total = targets.size
        val results = pass(plan, targets, plan.firstPassTimeoutMs) { host, result ->
            scanned++
            emit(DiscoveryEvent.Progress(scanned, total))
            if (result.isAdb) emit(DiscoveryEvent.Found(host, result))
        }.toMutableMap()
        val silent = targets.filter { results[it]?.hostIsLive == false }
        if (silent.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            total += silent.size
            pass(plan, silent, plan.secondPassTimeoutMs) { host, result ->
                results[host] = result
                scanned++
                emit(DiscoveryEvent.Progress(scanned, total))
                if (result.isAdb) emit(DiscoveryEvent.Found(host, result))
            }
        }
        emit(DiscoveryEvent.Done(DiscoverySummary(liveNonAdb = results.count { (_, r) -> r.hostIsLive && !r.isAdb }, truncated = truncated)))
    }

    private suspend fun FlowCollector<DiscoveryEvent>.pass(
        plan: DiscoveryPlan,
        hosts: List<Ipv4>,
        timeoutMs: Int,
        onEach: suspend (Ipv4, ProbeResult) -> Unit,
    ): Map<Ipv4, ProbeResult> = coroutineScope {
        val channel = Channel<Pair<Ipv4, ProbeResult>>(Channel.UNLIMITED)
        val gate = Semaphore(PARALLELISM)
        for (host in hosts) {
            launch {
                gate.withPermit { channel.send(host to probe.probe(host, plan.port, timeoutMs)) }
            }
        }
        val out = LinkedHashMap<Ipv4, ProbeResult>()
        repeat(hosts.size) {
            val (host, result) = channel.receive()
            out[host] = result
            onEach(host, result)
        }
        out
    }

    companion object {
        /** Probes in flight at once; production runs them on an IO dispatcher limited to this. */
        const val PARALLELISM = 128
    }
}
