package io.github.devjardins.sidespeed.companion.discovery

import com.flyfishxu.kadb.mdns.KadbMdns
import com.flyfishxu.kadb.mdns.MdnsServiceType
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.tv.LinkOffer
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** True when port 5555 answers STLS. mDNS is checked separately, so this probe stays short. */
class StlsOffer(
    private val probe: AdbProbe,
    private val timeoutMs: Int = 800,
) : LinkOffer {
    override suspend fun encryptedAvailable(address: String): Boolean {
        val ip = Ipv4.parse(address) ?: return false
        return probe.probe(ip, TvConnector.DEFAULT_PORT, timeoutMs) == ProbeResult.AdbTls
    }
}

/**
 * Looks up the wireless-debugging connect port for one TV. Never scans a port range.
 *
 * [servicesFor] is the browse behind [find], including each service's instance name.
 * Null means this finder only answers [find] and has no names to offer. An empty list means
 * the browse saw nothing.
 */
fun interface ConnectPortFinder {
    /** The `_adb-tls-connect` port whose address equals [host], or null when none is advertising. */
    suspend fun find(host: String): Int?

    /** Services seen while looking for [host]. See [ConnectPortFinder]. */
    suspend fun servicesFor(host: String): List<TlsConnectService>? = null
}

data class TlsConnectService(val host: String, val port: Int, val name: String = "")

/** The port of the service whose address equals [host]. A different host is ignored. */
fun portForHost(host: String, services: List<TlsConnectService>): Int? =
    services.firstOrNull { it.host == host && it.port in 1..65535 }?.port

/**
 * The service whose instance name is `adb-<serial>-` plus a suffix.
 *
 * adbd registers `_adb-tls-connect` under that name. `GenerateDeviceGuid` in
 * `packages/modules/adb/daemon/mdns.cpp` builds `adb-` + `ro.serialno` + `-` + a suffix
 * (six random characters when it generates the guid). `adb-ABC-…` does not match serial `AB`.
 */
fun serviceForSerial(serial: String, services: List<TlsConnectService>): TlsConnectService? {
    if (serial.isEmpty()) return null
    val prefix = "adb-$serial-"
    return services.firstOrNull { it.name.length > prefix.length && it.name.startsWith(prefix) }
}

/**
 * Asks [browse] for the TLS connect services and keeps the one for the host.
 * [browse] is given [timeoutMs] (3 seconds unless a test says otherwise).
 */
class MdnsConnectPortFinder(
    private val browse: suspend (timeoutMs: Long) -> List<TlsConnectService>,
    private val timeoutMs: Long = BROWSE_MS,
) : ConnectPortFinder {
    override suspend fun find(host: String): Int? = portForHost(host, servicesFor(host))

    override suspend fun servicesFor(host: String): List<TlsConnectService> =
        withTimeoutOrNull(timeoutMs) { browse(timeoutMs) } ?: emptyList()

    companion object {
        const val BROWSE_MS = 3_000L
    }
}

/**
 * Browses `_adb-tls-connect._tcp` with kadb-mdns for up to [timeoutMs]. [open] builds a fresh
 * browser for each lookup, so two lookups never share one session.
 */
class KadbMdnsConnectPorts(
    private val open: () -> KadbMdns,
    private val timeoutMs: Long = MdnsConnectPortFinder.BROWSE_MS,
) : ConnectPortFinder {
    override suspend fun find(host: String): Int? = portForHost(host, servicesFor(host))

    override suspend fun servicesFor(host: String): List<TlsConnectService> {
        val mdns = open()
        mdns.start()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var latest = emptyList<TlsConnectService>()
            while (true) {
                latest = mdns.state.value.connectDevices
                    .filter { it.serviceType == MdnsServiceType.TLS_CONNECT }
                    .map { TlsConnectService(it.host, it.port, it.name) }
                // The saved address is enough: stop as soon as it is advertising.
                if (portForHost(host, latest) != null) return latest
                if (System.currentTimeMillis() >= deadline) return latest
                delay(100)
            }
        } finally {
            mdns.stop()
        }
    }
}
