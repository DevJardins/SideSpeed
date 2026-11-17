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
) : TvSession {
    val outputs: MutableMap<String, String> = outputs.toMutableMap()

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
        return outputs[command]?.trim() ?: throw AssertionError("Unscripted command sent to TV: $command")
    }

    override fun close() {
        closed = true
    }
}
