package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Whether a Kadb 2.1.4 connection finished TLS.
 *
 * Kadb has no public API for this. [com.flyfishxu.kadb.core.AdbConnection.connect] leaves the
 * plain channel in place when the peer answers CNXN, and stores a
 * [com.flyfishxu.kadb.transport.TlsNioChannel] only after that channel's handshake returns.
 * TlsNioChannel wraps an [SSLEngine], not an [javax.net.ssl.SSLSocket], so the socket type
 * cannot answer the question. The channel lives in Kadb's private `connection` pair. This
 * reads that pair. A missing field, a plain channel, or a session that is not valid is not TLS.
 */
internal object KadbTls {
    fun negotiated(kadb: Kadb): Boolean = try {
        val channel = channel(kadb) ?: return false
        if (channel.javaClass.name != TLS_CHANNEL) return false
        val engine = engine(channel) ?: return false
        val session = engine.session ?: return false
        if (!session.isValid) return false
        val protocol = session.protocol
        if (protocol.isNullOrEmpty() || protocol == "NONE") return false
        val status = engine.handshakeStatus
        status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
            status == SSLEngineResult.HandshakeStatus.FINISHED
    } catch (_: Exception) {
        false
    }

    private fun channel(kadb: Kadb): Any? {
        val field = Kadb::class.java.getDeclaredField("connection")
        field.isAccessible = true
        val pair = field.get(kadb) as? Pair<*, *> ?: return null
        return pair.second
    }

    private fun engine(channel: Any): SSLEngine? {
        val field = channel.javaClass.getDeclaredField("engine")
        field.isAccessible = true
        return field.get(channel) as? SSLEngine
    }

    private const val TLS_CHANNEL = "com.flyfishxu.kadb.transport.TlsNioChannel"
}
