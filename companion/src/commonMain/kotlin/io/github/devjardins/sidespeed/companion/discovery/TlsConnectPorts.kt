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

/** Looks up the wireless-debugging connect port for one TV. Never scans a port range. */
fun interface ConnectPortFinder {
    /** The `_adb-tls-connect` port whose address equals [host], or null when none is advertising. */
    suspend fun find(host: String): Int?
}

data class TlsConnectService(val host: String, val port: Int)

/** The port of the service whose address equals [host]. A different host is ignored. */
fun portForHost(host: String, services: List<TlsConnectService>): Int? =
    services.firstOrNull { it.host == host && it.port in 1..65535 }?.port

/**
 * Asks [browse] for the TLS connect services and keeps the one for the host.
 * [browse] is given [timeoutMs] (3 seconds unless a test says otherwise).
 */
class MdnsConnectPortFinder(
    private val browse: suspend (timeoutMs: Long) -> List<TlsConnectService>,
    private val timeoutMs: Long = BROWSE_MS,
) : ConnectPortFinder {
    override suspend fun find(host: String): Int? {
        val services = withTimeoutOrNull(timeoutMs) { browse(timeoutMs) } ?: return null
        return portForHost(host, services)
    }

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
    override suspend fun find(host: String): Int? {
        val mdns = open()
        mdns.start()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val services = mdns.state.value.connectDevices
                    .filter { it.serviceType == MdnsServiceType.TLS_CONNECT }
                    .map { TlsConnectService(it.host, it.port) }
                portForHost(host, services)?.let { return it }
                if (System.currentTimeMillis() >= deadline) return null
                delay(100)
            }
        } finally {
            mdns.stop()
        }
    }
}
