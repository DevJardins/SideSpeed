package io.github.devjardins.sidespeed.adb

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scriptable in-memory [AdbBackend]. Operates on RAW commands: ShellExit wrapping
 * happens only inside LibAdbBackend, and [commands] records the raw strings.
 *
 * Unscripted commands emit just Exit(127) with no lines.
 */
class FakeAdbBackend : AdbBackend {
    private class Script(val lines: List<String>, val exit: Int, val dropAfter: Int?)

    private val scripts = mutableMapOf<String, Script>()
    private val services = mutableMapOf<String, Result<String>>()
    private var connected = false

    /** Raw commands passed to [shell], in order. */
    val commands = mutableListOf<String>()

    /** Targets passed to [connect], in order. */
    val connectAttempts = mutableListOf<AdbTarget>()

    /** When set, [connect] fails with this exception. */
    var failConnect: Throwable? = null

    /** Per-target failures, checked before [failConnect]. */
    val failConnectFor = mutableMapOf<AdbTarget, Throwable>()

    /** When true, [connect] suspends forever (for timeout tests). */
    var hangConnect = false

    /** Result returned by [pair]. */
    var pairResult: Result<Unit> = Result.success(Unit)

    /** Result for [openService] when the service is not scripted via [onService]. */
    var defaultServiceResult: Result<String> = Result.success("")

    override val isConnected: Boolean get() = connected

    /** Script an exact raw command: emits [lines] then Exit(code). */
    fun on(command: String, block: () -> Pair<List<String>, Int>) {
        val (lines, exit) = block()
        scripts[command] = Script(lines, exit, null)
    }

    /** Make an already scripted (or unscripted) command emit [n] lines then throw [AdbConnectionLost]. */
    fun dropAfterLines(command: String, n: Int, lines: List<String> = emptyList()) {
        val existing = scripts[command]
        scripts[command] = Script(existing?.lines ?: lines, existing?.exit ?: 0, n)
    }

    fun onService(service: String, result: Result<String>) {
        services[service] = result
    }

    override suspend fun connect(target: AdbTarget): Result<Unit> {
        connectAttempts += target
        if (hangConnect) awaitCancellation()
        val failure = failConnectFor[target] ?: failConnect
        if (failure != null) return Result.failure(failure)
        connected = true
        return Result.success(Unit)
    }

    override suspend fun pair(host: String, port: Int, code: String): Result<Unit> = pairResult

    override fun shell(command: String): Flow<ShellEvent> = flow {
        commands += command
        val script = scripts[command]
        if (script == null) {
            emit(ShellEvent.Exit(127))
            return@flow
        }
        val limit = script.dropAfter
        if (limit != null) {
            script.lines.take(limit).forEach { emit(ShellEvent.Line(it)) }
            throw AdbConnectionLost(null)
        }
        script.lines.forEach { emit(ShellEvent.Line(it)) }
        emit(ShellEvent.Exit(script.exit))
    }

    override suspend fun openService(service: String): Result<String> =
        services[service] ?: defaultServiceResult

    override fun close() {
        connected = false
    }
}
