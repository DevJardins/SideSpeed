package io.github.devjardins.sidespeed.adb

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/** Thrown when ADB wireless pairing returns false without a more specific library exception. */
class AdbPairingFailedException : IOException()

/** ADB accepted the socket but closed it before the handshake finished (Fire OS refuses on-box ADB this way). */
class AdbClosedException(cause: Throwable? = null) : IOException(cause)

/**
 * Maps a classic connect failure from libadb. A plain [IOException] is libadb's "the handshake ended"
 * (the socket opened, then adbd closed it); a rejected key is [AdbAuthenticationFailedException].
 * Both become [AdbClosedException]. Anything else, such as a closed port, stays as it is.
 */
fun classicConnectFailure(e: Throwable): Throwable =
    if (e.javaClass == IOException::class.java || e is AdbAuthenticationFailedException) AdbClosedException(e) else e

sealed interface AdbTarget {
    data class Classic(val host: String = "127.0.0.1", val port: Int = 5555) : AdbTarget

    /** Wireless Debugging over TLS; the port is found by mDNS discovery in libadb's connectTls. */
    data object WirelessTls : AdbTarget
}

interface AdbBackend : CommandRunner {
    val isConnected: Boolean
    suspend fun connect(target: AdbTarget): Result<Unit>
    suspend fun pair(host: String, port: Int, code: String): Result<Unit>

    /** Emits Lines then exactly one Exit; throws [AdbConnectionLost] if the stream dies first. */
    override fun shell(command: String): Flow<ShellEvent>

    /** Opens a raw service (e.g. "tcpip:5555") and returns its full output. */
    suspend fun openService(service: String): Result<String>
    fun close()
}
