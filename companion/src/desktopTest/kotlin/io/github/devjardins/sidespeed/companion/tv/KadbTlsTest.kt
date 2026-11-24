package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class KadbTlsTest {
    @Test fun aPlainAdbHandshakeIsNotTls() {
        runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val accepted = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = DataInputStream(socket.getInputStream())
                    val header = ByteArray(24)
                    input.readFully(header)
                    val length = ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    check(length in 0..1_048_576) { "peer payload $length" }
                    if (length > 0) input.readFully(ByteArray(length))
                    val banner = "device::features=shell_v2".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(adbMessage(0x4e584e43, 0x01000001, 256 * 1024, banner))
                    socket.getOutputStream().flush()
                    try {
                        while (input.read() != -1) {
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val dir = createTempDirectory("kadb-tls").toFile()
        try {
            val session = KadbTvConnector(
                dir,
                authTimeoutMs = 5_000,
                connectTimeoutMs = 2_000,
                commandTimeoutMs = 2_000,
            ).open("127.0.0.1", server.localPort)
            assertFalse(session.tlsNegotiated)
            session.close()
        } finally {
            server.close()
            accepted.join(5_000)
            dir.deleteRecursively()
        }
            failure.get()?.let { throw it }
        }
    }

    @Test fun aFinishedTlsChannelCountsAsTls() {
        val client = finishedClientEngine()
        val transportClass = Class.forName("com.flyfishxu.kadb.transport.TransportChannel")
        val transport = java.lang.reflect.Proxy.newProxyInstance(
            transportClass.classLoader,
            arrayOf(transportClass),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }
        val channel = Class.forName("com.flyfishxu.kadb.transport.TlsNioChannel")
            .getConstructor(transportClass, SSLEngine::class.java)
            .newInstance(transport, client)
        val kadb = Kadb("127.0.0.1", 9, 1_000, 1_000)
        val field = Kadb::class.java.getDeclaredField("connection")
        field.isAccessible = true
        field.set(kadb, Pair(null, channel))
        assertTrue(KadbTls.negotiated(kadb))

        field.set(kadb, Pair(null, "plain"))
        assertFalse(KadbTls.negotiated(kadb))
        kadb.close()
    }

    private fun finishedClientEngine(): SSLEngine {
        val dir = createTempDirectory("kadb-p12").toFile()
        val file = File(dir, "test.p12")
        val password = "changeit".toCharArray()
        try {
            val keytool = File(System.getProperty("java.home"), "bin/keytool")
            val proc = ProcessBuilder(
                keytool.absolutePath,
                "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", file.absolutePath,
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=kadb-tls-test", "-validity", "1",
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.readBytes().decodeToString()
            check(proc.waitFor() == 0) { output }
            val store = KeyStore.getInstance("PKCS12")
            file.inputStream().use { store.load(it, password) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store, password)
            return clientEngine(managers)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun clientEngine(managers: KeyManagerFactory): SSLEngine {
        val trust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        })
        fun context(server: Boolean): SSLContext = SSLContext.getInstance("TLS").apply {
            init(if (server) managers.keyManagers else null, trust, SecureRandom())
        }
        val client = context(false).createSSLEngine("127.0.0.1", 1)
        val server = context(true).createSSLEngine()
        client.useClientMode = true
        server.useClientMode = false
        handshake(client, server)
        return client
    }

    private fun handshake(client: SSLEngine, server: SSLEngine) {
        val toServer = ArrayDeque<ByteBuffer>()
        val toClient = ArrayDeque<ByteBuffer>()
        client.beginHandshake()
        server.beginHandshake()
        var spins = 0
        while (!idle(client) || !idle(server)) {
            check(++spins < 80) { "handshake stuck ${client.handshakeStatus} ${server.handshakeStatus}" }
            drive(client, toClient, toServer)
            drive(server, toServer, toClient)
        }
    }

    private fun idle(engine: SSLEngine): Boolean {
        val status = engine.handshakeStatus
        return status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
            status == SSLEngineResult.HandshakeStatus.FINISHED
    }

    private fun drive(engine: SSLEngine, inbound: ArrayDeque<ByteBuffer>, outbound: ArrayDeque<ByteBuffer>) {
        when (engine.handshakeStatus) {
            SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                var task = engine.delegatedTask
                while (task != null) {
                    task.run()
                    task = engine.delegatedTask
                }
            }
            SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                val result = engine.wrap(ByteBuffer.allocate(0), out)
                check(result.status == SSLEngineResult.Status.OK) { result.toString() }
                out.flip()
                if (out.hasRemaining()) outbound.add(out)
            }
            SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                val packet = inbound.firstOrNull() ?: return
                val result = engine.unwrap(packet, ByteBuffer.allocate(engine.session.applicationBufferSize))
                check(result.status == SSLEngineResult.Status.OK) { result.toString() }
                if (!packet.hasRemaining()) inbound.removeFirst()
            }
            else -> Unit
        }
    }

    private fun adbMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(command)
        out.putInt(arg0)
        out.putInt(arg1)
        out.putInt(payload.size)
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xff
        out.putInt(sum)
        out.putInt(command.inv())
        out.put(payload)
        return out.array()
    }
}
