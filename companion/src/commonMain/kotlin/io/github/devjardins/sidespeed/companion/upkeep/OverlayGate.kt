package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.OverlayPeer
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

/** True when the trusted overlay that matches now ([currentId]) is new since the last poll ([previousId]). */
fun overlayJoined(previousId: String?, currentId: String?): Boolean = currentId != null && currentId != previousId

/**
 * The network poll's memory: the fingerprint and trusted overlay id from the previous tick. Seeded with
 * the state at startup so an already-trusted tailnet is not reported as a join on the first tick.
 */
class JoinPoll(private var lastFingerprint: NetworkFingerprint?, private var lastOverlayId: String?) {
    /**
     * Whether this tick joined a trusted scope, remembering the new state either way: a changed fingerprint
     * whose network [isTrustedLan], or a newly matching trusted overlay id.
     */
    suspend fun tick(
        fingerprint: NetworkFingerprint?,
        overlayId: String?,
        isTrustedLan: suspend (NetworkFingerprint?) -> Boolean,
    ): Boolean {
        var joined = false
        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint
            if (isTrustedLan(fingerprint)) joined = true
        }
        if (overlayJoined(lastOverlayId, overlayId)) joined = true
        lastOverlayId = overlayId
        return joined
    }
}

/** The overlay gate's answer for one TV. */
internal sealed interface OverlayVerdict {
    /** Connect to [address]. */
    data class Open(val address: String) : OverlayVerdict
    /** The tailnet is fine but this TV's peer is gone, shared in, unbound or has no usable address: a failed check, no connection. */
    data object PeerUnavailable : OverlayVerdict
    /** The tailnet itself does not match or traffic would not leave through it: stop using the overlay. */
    data object Closed : OverlayVerdict
}

/**
 * The gate for a TV on a [TrustedOverlay]. [verdict] says whether to connect, and where; conditions 1 and 4 close
 * the gate, 2 and 3 only fail this TV. It is open only when all of these hold, read fresh:
 *
 * 1. the snapshot still matches the trusted overlay (running, with an interface, same tailnet and node);
 * 2. the TV's bound peer exists and was not shared in from another tailnet;
 * 3. the peer has an overlay-range address; the TV's own address if the peer still has it, otherwise the
 *    peer's first one (the caller updates the TV only once the serial matches there);
 * 4. traffic to that address would leave through an interface that holds this node's own tailnet address, which
 *    catches Tailscale stopping, a hotel or carrier network using 100.64.0.0/10, and another VPN.
 */
internal class OverlayGate(private val access: OverlayAccess) {
    suspend fun verdict(trusted: TrustedOverlay, tv: SavedTv): OverlayVerdict {
        val snapshot = access.source.snapshot() ?: return OverlayVerdict.Closed
        if (!OverlayTrustMatcher.matches(snapshot, trusted)) return OverlayVerdict.Closed
        val peerId = tv.overlayPeerId ?: return OverlayVerdict.PeerUnavailable
        val peer = snapshot.peers.firstOrNull { it.id == peerId && !it.shared } ?: return OverlayVerdict.PeerUnavailable
        val usable = peer.ips.filter(AddressPolicy::isOverlay)
        val target = usable.firstOrNull { it.toString() == tv.address } ?: usable.firstOrNull() ?: return OverlayVerdict.PeerUnavailable
        return if (leavesThroughTailnet(snapshot, target)) OverlayVerdict.Open(target.toString()) else OverlayVerdict.Closed
    }

    /** A trusted tailnet peer that owns [address], when the gate would be open for a TV added there; else null. */
    class Binding(val overlay: TrustedOverlay, val peer: OverlayPeer)

    suspend fun bindingFor(address: Ipv4, trusted: List<TrustedOverlay>): Binding? {
        if (!AddressPolicy.isOverlay(address) || trusted.isEmpty()) return null
        val snapshot = access.source.snapshot() ?: return null
        val overlay = OverlayTrustMatcher.currentTrusted(snapshot, trusted) ?: return null
        val peer = snapshot.peers.firstOrNull { !it.shared && address in it.ips } ?: return null
        return Binding(overlay, peer).takeIf { leavesThroughTailnet(snapshot, address) }
    }

    /** A non-shared peer that owns [address] on the tailnet Tailscale is signed into now, whether or not it is
     * trusted yet. Null when the snapshot is missing or unusable, the route would not leave through the tailnet,
     * or no non-shared peer owns the address. */
    class CurrentTailnetPeer(val snapshot: OverlaySnapshot, val peer: OverlayPeer)

    suspend fun peerOnCurrentTailnet(address: Ipv4): CurrentTailnetPeer? {
        if (!AddressPolicy.isOverlay(address)) return null
        val snapshot = access.source.snapshot() ?: return null
        if (!snapshot.running || !snapshot.tun) return null
        val peer = snapshot.peers.firstOrNull { !it.shared && address in it.ips } ?: return null
        return CurrentTailnetPeer(snapshot, peer).takeIf { leavesThroughTailnet(snapshot, address) }
    }

    private suspend fun leavesThroughTailnet(snapshot: OverlaySnapshot, address: Ipv4): Boolean {
        val route = access.egress.route(address) ?: return false
        return route.addresses.any { it in snapshot.selfIps }
    }
}
