package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.coroutines.resume

/** One UDP reply: who sent it and what it said. */
class Datagram(val from: Ipv4, val bytes: ByteArray)

/**
 * Sends one UDP request and collects the unicast replies. Nothing is bound on a well-known port and no
 * multicast group is joined: replies come back to the ephemeral sender port.
 */
fun interface DatagramQuery {
    /** Sends [payload] once to [target]:[port], collects every reply until [listenMs] passes; empty on any I/O failure. */
    suspend fun query(target: Ipv4, port: Int, payload: ByteArray, listenMs: Long, via: Ipv4?): List<Datagram>
}

/** Socket implementation: one ephemeral [MulticastSocket] for the send, plain receives for the replies. */
class SocketDatagramQuery : DatagramQuery {
    override suspend fun query(
        target: Ipv4,
        port: Int,
        payload: ByteArray,
        listenMs: Long,
        via: Ipv4?,
    ): List<Datagram> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val socket = runCatching {
                MulticastSocket(0).also { s ->
                    s.timeToLive = 255
                    runCatching { via?.let { s.setInterface(InetAddress.getByName(it.toString())) } }
                }
            }.getOrNull()
            if (socket == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            // A cancelled Find closes the socket: the blocked receive ends and nothing is emitted.
            cont.invokeOnCancellation { runCatching { socket.close() } }
            cont.resume(runCatching { collect(socket, target, port, payload, listenMs) }.getOrDefault(emptyList()))
            runCatching { socket.close() }
        }
    }

    private fun collect(
        socket: DatagramSocket,
        target: Ipv4,
        port: Int,
        payload: ByteArray,
        listenMs: Long,
    ): List<Datagram> {
        val out = mutableListOf<Datagram>()
        val address = InetAddress.getByName(target.toString())
        socket.send(DatagramPacket(payload, payload.size, address, port))
        val end = System.currentTimeMillis() + listenMs
        val buf = ByteArray(65507)
        while (true) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) break
            socket.soTimeout = left
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (_: Exception) {
                break
            }
            val from = Ipv4.parse(p.address.hostAddress) ?: continue
            out += Datagram(from, p.data.copyOf(p.length))
        }
        return out
    }
}
