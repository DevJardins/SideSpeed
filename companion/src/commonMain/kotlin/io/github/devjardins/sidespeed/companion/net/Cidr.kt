package io.github.devjardins.sidespeed.companion.net

/** An IPv4 address as an unsigned 32-bit value. */
@JvmInline
value class Ipv4(val value: Long) {
    init {
        require(value in 0..0xFFFFFFFFL) { "not an IPv4 value" }
    }

    override fun toString(): String = "${(value shr 24) and 255}.${(value shr 16) and 255}.${(value shr 8) and 255}.${value and 255}"

    companion object {
        /** Strict dotted quad ("1.2.3.4"): four decimal octets of 1 to 3 digits, no spaces. Null otherwise. */
        fun parse(text: String): Ipv4? {
            val octets = text.split('.')
            if (octets.size != 4) return null
            var v = 0L
            for (o in octets) {
                if (o.isEmpty() || o.length > 3 || !o.all { it in '0'..'9' }) return null
                val n = o.toInt()
                if (n > 255) return null
                v = (v shl 8) or n.toLong()
            }
            return Ipv4(v)
        }
    }
}

/** An IPv4 block, always held as its network address and prefix length (0..32). */
class Cidr private constructor(val network: Ipv4, val prefix: Int) {
    /** Number of addresses in the block, network and broadcast included. */
    val size: Long get() = 1L shl (32 - prefix)
    val broadcast: Ipv4 get() = Ipv4(network.value + size - 1)

    /** Usable hosts: all but network and broadcast, except /31 (both) and /32 (the one address). */
    val hostCount: Long get() = if (prefix >= 31) size else size - 2

    operator fun contains(ip: Ipv4): Boolean = ip.value - network.value in 0 until size

    /** Usable hosts in ascending order. */
    fun hosts(): Sequence<Ipv4> {
        val first = if (prefix >= 31) network.value else network.value + 1
        val last = if (prefix >= 31) broadcast.value else broadcast.value - 1
        return (first..last).asSequence().map { Ipv4(it) }
    }

    override fun equals(other: Any?) = other is Cidr && other.network == network && other.prefix == prefix
    override fun hashCode() = network.value.hashCode() * 31 + prefix
    override fun toString() = "$network/$prefix"

    companion object {
        /** "a.b.c.d/p"; host bits are cleared. Null for anything malformed. */
        fun parse(text: String): Cidr? {
            val parts = text.split('/')
            if (parts.size != 2) return null
            val ip = Ipv4.parse(parts[0]) ?: return null
            val p = parts[1]
            if (p.isEmpty() || p.length > 2 || !p.all { it in '0'..'9' }) return null
            val prefix = p.toInt()
            if (prefix > 32) return null
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            return Cidr(Ipv4(ip.value and mask), prefix)
        }
    }
}

/** Which addresses the companion may ever contact on its own initiative. */
object AddressPolicy {
    private val blocks = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16").map { Cidr.parse(it)!! }

    /** RFC 1918 only: 10/8, 172.16/12, 192.168/16. */
    fun isPrivate(ip: Ipv4): Boolean = blocks.any { ip in it }

    /** A block that may be swept: wholly inside RFC 1918 space, with a prefix from /16 to /30. */
    fun sweepable(c: Cidr): Boolean = c.prefix in 16..30 && isPrivate(c.network) && isPrivate(c.broadcast)
}
