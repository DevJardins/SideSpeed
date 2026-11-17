package io.github.devjardins.sidespeed.net

/** One network Android knows about, reduced to what picking the LAN address needs. */
data class LanCandidate(val isVpn: Boolean, val isWifiOrEthernet: Boolean, val ipv4: String?)

/**
 * The IPv4 address to show as this TV's own. The active network wins unless it is a VPN: with
 * Tailscale (or any VPN) up, Android reports the VPN as active, and its 100.x address is not the
 * Wi-Fi address, so the Wi-Fi or Ethernet network underneath is used instead. Mobile data is
 * never picked. Null when no candidate has an IPv4 address.
 */
fun pickLanIpv4(active: LanCandidate?, all: List<LanCandidate>): String? {
    if (active != null && !active.isVpn) return active.ipv4
    return all.firstOrNull { !it.isVpn && it.isWifiOrEthernet && it.ipv4 != null }?.ipv4
}
