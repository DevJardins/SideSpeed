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
 * Opens classic TCP ADB connections (`<ip>:5555`, no pairing or TLS) with Kadb (pinned to 2.1.4: 2.1.5 ships
 * Java 25 bytecode), using one key generated
 * on first use and persisted under [keyDir] (see [CompanionKeys]).
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
    private val linkFactory: (host: String, port: Int) -> AdbLink,
) : TvConnector {

    constructor(
        keyDir: File,
        authTimeoutMs: Long = 20_000,
        connectTimeoutMs: Long = 5_000,
        commandTimeoutMs: Long = 20_000,
    ) : this(authTimeoutMs, connectTimeoutMs, commandTimeoutMs, { host, port ->
        CompanionKeys.ensureReady(keyDir)
        // Kadb 2.1.4's close() cannot abort a handshake in progress, so a socket read timeout a little past
        // authTimeoutMs is what ends a handshake abandoned as TvAuthPending (GuardedLink closes one that
        // completes instead). It also bounds each command read, hence commandTimeoutMs must stay below it.
        GuardedLink(KadbClient(
            Kadb.create(
                host, port,
                connectTimeout = connectTimeoutMs.toInt(),
                socketTimeout = (authTimeoutMs + ABANDONED_HANDSHAKE_GRACE_MS).toInt(),
            )
        ))
    })

    private companion object {
        const val ABANDONED_HANDSHAKE_GRACE_MS = 5_000L
    }

    init {
        require(connectTimeoutMs in 1 until authTimeoutMs) { "connectTimeoutMs must be > 0 and < authTimeoutMs" }
        require(commandTimeoutMs in 1 until authTimeoutMs + ABANDONED_HANDSHAKE_GRACE_MS) {
            "commandTimeoutMs must be > 0 and below the socket read timeout (authTimeoutMs + $ABANDONED_HANDSHAKE_GRACE_MS)"
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
