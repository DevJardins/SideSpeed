package io.github.devjardins.sidespeed.adb

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/** Thrown when ADB wireless pairing returns false without a more specific library exception. */
class AdbPairingFailedException : IOException()

/** Thrown when wireless TLS connect timed out waiting for mDNS to advertise a port. */
class AdbMdnsMissException(cause: Throwable? = null) : IOException(cause)

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
