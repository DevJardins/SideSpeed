package io.github.devjardins.sidespeed.companion.tv

/** The ADB peer supports neither `abb_exec` nor `exec:cmd` for streamed package installs. */
class UnsupportedInstallStreamException : Exception()

/** One ADB connection to a TV. Closing it drops the connection. */
interface TvSession : AutoCloseable {
    /**
     * Runs one shell command and returns its trimmed stdout. A non-zero exit code is not an error
     * (`pidof` exits 1 with no output when nothing runs).
     *
     * @throws io.github.devjardins.sidespeed.adb.AdbConnectionLost if the connection fails or has failed.
     */
    suspend fun run(command: String): String

    /**
     * Opens a streamed `package` subcommand (`abb_exec` or `exec:cmd`).
     *
     * @throws UnsupportedInstallStreamException if the TV supports neither transport.
     * @throws io.github.devjardins.sidespeed.adb.AdbConnectionLost if the connection fails or has failed.
     */
    suspend fun openPackageCmd(vararg args: String): InstallStream {
        throw UnsupportedInstallStreamException()
    }

    /**
     * True only when this connection's ADB transport finished a TLS handshake.
     * An encrypted open refuses the session when this is false.
     */
    val tlsNegotiated: Boolean
}

/** The TV could not be reached before the ADB handshake: refused, no route, or the connect timed out. */
class TvUnreachable(cause: Throwable?) : Exception(cause)

/** TCP connected, but the TV did not accept our key in time: "Allow USB debugging?" is waiting on screen. */
class TvAuthPending : Exception("Waiting for the TV to allow debugging")

fun interface TvConnector {
    /** @throws TvUnreachable @throws TvAuthPending */
    suspend fun open(address: String, port: Int): TvSession

    companion object {
        /** Cleartext ADB. Encrypted TVs use a wireless-debugging port instead of this one. */
        const val DEFAULT_PORT = 5555
    }
}

/** Opens [address] on [TvConnector.DEFAULT_PORT]. (A `fun interface` method cannot declare the default itself.) */
suspend fun TvConnector.open(address: String): TvSession = open(address, TvConnector.DEFAULT_PORT)
