package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Ipv4

/**
 * Keeps only hint addresses the sweep itself could visit: distinct RFC 1918 hosts inside the plan's subnet,
 * in the order they arrived. A reflector on another subnet and any tailnet peer never get through.
 */
fun DiscoveryPlan.lanOnly(hosts: List<Ipv4>): List<Ipv4> =
    hosts.distinct().filter { it in subnet && AddressPolicy.isPrivate(it) }

/** The mDNS service-enumeration question and what counts as an answer to it. */
object Mdns {
    /** One PTR question for `_services._dns-sd._udp.local`: any responder answers, whatever it advertises. */
    fun servicesQuery(): ByteArray {
        val name = "_services._dns-sd._udp.local".split('.')
        val out = ByteArrayOutputStream2()
        out.writeShort(0x0000) // ID
        out.writeShort(0x0000) // flags: a question
        out.writeShort(0x0001) // QDCOUNT
        out.writeShort(0x0000) // ANCOUNT
        out.writeShort(0x0000) // NSCOUNT
        out.writeShort(0x0000) // ARCOUNT
        for (label in name) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.writeByte(bytes.size)
            out.write(bytes)
        }
        out.writeByte(0) // root
        out.writeShort(0x000C) // QTYPE PTR
        out.writeShort(0x0001) // QCLASS IN
        return out.bytes()
    }

    /** A response (QR bit set) carrying at least one answer. */
    fun isResponse(b: ByteArray): Boolean {
        if (b.size < 12) return false
        if (b[2].toInt() and 0x80 == 0) return false
        return ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF) > 0
    }

    /** Byte writes without java.io (common code). */
    private class ByteArrayOutputStream2 {
        private val buf = mutableListOf<Byte>()
        fun writeByte(v: Int) { buf += v.toByte() }
        fun writeShort(v: Int) { buf += (v shr 8).toByte(); buf += v.toByte() }
        fun write(bytes: ByteArray) { bytes.forEach { buf += it } }
        fun bytes() = buf.toByteArray()
    }
}

/** The SSDP search and what counts as a reply to it. */
object Ssdp {
    /** Standard `M-SEARCH` for everything (`ST: ssdp:all`, `MX: 1`). */
    fun mSearch(): ByteArray =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)

    /** An HTTP 200 reply, not a NOTIFY or anything else. */
    fun isResponse(b: ByteArray): Boolean =
        b.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 200")
}
