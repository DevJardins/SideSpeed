package io.github.devjardins.sidespeed.companion.desktop

/** Pure parsers for the OS tools that say which interface traffic to an address would leave through. */
object EgressParsers {
    /** `route -n get <ip>` on macOS: the `interface: utunN` line. */
    fun macInterface(output: String): String? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("interface:") }
        ?.substringAfter(':')?.trim()?.takeIf(::plausibleName)

    /** `ip route get <ip>` on Linux: the word after `dev`. */
    fun linuxInterface(output: String): String? = output.lineSequence().firstOrNull()
        ?.trim()?.split(Regex("\\s+"))
        ?.let { words -> words.indexOf("dev").takeIf { it >= 0 }?.let { words.getOrNull(it + 1) } }
        ?.takeIf(::plausibleName)

    /** `(Find-NetRoute -RemoteIPAddress <ip>).InterfaceAlias` on Windows: the alias, which may contain spaces. */
    fun windowsInterface(output: String): String? = output.lineSequence()
        .map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeIf { plausibleName(it) && !it.contains(" : ") }

    /** [address] only when it is a dotted IPv4 literal, so it is safe to pass as a command argument. */
    fun safeIpv4(address: String): String? {
        val p = address.split('.')
        val ok = p.size == 4 && p.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
        return address.takeIf { ok }
    }

    private fun plausibleName(s: String): Boolean =
        s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() || it in " _.-:@" } && !s.startsWith("-") && !s.contains("RTNETLINK")
}
