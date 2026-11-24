package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.Subnets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Reads the current network's fingerprint with fixed read-only OS commands (no prompts, no permissions).
 * Gateway and DNS come from the OS's own tools; the address and prefix come from Java's [NetworkInterface],
 * the same on every OS.
 */
class DesktopFingerprintSource(private val os: String = System.getProperty("os.name").orEmpty().lowercase()) : FingerprintSource {
    private val mac = os.startsWith("mac") || os.startsWith("darwin")
    private val windows = os.startsWith("windows")

    override suspend fun current(): NetworkFingerprint? = withContext(Dispatchers.IO) {
        val gateway = when {
            mac -> FingerprintParsers.macGateway(runCommand("route", "-n", "get", "default"))
            windows -> FingerprintParsers.windowsGateway(runCommand("route", "print", "-4"))
            else -> FingerprintParsers.linuxGateway(runCommand("ip", "route", "show", "default"))
        } ?: return@withContext null
        val (ownIp, subnet) = ownAddressAndSubnet(gateway) ?: return@withContext null
        val dns = when {
            mac -> FingerprintParsers.macDns(runCommand("scutil", "--dns"))
            windows -> FingerprintParsers.windowsDns(
                runCommand("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-DnsClientServerAddress -AddressFamily IPv4).ServerAddresses"),
            )
            else -> FingerprintParsers.resolvectl(runCommand("resolvectl", "dns")).ifEmpty {
                FingerprintParsers.resolvConf(runCatching { java.io.File("/etc/resolv.conf").readText() }.getOrDefault(""))
            }
        }
        // `gateway` is a validated IPv4 literal, so it is safe as a command argument.
        // Warm the ARP entry first so the MAC read below doesn't miss on a quiet network; ignore the result.
        runCommand(*pingCommand(gateway))
        val macAddr = readGatewayMac(mac, windows, gateway, ::runCommand)
        NetworkFingerprint(gateway, subnet, dns, macAddr, ownIp)
    }

    /** One ICMP echo to the gateway; fixed argv, and the gateway is a validated IPv4 literal. */
    private fun pingCommand(gateway: String): Array<String> = when {
        mac -> arrayOf("ping", "-c", "1", "-W", "1000", gateway)
        windows -> arrayOf("ping", "-n", "1", "-w", "1000", gateway)
        else -> arrayOf("ping", "-c", "1", "-W", "1", gateway)
    }

    /** The interface address inside the gateway's subnet, with that subnet's normalized form. */
    private fun ownAddressAndSubnet(gateway: String): Pair<String, String>? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp }
            .flatMap { it.interfaceAddresses.asSequence() }
            .filter { it.address is Inet4Address }
            .mapNotNull { ia ->
                val own = ia.address.hostAddress
                val subnet = Subnets.normalize("$own/${ia.networkPrefixLength}") ?: return@mapNotNull null
                if (Subnets.containsHost(subnet, gateway)) own to subnet else null
            }.firstOrNull()
    }.getOrNull()

}

/**
 * The gateway's MAC from the OS neighbour table. [run] is one fixed command, the same shape as [runCommand].
 * The per-host lookup is enough when it prints a MAC. On macOS that lookup reports "no entry" for an
 * interface-scoped row the full table still shows, so a miss reads the whole table.
 */
internal fun readGatewayMac(
    mac: Boolean,
    windows: Boolean,
    gateway: String,
    run: (Array<out String>) -> String,
): String? {
    val specific = when {
        mac -> arrayOf("arp", "-n", gateway)
        windows -> arrayOf("arp", "-a", gateway)
        else -> arrayOf("ip", "neigh", "show", gateway)
    }
    FingerprintParsers.gatewayMac(run(specific), gateway)?.let { return it }
    val table = when {
        mac -> arrayOf("arp", "-an")
        windows -> arrayOf("arp", "-a")
        else -> arrayOf("ip", "neigh", "show")
    }
    return FingerprintParsers.gatewayMac(run(table), gateway)
}
