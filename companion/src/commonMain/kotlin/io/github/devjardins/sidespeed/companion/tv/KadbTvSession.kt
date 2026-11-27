package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb
import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Opens a TCP ADB connection with Kadb (pinned to 2.1.4: 2.1.5 ships Java 25 bytecode), using one key
 * generated on first use and persisted under [keyDir] (see [CompanionKeys]).
 *
 * `Kadb.create` against a wireless-debugging connect port does the STLS handshake itself when the peer
 * answers STLS, using the key [CompanionKeys] configured. Pairing ([kadbPairer]) writes that same key,
 * so a paired TV accepts the connection without a separate TLS call.
 *
 * Failure mapping:
 * - anything that fails the connect or handshake (refused, no route, connect timeout, peer closed) → [TvUnreachable];
 * - no failure but no completed handshake after [authTimeoutMs] → [TvAuthPending]. TCP must have connected by
 *   then, because Kadb gives up on TCP after [connectTimeoutMs], which is required to be shorter;
 * - a failure after the handshake → [AdbConnectionLost] (from [TvSession.run]).
 */
class KadbTvConnector internal constructor(
    private val authTimeoutMs: Long,
    private val connectTimeoutMs: Long,
    private val commandTimeoutMs: Long,
    private val socketTimeoutMs: Long = authTimeoutMs + ABANDONED_HANDSHAKE_GRACE_MS,
    private val linkFactory: (host: String, port: Int) -> AdbLink,
) : TvConnector {

    constructor(
        keyDir: File,
        authTimeoutMs: Long = 20_000,
        connectTimeoutMs: Long = 5_000,
        commandTimeoutMs: Long = 20_000,
    ) : this(
        keyDir = keyDir,
        authTimeoutMs = authTimeoutMs,
        connectTimeoutMs = connectTimeoutMs,
        commandTimeoutMs = commandTimeoutMs,
        socketTimeoutMs = authTimeoutMs + ABANDONED_HANDSHAKE_GRACE_MS,
    )

    private constructor(
        keyDir: File,
        authTimeoutMs: Long,
        connectTimeoutMs: Long,
        commandTimeoutMs: Long,
        socketTimeoutMs: Long,
    ) : this(authTimeoutMs, connectTimeoutMs, commandTimeoutMs, socketTimeoutMs, { host, port ->
        CompanionKeys.ensureReady(keyDir)
        // Kadb 2.1.4's close() cannot abort a handshake in progress, so a socket read timeout a little past
        // authTimeoutMs/commandTimeoutMs bounds abandoned calls (GuardedLink closes one that completes instead).
        GuardedLink(KadbClient(
            Kadb.create(
                host, port,
                connectTimeout = connectTimeoutMs.toInt(),
                socketTimeout = socketTimeoutMs.toInt(),
            )
        ))
    })

    companion object {
        private const val ABANDONED_HANDSHAKE_GRACE_MS = 5_000L
        const val INSTALL_COMMAND_TIMEOUT_MS = 120_000L
        const val INSTALL_SOCKET_TIMEOUT_MS = INSTALL_COMMAND_TIMEOUT_MS + ABANDONED_HANDSHAKE_GRACE_MS

        fun forInstalls(keyDir: File): KadbTvConnector = KadbTvConnector(
            keyDir = keyDir,
            authTimeoutMs = 20_000L,
            connectTimeoutMs = 5_000L,
            commandTimeoutMs = INSTALL_COMMAND_TIMEOUT_MS,
            socketTimeoutMs = INSTALL_SOCKET_TIMEOUT_MS,
        )
    }

    init {
        require(connectTimeoutMs in 1 until authTimeoutMs) { "connectTimeoutMs must be > 0 and < authTimeoutMs" }
        require(commandTimeoutMs in 1 until socketTimeoutMs) {
            "commandTimeoutMs must be > 0 and below the socket read timeout ($socketTimeoutMs)"
        }
    }

    override suspend fun open(address: String, port: Int): TvSession {
        // Key store failures (KadbCertException) propagate as they are: they are not about the TV.
        val link = withContext(Dispatchers.IO) { linkFactory(address, port) }
        val connected = try {
            callBlocking(link, authTimeoutMs) { link.connect() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            link.close()
            throw TvUnreachable(e)
        }
        if (connected == null) throw TvAuthPending()
        return KadbTvSession(link, commandTimeoutMs)
    }
}

internal class KadbTvSession(private val link: AdbLink, private val commandTimeoutMs: Long) : TvSession {
    @Volatile private var dead = false

    override val tlsNegotiated: Boolean get() = !dead && link.tlsNegotiated

    override suspend fun run(command: String): String {
        // Kadb would silently reconnect a dropped transport on the next call; a session never does,
        // so a reconnect always goes through open() and its auth-timeout handling.
        if (dead || !link.isConnected) {
            close()
            throw AdbConnectionLost("ADB connection is closed")
        }
        val out = try {
            callBlocking(link, commandTimeoutMs) { link.shell(command) }
        } catch (e: CancellationException) {
            close() // the command's stream state is unknown; callBlocking already closed the link
            throw e
        } catch (e: Exception) {
            close()
            throw AdbConnectionLost(e)
        }
        if (out == null) {
            close()
            throw AdbConnectionLost("Command timed out after ${commandTimeoutMs}ms", null)
        }
        return out.value.trim()
    }

    override suspend fun openPackageCmd(vararg args: String): InstallStream {
        if (dead || !link.isConnected) {
            close()
            throw AdbConnectionLost("ADB connection is closed")
        }
        val out = try {
            callBlocking(link, commandTimeoutMs) { link.openPackageCmd(*args) }
        } catch (e: UnsupportedInstallStreamException) {
            throw e
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw AdbConnectionLost(e)
        }
        if (out == null) {
            close()
            throw AdbConnectionLost("Command timed out after ${commandTimeoutMs}ms", null)
        }
        return SessionInstallStream(out.value)
    }

    private inner class SessionInstallStream(private val delegate: InstallStream) : InstallStream {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (dead) throw AdbConnectionLost("ADB connection is closed")
            try {
                delegate.write(bytes, offset, length)
            } catch (e: IOException) {
                this@KadbTvSession.close()
                throw AdbConnectionLost(e)
            }
        }

        override fun flush() {
            if (dead) throw AdbConnectionLost("ADB connection is closed")
            try {
                delegate.flush()
            } catch (e: IOException) {
                this@KadbTvSession.close()
                throw AdbConnectionLost(e)
            }
        }

        override fun readAllText(): String {
            if (dead) throw AdbConnectionLost("ADB connection is closed")
            return try {
                delegate.readAllText()
            } catch (e: IOException) {
                this@KadbTvSession.close()
                throw AdbConnectionLost(e)
            }
        }

        override fun close() = delegate.close()
    }

    override fun close() {
        dead = true
        link.close()
    }
}

private class Box<T>(val value: T)

/** Detached so a timed-out or cancelled caller never waits for the blocking call; closing the link ends it. */
private val blockingCalls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs the blocking [block] on IO. Returns null, after closing [link], if it did not finish within [timeoutMs].
 * If the caller is cancelled, closes [link] and rethrows. A CancellationException thrown by [block] itself is not
 * the caller's cancellation and becomes an [IOException]; in particular Kadb's own connect timeout, a
 * [TimeoutCancellationException], becomes a [SocketTimeoutException].
 */
private suspend fun <T> callBlocking(link: AdbLink, timeoutMs: Long, block: () -> T): Box<T>? {
    val call = blockingCalls.async {
        try {
            block()
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException("connect timed out").apply { initCause(e) }
        } catch (e: CancellationException) {
            throw IOException("ADB call aborted", e)
        }
    }
    val result = try {
        withTimeoutOrNull(timeoutMs) { Box(call.await()) }
    } catch (e: CancellationException) {
        link.close()
        currentCoroutineContext().ensureActive() // the caller was cancelled: rethrows its CancellationException
        throw IOException("ADB call aborted", e)
    }
    if (result == null) link.close()
    return result
}
