package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4

/** What a Find TVs sweep concluded. */
enum class Diagnosis {
    /** At least one ADB-speaking host was found. */
    FOUND,

    /** Hosts answered, but none speaks ADB: debugging is off on the TVs. */
    NO_ADB_ON_LIVE_HOSTS,

    /** Nothing on the subnet answered at all. */
    NOTHING_ANSWERED,

    /** The system blocked the sweep (local-network permission or firewall). */
    OS_BLOCKED,

    /** A VPN is active, so the sweep can't see the home network. */
    VPN_ACTIVE,

    /** The sweep stopped early or the probe cap cut it short: only part of the network was covered. */
    PARTIAL,
    ;
}

/** How much of a sweep answered; ADB-speaking hosts are reported as [DiscoveryEvent.Found]. */
data class DiscoverySummary(
    /** Every host this run probed, so a resumed run can skip them. */
    val scanned: Set<Ipv4> = emptySet(),
    /** Hosts that answered but don't speak ADB. */
    val liveNonAdb: Int = 0,
    /** The probe cap cut the sweep short: only part of the subnet was covered. */
    val truncated: Boolean = false,
)

/**
 * Classifies a sweep. [found] wins over everything: a stopped sweep that already found a TV is still a
 * find. [osBlocked] and [vpnActive] come from the platform (not the probes); the companion passes false
 * until platform sources report them.
 */
fun diagnose(
    summary: DiscoverySummary,
    found: Boolean,
    complete: Boolean,
    osBlocked: Boolean = false,
    vpnActive: Boolean = false,
): Diagnosis {
    if (found) return Diagnosis.FOUND
    if (vpnActive) return Diagnosis.VPN_ACTIVE
    if (osBlocked) return Diagnosis.OS_BLOCKED
    if (!complete || summary.truncated) return Diagnosis.PARTIAL
    if (summary.liveNonAdb > 0) return Diagnosis.NO_ADB_ON_LIVE_HOSTS
    return Diagnosis.NOTHING_ANSWERED
}

private const val MANUAL_ADDRESS_COPY = "or type the address shown in SideSpeed on the TV"

/** What the Add screen shows for [diagnosis]; every copy ends with the manual-address fallback. */
fun helpFor(diagnosis: Diagnosis): String = when (diagnosis) {
    Diagnosis.FOUND ->
        "Tap a TV above to add it, $MANUAL_ADDRESS_COPY"
    Diagnosis.NO_ADB_ON_LIVE_HOSTS ->
        "Devices answered on this network, but none has ADB open. Turn on ADB or Network debugging on the TV, " +
            "accept the prompt, then scan again, $MANUAL_ADDRESS_COPY"
    Diagnosis.NOTHING_ANSWERED ->
        "Nothing answered on this network. Check the TV is on and on the same Wi-Fi, then scan again, " +
            MANUAL_ADDRESS_COPY
    Diagnosis.OS_BLOCKED ->
        "The system blocked the scan: allow local-network access for the companion, then scan again, " +
            MANUAL_ADDRESS_COPY
    Diagnosis.VPN_ACTIVE ->
        "A VPN is active, so the scan can't see your home network. Disconnect the VPN, then scan again, " +
            MANUAL_ADDRESS_COPY
    Diagnosis.PARTIAL ->
        "The scan covered only part of the network. Keep scanning to cover the rest, $MANUAL_ADDRESS_COPY"
}
