package io.github.devjardins.sidespeed.helper

/**
 * Finds which uid owns a loopback TCP socket, from the text of /proc/net/tcp and /proc/net/tcp6.
 *
 * Row format (whitespace separated): `sl local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode ...`.
 * Addresses are hex in the kernel's byte order (little-endian on ARM/x86); ports are big-endian hex.
 */
object ProcNetTcp {
    private const val LOOPBACK_V4 = "0100007F" // 127.0.0.1
    private const val LOOPBACK_V4_MAPPED = "0000000000000000FFFF00000100007F" // ::ffff:127.0.0.1

    // Socket states that a live client socket can be in. Listeners (0A) and TIME_WAIT (06, uid 0) are ignored.
    private val CLIENT_STATES = setOf("01", "02", "03", "04", "05") // ESTABLISHED, SYN_SENT, SYN_RECV, FIN_WAIT1/2

    /**
     * Returns the uid owning the socket whose local endpoint is 127.0.0.1:[localPort] (or ::ffff:127.0.0.1),
     * from /proc/net/tcp + tcp6 text. If [remotePort] is given, the row's remote endpoint must also be
     * 127.0.0.1:[remotePort]. Returns null if no row matches, or if matching rows disagree on the uid.
     * Malformed lines are skipped.
     */
    fun ownerUid(tcp: String, tcp6: String, localPort: Int, remotePort: Int? = null): Int? {
        val uids = HashSet<Int>()
        for (line in tcp.lineSequence() + tcp6.lineSequence()) {
            parseRow(line, localPort, remotePort)?.let { uids += it }
        }
        return uids.singleOrNull()
    }

    private fun parseRow(line: String, localPort: Int, remotePort: Int?): Int? {
        val f = line.trim().split(WHITESPACE)
        if (f.size < 8 || !f[0].endsWith(":")) return null
        val (localAddr, localP) = endpoint(f[1]) ?: return null
        val (remoteAddr, remoteP) = endpoint(f[2]) ?: return null
        if (localP != localPort || !isLoopback(localAddr)) return null
        if (remotePort != null && (remoteP != remotePort || !isLoopback(remoteAddr))) return null
        if (f[3].uppercase() !in CLIENT_STATES) return null
        return f[7].toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun endpoint(s: String): Pair<String, Int>? {
        val parts = s.split(':')
        if (parts.size != 2 || parts[1].length != 4) return null
        val port = parts[1].toIntOrNull(16) ?: return null
        return parts[0].uppercase() to port
    }

    private fun isLoopback(addr: String) = addr == LOOPBACK_V4 || addr == LOOPBACK_V4_MAPPED

    private val WHITESPACE = Regex("\\s+")
}
