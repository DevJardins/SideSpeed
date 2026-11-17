package io.github.devjardins.sidespeed.companion.net

/**
 * What identifies a Trusted Network without any location permission: gateway IP, subnet, DNS servers and,
 * on desktop where readable without prompts, the gateway MAC.
 *
 * [subnet] is "network-address/prefix" (see [Subnets.normalize]).
 */
data class NetworkFingerprint(
    val gatewayIp: String,
    val subnet: String,
    val dnsServers: Set<String>,
    val gatewayMac: String? = null,
    /**
     * The device's own address on this network right now, when the source knows it. Never part of trust
     * matching (DHCP reassigns it): discovery uses it only to skip itself and to sweep its own /24 first.
     */
    val ownIp: String? = null,
)

/** A network the user chose to trust. */
data class TrustedNetwork(val id: String, val name: String, val fingerprint: NetworkFingerprint) {
    /** The user's label, or the identifier when no label is set. */
    val displayName: String get() = name.ifBlank { id }
}
