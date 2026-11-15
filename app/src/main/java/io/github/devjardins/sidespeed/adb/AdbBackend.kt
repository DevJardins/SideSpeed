package io.github.devjardins.sidespeed.adb

import kotlinx.coroutines.flow.Flow

sealed interface AdbTarget {
    data class Classic(val host: String = "127.0.0.1", val port: Int = 5555) : AdbTarget

    /** Wireless Debugging over TLS; the port is found by mDNS discovery in libadb's connectTls. */
    data object WirelessTls : AdbTarget
}

sealed interface ShellEvent {
    data class Line(val text: String) : ShellEvent
    data class Exit(val code: Int) : ShellEvent
}

class AdbConnectionLost(cause: Throwable? = null) : Exception(cause)

interface AdbBackend {
    val isConnected: Boolean
    suspend fun connect(target: AdbTarget): Result<Unit>
    suspend fun pair(host: String, port: Int, code: String): Result<Unit>

    /** Emits Lines then exactly one Exit; throws [AdbConnectionLost] if the stream dies first. */
    fun shell(command: String): Flow<ShellEvent>

    /** Opens a raw service (e.g. "tcpip:5555") and returns its full output. */
    suspend fun openService(service: String): Result<String>
    fun close()
}
