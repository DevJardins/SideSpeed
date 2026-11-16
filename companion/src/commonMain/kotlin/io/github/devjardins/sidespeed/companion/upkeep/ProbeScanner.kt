package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.discovery.AdbProbe
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryEvent
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryPlan
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.discovery.SweepSource
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * Finds ADB-speaking hosts with the one-packet [AdbProbe], starting around [near]. Only classic ADB
 * answers ([ProbeResult.AdbAuthRequired], [ProbeResult.AdbOpen]) are returned; TLS-only hosts need a
 * pairing this companion cannot do. Never probes outside the parsed subnet or off port 5555.
 */
class ProbeScanner(private val probe: AdbProbe, private val dispatcher: CoroutineDispatcher? = null) : Scanner {
    override fun hosts(subnet: String, port: Int, near: String?): Flow<String> {
        if (port != 5555) return emptyFlow()
        val cidr = Cidr.parse(subnet) ?: return emptyFlow()
        val anchor = near?.let(Ipv4::parse)?.takeIf { it in cidr }
        return Discovery(listOf(SweepSource), probe, dispatcher)
            .run(DiscoveryPlan(cidr, own = null, gateway = anchor))
            .filterIsInstance<DiscoveryEvent.Found>()
            .filter { it.result == ProbeResult.AdbAuthRequired || it.result == ProbeResult.AdbOpen }
            .map { it.address.toString() }
    }

    override suspend fun scan(subnet: String, port: Int, near: String?): List<String> =
        hosts(subnet, port, near).toList()
}
