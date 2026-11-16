package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.OverlaySnapshot
import io.github.devjardins.sidespeed.companion.net.OverlayTrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.store.SavedTv

/** Reads the local overlay client (Tailscale) afresh each call; null when it isn't there or can't be read. */
fun interface OverlaySource {
    suspend fun snapshot(): OverlaySnapshot?
}

/** The network interface the OS would send to an address through, and the IPv4 addresses that interface holds. */
data class EgressRoute(val interfaceName: String, val addresses: List<Ipv4>)

/** Asks the OS where traffic to [address] would leave, without sending any. Null when it can't tell. */
fun interface EgressCheck {
    suspend fun route(address: Ipv4): EgressRoute?
}

/** What upkeep needs to keep TVs reached over an overlay. Desktop only (decision D1). */
class OverlayAccess(val source: OverlaySource, val egress: EgressCheck)

/**
 * The gate for a TV on a [TrustedOverlay]. [addressToUse] returns the address to connect to, or null when the
 * gate is closed. It is open only when all of these hold, read fresh:
 *
 * 1. the snapshot still matches the trusted overlay (running, with an interface, same tailnet and node);
 * 2. the TV's bound peer exists and was not shared in from another tailnet;
 * 3. the peer has an overlay-range address; the TV's own address if the peer still has it, otherwise the
 *    peer's first one (the caller updates the TV only once the serial matches there);
 * 4. traffic to that address would leave through an interface that holds this node's own tailnet address, which
 *    catches Tailscale stopping, a hotel or carrier network using 100.64.0.0/10, and another VPN.
 */
internal class OverlayGate(private val access: OverlayAccess) {
    suspend fun addressToUse(trusted: TrustedOverlay, tv: SavedTv): String? {
        val snapshot = access.source.snapshot() ?: return null
        if (!OverlayTrustMatcher.matches(snapshot, trusted)) return null
        val peerId = tv.overlayPeerId ?: return null
        val peer = snapshot.peers.firstOrNull { it.id == peerId && !it.shared } ?: return null
        val usable = peer.ips.filter(AddressPolicy::isOverlay)
        val target = usable.firstOrNull { it.toString() == tv.address } ?: usable.firstOrNull() ?: return null
        return target.toString().takeIf { leavesThroughTailnet(snapshot, target) }
    }

    private suspend fun leavesThroughTailnet(snapshot: OverlaySnapshot, address: Ipv4): Boolean {
        val route = access.egress.route(address) ?: return false
        return route.addresses.any { it in snapshot.selfIps }
    }
}
