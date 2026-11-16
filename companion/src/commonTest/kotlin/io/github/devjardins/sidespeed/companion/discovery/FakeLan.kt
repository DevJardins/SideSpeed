package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.delay

/**
 * A scripted LAN for virtual-time discovery tests. [script] maps each address to its per-attempt results;
 * an unscripted address stays silent ([ProbeResult.Timeout]). Every probe records its call, so tests can
 * assert order, ports, timeouts and attempt counts without any sockets.
 */
internal class FakeLan(
    private val script: Map<Ipv4, List<ProbeResult>> = emptyMap(),
    private val delayMs: Long = 0,
) : AdbProbe {
    data class Call(val address: Ipv4, val port: Int, val timeoutMs: Int)

    val calls = mutableListOf<Call>()
    private val attempts = mutableMapOf<Ipv4, Int>()

    fun attemptsOf(address: Ipv4): Int = attempts[address] ?: 0

    override suspend fun probe(address: Ipv4, port: Int, timeoutMs: Int): ProbeResult {
        calls += Call(address, port, timeoutMs)
        val n = attempts[address] ?: 0
        attempts[address] = n + 1
        if (delayMs > 0) delay(delayMs)
        return script[address]?.getOrNull(n) ?: ProbeResult.Timeout
    }
}
