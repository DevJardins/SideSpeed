package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import java.io.EOFException
import java.io.IOException

/** Streamed ADB `package` command handle (`abb_exec` or `exec:cmd`). */
interface InstallStream : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun flush()
    fun readAllText(): String
}

/**
 * The blocking ADB operations [KadbTvConnector] needs, so failure mapping can be tested without a TV.
 * Every call may block; close() must unblock a call in progress, or make it release what it opened.
 */
internal interface AdbLink : AutoCloseable {
    /** Opens TCP and completes the ADB handshake, including waiting for the TV to authorise the key. */
    fun connect()

    /** Runs [command] on the established connection and returns its stdout. */
    fun shell(command: String): String

    /** Opens a streamed `package` command (`abb_exec` or `exec:cmd`). */
    fun openPackageCmd(vararg args: String): InstallStream

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
    fun openPackageCmd(vararg args: String): InstallStream
    val isOpen: Boolean

    /** True when [handshake] left a finished TLS transport. See [KadbTls]. */
    val tlsNegotiated: Boolean get() = false
}

private class KadbInstallStream(
    private val stream: AdbStream,
    private val isConnectionOpen: () -> Boolean,
) : InstallStream {
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        stream.sink.write(bytes, offset, length)
    }

    override fun flush() {
        stream.sink.flush()
    }

    override fun readAllText(): String {
        val text = stream.source.readUtf8()
        if (!isConnectionOpen()) throw IOException("ADB link is closed")
        if (text.isBlank()) throw EOFException()
        return text
    }

    override fun close() = stream.close()
}

internal class KadbClient(private val kadb: Kadb) : AdbClient {
    // Kadb connects lazily on the first call that needs the connection; reading the negotiated feature set is
    // the cheapest such call and sends no command to the TV.
    override fun handshake() {
        kadb.supportsFeature("shell_v2")
    }

    override val tlsNegotiated: Boolean get() = KadbTls.negotiated(kadb)

    override fun shell(command: String): String = kadb.shell(command).output

    override fun openPackageCmd(vararg args: String): InstallStream {
        val stream = when {
            kadb.supportsFeature("abb_exec") -> kadb.abbExec("package", *args)
            kadb.supportsFeature("cmd") -> kadb.execCmd("package", *args)
            else -> throw UnsupportedInstallStreamException()
        }
        return KadbInstallStream(stream) { kadb.connectionCheck() }
    }

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

    override fun openPackageCmd(vararg args: String): InstallStream {
        if (closed) throw IOException("ADB link is closed")
        return client.openPackageCmd(*args)
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
