package io.github.devjardins.sidespeed.companion.net

/** Pure helpers for normalising fingerprint parts. */
object Subnets {
    /**
     * "192.168.5.140/24" becomes "192.168.5.0/24". IPv4 only; returns null for anything malformed
     * (bad octets, missing or out-of-range prefix, IPv6).
     */
    fun normalize(cidr: String): String? {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return null
        val octets = parts[0].split('.')
        if (octets.size != 4) return null
        var addr = 0L
        for (o in octets) {
            if (o.isEmpty() || o.length > 3 || !o.all { it in '0'..'9' }) return null
            val v = o.toInt()
            if (v > 255) return null
            addr = (addr shl 8) or v.toLong()
        }
        val p = parts[1]
        if (p.isEmpty() || p.length > 2 || !p.all { it in '0'..'9' }) return null
        val prefix = p.toInt()
        if (prefix > 32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = addr and mask
        return "${(net shr 24) and 255}.${(net shr 16) and 255}.${(net shr 8) and 255}.${net and 255}/$prefix"
    }

    /** Lower-cases a MAC, uses ':' separators and zero-pads each byte; null if it isn't six hex bytes. */
    fun normalizeMac(mac: String): String? {
        val bytes = mac.trim().lowercase().split(':', '-')
        if (bytes.size != 6) return null
        val out = bytes.map { b ->
            if (b.isEmpty() || b.length > 2 || !b.all { it in '0'..'9' || it in 'a'..'f' }) return null
            b.padStart(2, '0')
        }
        return out.joinToString(":")
    }
}

object TrustMatcher {
    /**
     * Same gateway IP and subnet, and DNS sets intersect (or both empty); if both have gatewayMac they must be
     * equal. A blank gateway IP, malformed subnets or MACs never match.
     */
    fun matches(current: NetworkFingerprint, trusted: NetworkFingerprint): Boolean {
        val cg = current.gatewayIp.trim()
        if (cg.isEmpty() || cg != trusted.gatewayIp.trim()) return false
        val cs = Subnets.normalize(current.subnet) ?: return false
        val ts = Subnets.normalize(trusted.subnet) ?: return false
        if (cs != ts) return false
        val cd = current.dnsServers.map { it.trim().lowercase() }.toSet()
        val td = trusted.dnsServers.map { it.trim().lowercase() }.toSet()
        if (!(cd.isEmpty() && td.isEmpty()) && cd.intersect(td).isEmpty()) return false
        val cm = current.gatewayMac
        val tm = trusted.gatewayMac
        if (cm != null && tm != null) {
            val a = Subnets.normalizeMac(cm) ?: return false
            val b = Subnets.normalizeMac(tm) ?: return false
            if (a != b) return false
        }
        return true
    }

    /** The first Trusted Network matching [current]; null when there is no usable network or none matches. */
    fun currentTrusted(current: NetworkFingerprint?, all: List<TrustedNetwork>): TrustedNetwork? {
        if (current == null) return null
        return all.firstOrNull { matches(current, it.fingerprint) }
    }
}
