package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.discovery.HostSource
import io.github.devjardins.sidespeed.companion.discovery.MAX_HINTS
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryPlan
import io.github.devjardins.sidespeed.companion.discovery.lanOnly
import io.github.devjardins.sidespeed.companion.net.Ipv4

/** Hosts the desktop neighbour table already knows, kept to LAN hints inside the plan. */
class ArpSource(
    private val read: suspend () -> String,
    private val parse: (String) -> List<Ipv4>,
) : HostSource {
    override suspend fun hosts(plan: DiscoveryPlan): List<Ipv4> =
        plan.lanOnly(parse(read())).take(MAX_HINTS)
}
