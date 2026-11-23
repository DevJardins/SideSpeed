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

    /** The Wi-Fi device from `networksetup -listallhardwareports` (usually "en0"); null when none. */
    fun macWifiDevice(listAllHardwarePorts: String): String? {
        var wifi = false
        for (line in listAllHardwarePorts.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("Hardware Port:")) wifi = t.substringAfter(':').trim() == "Wi-Fi"
            else if (wifi && t.startsWith("Device:")) return t.substringAfter(':').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** The SSID from `networksetup -getairportnetwork <device>`; "not associated" and blanks give null. */
    fun macSsid(getairportnetwork: String): String? {
        val line = getairportnetwork.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Current Wi-Fi Network:") } ?: return null
        return line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The (SSID, BSSID) of the first connected interface from `netsh wlan show interfaces`, or null when
     * none is connected. The BSSID is normalised like a MAC; a missing or malformed one gives null BSSID.
     */
    fun windowsWifi(netsh: String): Pair<String, String?>? {
        var connected = false
        var ssid: String? = null
        for (line in netsh.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("State", ignoreCase = true)) connected = t.substringAfter(':').trim().equals("connected", ignoreCase = true)
            else if (t.startsWith("SSID", ignoreCase = true) && !t.startsWith("BSSID", ignoreCase = true)) {
                val v = t.substringAfter(':').trim()
                if (ssid == null) ssid = v.takeIf { it.isNotEmpty() }
            } else if (t.startsWith("BSSID", ignoreCase = true)) {
                val b = Subnets.normalizeMac(t.substringAfter(':').trim())
                if (ssid != null && connected) return ssid to b
            }
        }
        return null
    }

    /**
     * The (SSID, escaped BSSID) of the active row from `nmcli -t -f ACTIVE,SSID,BSSID dev wifi`, or null when
     * no row is active. nmcli escapes `:` as `\:` (and `\` as `\\`), so rows split on unescaped colons only.
     */
    fun linuxWifi(nmcli: String): Pair<String, String?>? {
        for (line in nmcli.lineSequence()) {
            if (line.isBlank()) continue
            val fields = splitEscaped(line)
            if (fields.size < 2 || fields[0] != "yes") continue
            val ssid = fields[1].takeIf { it.isNotEmpty() } ?: continue
            val bssid = fields.getOrNull(2)?.let(Subnets::normalizeMac)
            return ssid to bssid
        }
        return null
    }

    /** Splits on `:` except when backslash-escaped, unescaping `\X` to `X`. */
    private fun splitEscaped(row: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < row.length) {
            val c = row[i]
            if (c == '\\' && i + 1 < row.length) {
                cur.append(row[i + 1])
                i += 2
            } else if (c == ':') {
                out += cur.toString()
                cur.clear()
                i++
            } else {
                cur.append(c)
                i++
            }
        }
        out += cur.toString()
        return out
    }

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
