package io.github.devjardins.sidespeed.companion.upkeep

import io.github.devjardins.sidespeed.companion.net.Subnets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Finds hosts accepting TCP on [port]. It only opens and closes a TCP connection; it never speaks ADB. */
interface Scanner {
    /** Addresses in [subnet] answering on [port], in address order. */
    suspend fun scan(subnet: String, port: Int = 5555): List<String>
}

/**
 * Plain TCP connect scan of a /24 (hosts .1 to .254). Any other prefix, or a malformed subnet, returns empty:
 * the companion never sweeps more than one ordinary home subnet.
 */
class TcpScanner(
    private val connectTimeoutMs: Int = 300,
    private val parallelism: Int = 64,
) : Scanner {
    init {
        require(connectTimeoutMs > 0 && parallelism > 0) { "invalid scanner settings" }
    }

    override suspend fun scan(subnet: String, port: Int): List<String> {
        val normalized = Subnets.normalize(subnet) ?: return emptyList()
        val (network, prefix) = normalized.split('/')
        if (prefix != "24" || port !in 1..65535) return emptyList()
        val base = network.substringBeforeLast('.')
        val gate = Semaphore(parallelism)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                (1..254).map { host ->
                    async {
                        val address = "$base.$host"
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
