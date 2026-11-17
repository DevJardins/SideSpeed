package io.github.devjardins.sidespeed.companion.net

/** One device on a tailnet, as `tailscale status --json` reports it. IPv4 addresses only. */
data class OverlayPeer(
    /** Tailscale's stable node ID: unlike an address, it survives the node being re-addressed. */
    val id: String,
    val hostName: String,
    val os: String?,
    val ips: List<Ipv4>,
    val online: Boolean,
    /** Shared in from another tailnet: not the user's own device. */
    val shared: Boolean,
)

/** What the local Tailscale reports right now: who we are on which tailnet, and the peers we could reach. */
data class OverlaySnapshot(
    val tailnet: String,
    val selfId: String,
    val selfIps: List<Ipv4>,
    val running: Boolean,
    /** A real network interface exists. False in userspace-networking mode, where our sockets can't reach peers. */
    val tun: Boolean,
    val peers: List<OverlayPeer>,
)

/**
 * A tailnet the user chose to trust, the overlay counterpart of [TrustedNetwork]. Saved TVs reference it through
 * [io.github.devjardins.sidespeed.companion.store.SavedTv.trustedNetworkId], so [id] never collides with a
 * network id.
 */
data class TrustedOverlay(val id: String, val name: String, val tailnet: String, val selfId: String) {
    val displayName: String get() = name.ifBlank { tailnet }
}

object OverlayTrustMatcher {
    /**
     * True when Tailscale is running with a network interface, on the saved tailnet, as the saved node. A blank
     * saved identity never matches. The interface name and own address are deliberately not compared.
     */
    fun matches(snapshot: OverlaySnapshot?, trusted: TrustedOverlay): Boolean {
        if (snapshot == null || trusted.tailnet.isBlank() || trusted.selfId.isBlank()) return false
        return snapshot.running && snapshot.tun &&
            snapshot.tailnet == trusted.tailnet && snapshot.selfId == trusted.selfId
    }

    fun currentTrusted(snapshot: OverlaySnapshot?, all: List<TrustedOverlay>): TrustedOverlay? =
        all.firstOrNull { matches(snapshot, it) }
}
