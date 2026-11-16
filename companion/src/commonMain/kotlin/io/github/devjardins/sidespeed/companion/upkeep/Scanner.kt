package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.Subnets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Finds hosts accepting TCP on [port]. It only opens and closes a TCP connection; it never speaks ADB. */
interface Scanner {
    /** ADB hosts in [subnet] answering on [port]; [near], a host in [subnet], is scanned around first. */
    suspend fun scan(subnet: String, port: Int = 5555, near: String? = null): List<String>

    /**
     * The same hosts as [scan], streamed as they answer so a caller can stop the sweep early.
     * Cancelling the collection stops the sweep.
     */
    fun hosts(subnet: String, port: Int = 5555, near: String? = null): Flow<String> =
        flow { scan(subnet, port, near).forEach { emit(it) } }
}

/**
 * Plain TCP connect scan of a /16 to /30, probing at most [MAX_PROBES] hosts in ascending address order
 * (a /16 holds 65,534 hosts, so only its first 4096 are probed). Any other prefix, or a malformed subnet,
 * returns empty.
 */
class TcpScanner(
    private val connectTimeoutMs: Int = 300,
    private val parallelism: Int = 64,
) : Scanner {
    companion object {
        /** The most addresses one scan ever probes: the low end of a /16. */
        const val MAX_PROBES = 4096

        /**
         * Usable host addresses of [subnet] in ascending order, at most [maxProbes]; empty when the prefix is
         * outside /16../30 or the subnet is malformed.
         */
        fun probeTargets(subnet: String, maxProbes: Int = MAX_PROBES): List<String> {
            val normalized = Subnets.normalize(subnet) ?: return emptyList()
            val (network, prefixText) = normalized.split('/')
            val prefix = prefixText.toInt()
            if (prefix !in 16..30) return emptyList()
            val start = network.split('.').fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
            val size = 1L shl (32 - prefix)
            return (1 until size - 1).take(maxProbes).map { v ->
                val a = start + v
                "${(a shr 24) and 255}.${(a shr 16) and 255}.${(a shr 8) and 255}.${a and 255}"
            }
        }
    }

    init {
        require(connectTimeoutMs > 0 && parallelism > 0) { "invalid scanner settings" }
    }

    override suspend fun scan(subnet: String, port: Int, near: String?): List<String> {
        if (port !in 1..65535) return emptyList()
        val targets = probeTargets(subnet)
        if (targets.isEmpty()) return emptyList()
        val gate = Semaphore(parallelism)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                targets.map { address ->
                    async {
                        gate.withPermit { if (answers(address, port)) address else null }
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private fun answers(address: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), connectTimeoutMs) }
        true
    } catch (e: IOException) {
        false
    }
}
