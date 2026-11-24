package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb
import java.io.IOException

/**
 * The blocking ADB operations [KadbTvConnector] needs, so failure mapping can be tested without a TV.
 * Every call may block; close() must unblock a call in progress, or make it release what it opened.
 */
internal interface AdbLink : AutoCloseable {
    /** Opens TCP and completes the ADB handshake, including waiting for the TV to authorise the key. */
    fun connect()

    /** Runs [command] on the established connection and returns its stdout. */
    fun shell(command: String): String

    /** True while the transport established by [connect] is still open. */
    val isConnected: Boolean

    /**
     * True when the transport [connect] established finished TLS.
     * False before [connect], and false when the peer answered in cleartext.
     */
    val tlsNegotiated: Boolean get() = false
}

/** The raw Kadb calls, behind an interface so [GuardedLink]'s close handling is testable. */
internal interface AdbClient : AutoCloseable {
    /** Connects lazily if needed; blocks through the handshake. */
    fun handshake()
    fun shell(command: String): String
    val isOpen: Boolean

    /** True when [handshake] left a finished TLS transport. See [KadbTls]. */
    val tlsNegotiated: Boolean get() = false
}

internal class KadbClient(private val kadb: Kadb) : AdbClient {
    // Kadb connects lazily on the first call that needs the connection; reading the negotiated feature set is
    // the cheapest such call and sends no command to the TV.
    override fun handshake() {
        kadb.supportsFeature("shell_v2")
    }

    override val tlsNegotiated: Boolean get() = KadbTls.negotiated(kadb)

    override fun shell(command: String): String = kadb.shell(command).output

    override val isOpen: Boolean get() = kadb.connectionCheck()

    override fun close() = kadb.close()
}

/**
 * Makes close() final. Kadb 2.1.4's close() only closes an *established* connection: during a handshake the
 * transport is still local to Kadb, so close() does nothing, and a handshake that completes afterwards (the user
 * taps "Allow" after TvAuthPending, or the caller was cancelled) would store a live, authenticated connection
 * nobody closes. Kadb would also silently reconnect on the next shell() after a close. So: once closed, a
 * completed handshake is closed again and shell() is refused.
 */
internal class GuardedLink(private val client: AdbClient) : AdbLink {
    private val lock = Any()
    @Volatile private var closed = false

    override fun connect() {
        client.handshake()
        synchronized(lock) {
            if (closed) {
                client.close()
                throw IOException("ADB link was closed during the handshake")
            }
        }
    }

    override fun shell(command: String): String {
        if (closed) throw IOException("ADB link is closed")
        return client.shell(command)
    }

    override val isConnected: Boolean get() = !closed && client.isOpen

    override val tlsNegotiated: Boolean get() = !closed && client.tlsNegotiated

    override fun close() {
        synchronized(lock) {
            closed = true
            client.close()
        }
    }
}
