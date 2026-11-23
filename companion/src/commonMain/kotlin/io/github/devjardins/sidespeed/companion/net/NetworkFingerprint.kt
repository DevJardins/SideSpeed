package io.github.devjardins.sidespeed.companion.net

/**
 * What identifies a Trusted Network: gateway IP, subnet, DNS servers and, on desktop where readable
 * without prompts, the gateway MAC. The Wi-Fi name ([wifiSsid]) and access point ([wifiBssid]) only
 * when the user allowed it on Android, or readable without prompts on desktop; stored only in the
 * local settings file, never sent.
 *
 * [subnet] is "network-address/prefix" (see [Subnets.normalize]).
 */
data class NetworkFingerprint(
    val gatewayIp: String,
    val subnet: String,
    val dnsServers: Set<String>,
    val gatewayMac: String? = null,
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
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
