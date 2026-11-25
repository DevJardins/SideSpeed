package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.diag_found
import io.github.devjardins.sidespeed.companion.resources.diag_no_adb_on_live_hosts
import io.github.devjardins.sidespeed.companion.resources.diag_nothing_answered
import io.github.devjardins.sidespeed.companion.resources.diag_os_blocked
import io.github.devjardins.sidespeed.companion.resources.diag_partial
import io.github.devjardins.sidespeed.companion.resources.diag_tls_only
import io.github.devjardins.sidespeed.companion.resources.diag_vpn_active
import io.github.devjardins.sidespeed.companion.ui.text.UiText

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

    /** A device answered with wireless debugging and nothing else was found. */
    TLS_ONLY,

    /** The sweep stopped early or the probe cap cut it short: only part of the network was covered. */
    PARTIAL,
    ;
}

/** How much of a sweep answered; ADB-speaking hosts are reported as [DiscoveryEvent.Found]. */
data class DiscoverySummary(
    /** Every host this run probed, so a resumed run can skip them. */
    val scanned: Set<Ipv4> = emptySet(),
    /** Hosts that answered but don't speak ADB. A TLS answer is reported with the TVs that can be paired. */
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
    tlsOnly: Boolean = false,
): Diagnosis {
    if (found) return Diagnosis.FOUND
    if (tlsOnly) return Diagnosis.TLS_ONLY
    if (vpnActive) return Diagnosis.VPN_ACTIVE
    if (osBlocked) return Diagnosis.OS_BLOCKED
    if (!complete || summary.truncated) return Diagnosis.PARTIAL
    if (summary.liveNonAdb > 0) return Diagnosis.NO_ADB_ON_LIVE_HOSTS
    return Diagnosis.NOTHING_ANSWERED
}

/** What the Add screen shows for [diagnosis]; every copy ends with the manual-address fallback. */
fun helpFor(diagnosis: Diagnosis): UiText = when (diagnosis) {
    Diagnosis.FOUND -> UiText.Res(Res.string.diag_found)
    Diagnosis.NO_ADB_ON_LIVE_HOSTS -> UiText.Res(Res.string.diag_no_adb_on_live_hosts)
    Diagnosis.TLS_ONLY -> UiText.Res(Res.string.diag_tls_only)
    Diagnosis.NOTHING_ANSWERED -> UiText.Res(Res.string.diag_nothing_answered)
    Diagnosis.OS_BLOCKED -> UiText.Res(Res.string.diag_os_blocked)
    Diagnosis.VPN_ACTIVE -> UiText.Res(Res.string.diag_vpn_active)
    Diagnosis.PARTIAL -> UiText.Res(Res.string.diag_partial)
}
