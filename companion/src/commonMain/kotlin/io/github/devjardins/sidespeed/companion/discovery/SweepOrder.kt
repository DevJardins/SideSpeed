package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4

/** The order in which a sweep visits a subnet's hosts. */
object SweepOrder {
    const val DEFAULT_LIMIT = 4096

    /**
     * Up to [limit] hosts of [subnet]: first the /24 holding [own], then the /24 holding [gateway], then the
     * rest outward from [own] (the gateway, or the network address, when [own] is unknown). Never [own], the
     * network address or the broadcast address; hosts in [skip] (already probed) are left out, so calling
     * again with the earlier result as [skip] resumes without repeats.
     */
    fun of(subnet: Cidr, own: Ipv4?, gateway: Ipv4?, limit: Int = DEFAULT_LIMIT, skip: Set<Ipv4> = emptySet()): List<Ipv4> {
        val anchor = own ?: gateway ?: subnet.network
        fun slash24(ip: Ipv4?) = ip?.takeIf { it in subnet }?.let { it.value shr 8 }
        val ownBlock = slash24(own) ?: slash24(gateway)
        val gatewayBlock = slash24(gateway)
        val candidates = subnet.hosts().filter { it != own && it !in skip }
        fun rank(ip: Ipv4): Int = when ((ip.value shr 8)) {
            ownBlock -> 0
            gatewayBlock -> 1
            else -> 2
        }
        return candidates.toList()
            .sortedWith(
                compareBy<Ipv4> { rank(it) }
                    .thenBy { if (rank(it) == 2) kotlin.math.abs(it.value - anchor.value) else it.value }
                    .thenBy { it.value },
            )
            .take(limit)
    }
}
