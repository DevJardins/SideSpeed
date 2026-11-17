package io.github.devjardins.sidespeed.companion.discovery

import io.github.devjardins.sidespeed.companion.net.Ipv4
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketDatagramQueryTest {
    private fun ip(s: String) = Ipv4.parse(s)!!

    @Test fun repliesFromALocalResponderAreCollected() {
        val responder = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port = responder.localPort
        val worker = thread(isDaemon = true) {
            val buf = ByteArray(64)
            responder.soTimeout = 1500
            val deadline = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    responder.receive(p)
                } catch (_: Exception) {
                    break
                }
                val pong = "pong".toByteArray()
                repeat(2) { responder.send(DatagramPacket(pong, pong.size, p.address, p.port)) }
            }
            responder.close()
        }
        val datagrams = runBlocking {
            SocketDatagramQuery().query(ip("127.0.0.1"), port, "ping".toByteArray(), 300, null)
        }
        worker.join(4000)
        assertEquals(2, datagrams.size)
        assertTrue(datagrams.all { it.from == ip("127.0.0.1") })
        datagrams.forEach { assertContentEquals("pong".toByteArray(), it.bytes) }
    }

    @Test fun nothingAnsweringIsEmpty() {
        val start = System.currentTimeMillis()
        val got = runBlocking {
            SocketDatagramQuery().query(ip("127.0.0.1"), 9, "ping".toByteArray(), 200, null)
        }
        assertTrue(got.isEmpty())
        assertTrue(System.currentTimeMillis() - start < 1000)
    }
}
