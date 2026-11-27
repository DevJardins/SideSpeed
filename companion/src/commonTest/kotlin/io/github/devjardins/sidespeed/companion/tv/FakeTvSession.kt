package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.adb.AdbConnectionLost

/**
 * A scripted TV for tests. [outputs] maps each command to its stdout; an unscripted command fails the test
 * (the companion only ever sends a fixed set). [sent] records every command in order.
 *
 * Use [connector] to hand it out: [failOnOpen] makes open() throw [TvUnreachable], [authPending] makes it throw
 * [TvAuthPending]. [loseConnectionOn] makes that command throw [AdbConnectionLost], after which the session is
 * dead, as a real one is.
 */
class FakeTvSession(
    outputs: Map<String, String> = emptyMap(),
    var failOnOpen: Boolean = false,
    var authPending: Boolean = false,
    var loseConnectionOn: String? = null,
    var packageCmdHandler: ((Array<out String>) -> InstallStream)? = null,
    var onClose: (() -> Unit)? = null,
) : TvSession {
    val outputs: MutableMap<String, String> = outputs.toMutableMap()

    /**
     * Answers taken in order for one command. Once this queue is empty, [outputs] is used.
     * A first serial that is wrong and a later one that is right is how a test shows the gate
     * must not hand the session back.
     */
    val nextAnswers: MutableMap<String, ArrayDeque<String>> = mutableMapOf()

    /** Encrypted opens require this. Tests that stand in for a finished TLS handshake leave it true. */
    override var tlsNegotiated: Boolean = true

    private val _sent = mutableListOf<String>()
    val sent: List<String> get() = _sent.toList()

    /** Every `address:port` passed to [connector]'s open(), including failed attempts. */
    val opened = mutableListOf<String>()

    var closed = false
        private set

    fun connector(): TvConnector = TvConnector { address, port ->
        opened += "$address:$port"
        when {
            failOnOpen -> throw TvUnreachable(null)
            authPending -> throw TvAuthPending()
            else -> {
                closed = false
                this
            }
        }
    }

    override suspend fun run(command: String): String {
        if (closed) throw AdbConnectionLost("session closed")
        _sent += command
        if (command == loseConnectionOn) {
            closed = true
            throw AdbConnectionLost("scripted connection loss")
        }
        val queued = nextAnswers[command]?.removeFirstOrNull()
        if (queued != null) return queued.trim()
        return outputs[command]?.trim() ?: throw AssertionError("Unscripted command sent to TV: $command")
    }

    override suspend fun openPackageCmd(vararg args: String): InstallStream {
        if (closed) throw AdbConnectionLost("session closed")
        val handler = packageCmdHandler ?: throw UnsupportedInstallStreamException()
        return handler(args)
    }

    override fun close() {
        closed = true
        onClose?.invoke()
    }
}
