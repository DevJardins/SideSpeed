package io.github.devjardins.sidespeed.companion.discovery

import com.flyfishxu.kadb.mdns.KadbMdns
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.tv.LinkOffer
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Browses `_adb-tls-connect._tcp` with one shared kadb-mdns browser.
 *
 * [open] runs on the first lookup and again after the browser has been idle. A lookup reads whatever
 * that browser has already collected. When [host] is not among those services yet, the lookup waits
 * up to [timeoutMs] (3 seconds unless a test says otherwise) for a later result. It does not build
 * a browser of its own, and it does not close the shared one.
 */
class KadbMdnsConnectPorts(
    private val browser: SharedMdnsBrowser,
    private val timeoutMs: Long,
    private val clock: () -> Long,
) : ConnectPortFinder {
    constructor(
        open: suspend () -> KadbMdns,
        timeoutMs: Long = MdnsConnectPortFinder.BROWSE_MS,
        clock: () -> Long = { System.currentTimeMillis() },
        stopScope: CoroutineScope = MdnsBrowsers.stopScope,
        idleMs: Long = SharedMdnsBrowser.IDLE_MS,
        pollMs: Long = SharedMdnsBrowser.POLL_MS,
    ) : this(SharedMdnsBrowser(open, clock, idleMs, stopScope, pollMs), timeoutMs, clock)

    override suspend fun find(host: String): Int? = portForHost(host, servicesFor(host))

    override suspend fun servicesFor(host: String): List<TlsConnectService> {
        val deadline = clock() + timeoutMs
        var latest = browser.services()
        while (portForHost(host, latest) == null && clock() < deadline) {
            delay(100)
            latest = browser.services()
        }
        return latest
    }
}

/** Where a shared browser is closed. JmDNS's close blocks, so this is never the lookup's coroutine. */
internal object MdnsBrowsers {
    val stopScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
