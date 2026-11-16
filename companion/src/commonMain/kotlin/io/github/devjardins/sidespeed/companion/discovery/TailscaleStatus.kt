package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.store.MiniJson

/** Reads the peers out of `tailscale status --json`; pure, so it is tested without Tailscale installed. */
object TailscaleStatus {
    /**
     * IPv4 addresses of the Android peers that are online (or whose state or OS is unreported), at most
     * [MAX_PEERS], in the order Tailscale lists them. Never this machine itself, never IPv6, never a device
     * shared in from another tailnet, and nothing for output that isn't a status object.
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
            // Devices shared in from someone else's tailnet are not the user's to probe.
            .filter { it["ShareeNode"] != true }
            // A TV runs the Android build; an unreported OS is kept rather than guessed at.
            .filter { (it["OS"] as? String).let { os -> os.isNullOrEmpty() || os.equals("android", ignoreCase = true) } }
            .flatMap { (it["TailscaleIPs"] as? List<*>).orEmpty().asSequence() }
            .mapNotNull { (it as? String)?.let(Ipv4::parse) }
            .take(MAX_PEERS)
            .toList()
    }

    /** A tailnet can hold thousands of devices; a TV search never needs more than this many. */
    const val MAX_PEERS = 256
}

/**
 * Lists a Tailscale tailnet's peers as probe targets, so a tailnet is never swept. [statusJson] runs the
 * platform's `tailscale status --json` and returns its output, or null when Tailscale isn't there.
 */
class TailscalePeersSource(private val statusJson: suspend () -> String?) : HostSource {
    override suspend fun hosts(plan: DiscoveryPlan): List<Ipv4> =
        statusJson()?.let(TailscaleStatus::peerAddresses).orEmpty()
}
