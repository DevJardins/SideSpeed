package io.github.devjardins.sidespeed.companion.desktop

import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.upkeep.EgressCheck
import io.github.devjardins.sidespeed.companion.upkeep.EgressRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Asks the OS routing table (a read-only query, nothing is sent) which interface traffic to an address uses. */
class DesktopEgressCheck(private val os: String = System.getProperty("os.name").orEmpty().lowercase()) : EgressCheck {
    private val mac = os.startsWith("mac") || os.startsWith("darwin")
    private val windows = os.startsWith("windows")

    override suspend fun route(address: Ipv4): EgressRoute? = withContext(Dispatchers.IO) {
        val ip = EgressParsers.safeIpv4(address.toString()) ?: return@withContext null
        val name = when {
            mac -> EgressParsers.macInterface(run("route", "-n", "get", ip))
            windows -> EgressParsers.windowsInterface(
                run("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Find-NetRoute -RemoteIPAddress $ip).InterfaceAlias"),
            )
            else -> EgressParsers.linuxInterface(run("ip", "route", "get", ip))
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

    private fun run(vararg cmd: String): String {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.outputStream.close() }
        } catch (e: Exception) {
            return ""
        }
        val out = CompletableFuture.supplyAsync { runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") }
        return try {
            out.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            ""
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
    }
}
