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

    /** True if [host] is a plain dotted IPv4 address inside [subnet] (any prefix). */
    fun containsHost(subnet: String, host: String): Boolean {
        val net = normalize(subnet) ?: return false
        val prefix = net.substringAfter('/')
        if (normalize("$host/32") == null) return false
        return normalize("$host/$prefix") == net
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
     * Same gateway IP and subnet, and DNS sets intersect (or both empty). Then, fail closed on what the
     * trusted record has: a recorded gatewayMac needs the current one to match it (normalised); a recorded
     * wifiSsid needs the current one to be exactly equal; a recorded wifiBssid needs the current one to match
     * it (normalised) unless the record also has an SSID, whose match already covers a mesh network's roaming
     * access points. Anything the record lacks is ignored. A blank gateway IP, malformed subnets or MACs
     * never match.
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
        val tm = trusted.gatewayMac
        if (tm != null) {
            val cm = current.gatewayMac ?: return false
            val a = Subnets.normalizeMac(cm) ?: return false
            val b = Subnets.normalizeMac(tm) ?: return false
            if (a != b) return false
        }
        val tssid = trusted.wifiSsid
        if (tssid != null && current.wifiSsid != tssid) return false
        val tbssid = trusted.wifiBssid
        if (tbssid != null && tssid == null) {
            val cbssid = current.wifiBssid ?: return false
            val a = Subnets.normalizeMac(cbssid) ?: return false
            val b = Subnets.normalizeMac(tbssid) ?: return false
            if (a != b) return false
        }
        return true
    }

    /** The first Trusted Network matching [current]; null when there is no usable network or none matches. */
    fun currentTrusted(current: NetworkFingerprint?, all: List<TrustedNetwork>): TrustedNetwork? {
        if (current == null) return null
        return all.firstOrNull { matches(current, it.fingerprint) }
    }

    /**
     * The record with the current fingerprint's gatewayMac, wifiSsid and wifiBssid added wherever the record
     * lacks them — or null when there is nothing to add. Existing fields are never changed. Callers save the
     * result only after [matches] passed, so the added fields were all seen on the trusted network.
     */
    fun upgraded(matched: TrustedNetwork, current: NetworkFingerprint): TrustedNetwork? {
        val fp = matched.fingerprint
        val next = fp.copy(
            gatewayMac = fp.gatewayMac ?: current.gatewayMac,
            wifiSsid = fp.wifiSsid ?: current.wifiSsid,
            wifiBssid = fp.wifiBssid ?: current.wifiBssid,
        )
        return if (next == fp) null else matched.copy(fingerprint = next)
    }
}
