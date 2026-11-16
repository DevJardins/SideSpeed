package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.Kadb

/**
 * The blocking ADB operations [KadbTvConnector] needs, so failure mapping can be tested without a TV.
 * Every call may block; close() must unblock a call in progress.
 */
internal interface AdbLink : AutoCloseable {
    /** Opens TCP and completes the ADB handshake, including waiting for the TV to authorise the key. */
    fun connect()

    /** Runs [command] on the established connection and returns its stdout. */
    fun shell(command: String): String

    /** True while the transport established by [connect] is still open. */
    val isConnected: Boolean
}

internal class KadbLink(private val kadb: Kadb) : AdbLink {
    // Kadb connects lazily on the first call that needs the connection; reading the negotiated feature set is
    // the cheapest such call and sends no command to the TV.
    override fun connect() {
        kadb.supportsFeature("shell_v2")
    }

    override fun shell(command: String): String = kadb.shell(command).output

    override val isConnected: Boolean get() = kadb.connectionCheck()

    override fun close() = kadb.close()
}
