package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork

/** What a typed scan range means: blank (automatic), a usable block, or unusable. */
sealed interface RangeChoice {
    /** Blank: sweep the automatic range for the current network. */
    data object Default : RangeChoice

    /** A sweepable block on the Trusted Network's /16. */
    data class Custom(val cidr: Cidr) : RangeChoice

    /** Anything else: not blank, not sweepable here. */
    data object Invalid : RangeChoice
}

/**
 * Validates a typed scan range against [trusted]: blank is [RangeChoice.Default]; otherwise the text must
 * parse and be [AddressPolicy.sweepable], lying wholly inside the /16 holding the network's gateway, so a
 * typed range can never leave this network.
 */
fun scanRangeFor(text: String, trusted: TrustedNetwork): RangeChoice {
    if (text.isBlank()) return RangeChoice.Default
    val c = Cidr.parse(text.trim()) ?: return RangeChoice.Invalid
    val gateway = Ipv4.parse(trusted.fingerprint.gatewayIp) ?: return RangeChoice.Invalid
    val g16 = Cidr.parse("$gateway/16") ?: return RangeChoice.Invalid
    return if (AddressPolicy.sweepable(c) && c.network in g16 && c.broadcast in g16) RangeChoice.Custom(c)
    else RangeChoice.Invalid
}
