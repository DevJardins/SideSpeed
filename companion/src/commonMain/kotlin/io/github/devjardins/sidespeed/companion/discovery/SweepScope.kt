package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.Ipv4

/**
 * The block to actually sweep for a network whose subnet is [subnet]. Discovery only sweeps /16 and smaller,
 * so a bigger network (a /8 or /12 home or office LAN) shrinks to the /16 around the device's own address,
 * or around the gateway when the own address is unknown. A subnet already /16 or smaller is returned as is,
 * and a bigger one with no usable anchor is returned unchanged for [Discovery] to refuse.
 */
fun sweepScope(subnet: Cidr, own: Ipv4?, gateway: Ipv4?): Cidr {
    if (subnet.prefix >= 16) return subnet
    val anchor = own?.takeIf { it in subnet } ?: gateway?.takeIf { it in subnet } ?: return subnet
    return Cidr.parse("$anchor/16") ?: subnet
}
