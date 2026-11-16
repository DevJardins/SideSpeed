package io.github.devjardins.sidespeed.status

/** Which of this TV's own addresses are worth showing, so the user can type one into another device. */
object TvAddress {
    /** Strict dotted quad (four decimal octets, 0..255) as a long, or null. */
    private fun parse(s: String): Long? {
        val octets = s.split('.')
        if (octets.size != 4 || octets.any { o -> o.isEmpty() || o.length > 3 || !o.all { it in '0'..'9' } }) return null
        val values = octets.map { it.toInt() }
        if (values.any { it > 255 }) return null
        return (values[0].toLong() shl 24) or (values[1].toLong() shl 16) or (values[2].toLong() shl 8) or values[3].toLong()
    }

    private fun inBlock(value: Long, base: Long, prefix: Int): Boolean {
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return value and mask == base
    }

    private fun quad(a: Int, b: Int, c: Int, d: Int) = (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()

    /** RFC 1918: 10/8, 172.16/12, 192.168/16. */
    private fun isPrivate(value: Long): Boolean =
        inBlock(value, quad(10, 0, 0, 0), 8) ||
            inBlock(value, quad(172, 16, 0, 0), 12) ||
            inBlock(value, quad(192, 168, 0, 0), 16)

    /** Tailscale's reserved service blocks, not devices. */
    private fun isReserved(value: Long): Boolean =
        inBlock(value, quad(100, 100, 0, 0), 24) ||
            inBlock(value, quad(100, 100, 100, 0), 24) ||
            inBlock(value, quad(100, 115, 92, 0), 23)

    /** 100.64.0.0/10 minus the reserved service blocks. */
    private fun isOverlay(value: Long): Boolean =
        inBlock(value, quad(100, 64, 0, 0), 10) && !isReserved(value)

    /**
     * Private LAN addresses first, then Tailscale-range ones, each once, at most three. Everything else
     * (loopback, link-local, public, IPv6, garbage) is dropped.
     */
    fun pick(ipv4s: List<String>): List<String> {
        val parsed = ipv4s.mapNotNull { s -> parse(s)?.let { s to it } }.distinctBy { it.first }
        return (parsed.filter { isPrivate(it.second) } + parsed.filter { isOverlay(it.second) })
            .map { it.first }
            .take(3)
    }

    /** "This TV: 192.168.5.140 · 100.123.103.8", or null when there is nothing to show. */
    fun label(addresses: List<String>): String? =
        addresses.takeIf { it.isNotEmpty() }?.let { "This TV: " + it.joinToString(" · ") }
}
