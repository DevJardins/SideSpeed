package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.upkeep.EgressCheck
import io.github.devjardins.sidespeed.companion.upkeep.EgressRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/** Asks the OS routing table (a read-only query, nothing is sent) which interface traffic to an address uses. */
class DesktopEgressCheck(private val os: String = System.getProperty("os.name").orEmpty().lowercase()) : EgressCheck {
    private val mac = os.startsWith("mac") || os.startsWith("darwin")
    private val windows = os.startsWith("windows")

    override suspend fun route(address: Ipv4): EgressRoute? = withContext(Dispatchers.IO) {
        val ip = EgressParsers.safeIpv4(address.toString()) ?: return@withContext null
        val name = when {
            mac -> EgressParsers.macInterface(runCommand("route", "-n", "get", ip))
            windows -> EgressParsers.windowsInterface(
                runCommand("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Find-NetRoute -RemoteIPAddress $ip).InterfaceAlias"),
            )
            else -> EgressParsers.linuxInterface(runCommand("ip", "route", "get", ip))
        } ?: return@withContext null
        val addresses = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name == name || it.displayName == name }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { Ipv4.parse(it.hostAddress) }
                .toList()
        }.getOrDefault(emptyList())
        EgressRoute(name, addresses)
    }

}
