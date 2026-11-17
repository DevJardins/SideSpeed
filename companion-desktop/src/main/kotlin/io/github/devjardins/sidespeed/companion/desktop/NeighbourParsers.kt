package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.net.Ipv4

/** Reads the desktop neighbour table (ARP/NDP) output: only live unicast entries. */
object NeighbourParsers {
    private fun unicastMac(mac: String): Boolean {
        val bytes = mac.replace('-', ':').split(':')
        if (bytes.size != 6 || bytes.any { it.isEmpty() || it.length > 2 || !it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }) return false
        val first = bytes[0].toInt(16)
        // Odd first octet is multicast or broadcast (01:00:5e, ff:ff:ff:ff:ff:ff).
        return first and 1 == 0
    }

    /** `arp -an` lines: `? (192.168.5.140) at 3c:5a:b4:1:2:3 on en0 ifscope [ethernet]`. */
    fun mac(text: String): List<Ipv4> = text.lines().mapNotNull { line ->
        val match = Regex("""\(([^)]+)\) at (\S+)""").find(line) ?: return@mapNotNull null
        val ip = Ipv4.parse(match.groupValues[1]) ?: return@mapNotNull null
        if (!unicastMac(match.groupValues[2])) return@mapNotNull null
        ip
    }

    /** `ip neigh show` lines: `192.168.5.140 dev wlan0 lladdr 3c:5a:b4:01:02:03 REACHABLE`. */
    fun linux(text: String): List<Ipv4> = text.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6 || parts[3] != "lladdr") return@mapNotNull null
        val ip = Ipv4.parse(parts[0]) ?: return@mapNotNull null
        if (!unicastMac(parts[4])) return@mapNotNull null
        ip
    }

    /** `arp -a` lines: `  192.168.5.140         3c-5a-b4-01-02-03     dynamic`. */
    fun windows(text: String): List<Ipv4> = text.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) return@mapNotNull null
        val ip = Ipv4.parse(parts[0]) ?: return@mapNotNull null
        if (!unicastMac(parts[1])) return@mapNotNull null
        ip
    }
}
