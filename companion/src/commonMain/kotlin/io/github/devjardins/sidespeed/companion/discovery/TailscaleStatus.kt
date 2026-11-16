package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.store.MiniJson

/** Reads the peers out of `tailscale status --json`; pure, so it is tested without Tailscale installed. */
object TailscaleStatus {
    /**
     * IPv4 addresses of the peers that are online, or whose state is unreported, in the order Tailscale lists
     * them. Never this machine itself, never IPv6, and nothing for output that isn't a status object.
     */
    fun peerAddresses(json: String): List<Ipv4> {
        val root = try {
            MiniJson.parse(json)
        } catch (_: MiniJson.Malformed) {
            return emptyList()
        } as? Map<*, *> ?: return emptyList()
        val peers = root["Peer"] as? Map<*, *> ?: return emptyList()
        return peers.values.asSequence()
            .mapNotNull { it as? Map<*, *> }
            .filter { it["Online"] != false }
            .flatMap { (it["TailscaleIPs"] as? List<*>).orEmpty().asSequence() }
            .mapNotNull { (it as? String)?.let(Ipv4::parse) }
            .toList()
    }
}

/**
 * Lists a Tailscale tailnet's peers as probe targets, so a tailnet is never swept. [statusJson] runs the
 * platform's `tailscale status --json` and returns its output, or null when Tailscale isn't there.
 */
class TailscalePeersSource(private val statusJson: suspend () -> String?) : HostSource {
    override suspend fun hosts(plan: DiscoveryPlan): List<Ipv4> =
        statusJson()?.let(TailscaleStatus::peerAddresses).orEmpty()
}
