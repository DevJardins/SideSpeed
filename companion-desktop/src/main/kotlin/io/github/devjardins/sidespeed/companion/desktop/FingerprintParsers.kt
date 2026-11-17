package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.net.Subnets

/** Pure parsers for the output of each OS's network commands. Anything unrecognised gives null or empty. */
object FingerprintParsers {
    private val ipv4 = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")

    private fun validIpv4(s: String?): String? =
        s?.takeIf { Subnets.normalize("$it/32") != null && it != "0.0.0.0" }

    /** macOS `route -n get default`: the "gateway:" line. */
    fun macGateway(routeGet: String): String? =
        routeGet.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("gateway:") }
            ?.substringAfter(':')?.trim().let(::validIpv4)

    /** Linux `ip route show default`: the first "default via <ip>". */
    fun linuxGateway(ipRoute: String): String? =
        ipRoute.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstNotNullOfOrNull { t -> if (t.size > 2 && t[0] == "default" && t[1] == "via") validIpv4(t[2]) else null }

    /** Windows `route print -4`: the 0.0.0.0/0.0.0.0 row with the lowest metric and a real gateway ("On-link" is skipped). */
    fun windowsGateway(routePrint: String): String? =
        routePrint.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 5 && it[0] == "0.0.0.0" && it[1] == "0.0.0.0" }
            .mapNotNull { t -> validIpv4(t[2])?.let { gw -> gw to (t[4].toIntOrNull() ?: Int.MAX_VALUE) } }
            .minByOrNull { it.second }?.first

    /** macOS `scutil --dns`: every IPv4 "nameserver[n] : x", in order, without duplicates. */
    fun macDns(scutil: String): Set<String> =
        scutil.lineSequence().map { it.trim() }.filter { it.startsWith("nameserver[") }
            .mapNotNull { validIpv4(it.substringAfter(':').trim()) }.toCollection(LinkedHashSet())

    /** Linux `/etc/resolv.conf` (or `resolvectl dns` output): IPv4 nameservers. */
    fun resolvConf(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("nameserver") }
            .mapNotNull { validIpv4(it.removePrefix("nameserver").trim()) }.toCollection(LinkedHashSet())

    /** `resolvectl dns` lines like "Link 2 (wlan0): 192.168.1.1 fe80::1"; "Global:" lines count too. */
    fun resolvectl(text: String): Set<String> =
        text.lineSequence().filter { ':' in it }.flatMap { l -> ipv4.findAll(l.substringAfter("):").substringAfter("Global:")).map { it.value } }
            .mapNotNull(::validIpv4).toCollection(LinkedHashSet())

    /** Windows PowerShell `(Get-DnsClientServerAddress -AddressFamily IPv4).ServerAddresses`: one address per line. */
    fun windowsDns(powershell: String): Set<String> =
        powershell.lineSequence().map { it.trim() }.mapNotNull(::validIpv4).toCollection(LinkedHashSet())

    /**
     * The gateway's MAC from `arp -n <gw>` (macOS), `ip neigh show <gw>` (Linux) or `arp -a <gw>` (Windows):
     * the first colon- or dash-separated MAC on a line that mentions [gateway]. "(incomplete)" gives null.
     */
    fun gatewayMac(arpOutput: String, gateway: String): String? {
        val mac = Regex("""\b([0-9a-fA-F]{1,2}([:-])(?:[0-9a-fA-F]{1,2}\2){4}[0-9a-fA-F]{1,2})\b""")
        return arpOutput.lineSequence()
            .filter { l -> Regex("""(?<![\d.])${Regex.escape(gateway)}(?![\d.])""").containsMatchIn(l) }
            .firstNotNullOfOrNull { mac.find(it)?.value?.let(Subnets::normalizeMac) }
    }
}
