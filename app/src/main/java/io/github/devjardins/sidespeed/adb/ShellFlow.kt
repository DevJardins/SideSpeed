package io.github.devjardins.sidespeed.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform

/**
 * Runs a shell [body] on the IO dispatcher and delivers every [ShellEvent] it emitted before an
 * [AdbConnectionLost] is rethrown to the collector.
 *
 * A plain `flow { ... }.flowOn(IO)` loses lines here: when the producer fails, it cancels the collecting
 * coroutine, and a cancelled receiver drops an element even after it was handed over (prompt cancellation).
 * A connection drop right after a line, such as a compile's last error output, would vanish. So the loss
 * travels as a final in-order item instead, and the producer completes normally.
 */
internal fun shellFlow(body: suspend FlowCollector<ShellEvent>.() -> Unit): Flow<ShellEvent> =
    flow<Any> {
        val out = FlowCollector<ShellEvent> { emit(it) }
        try {
            out.body()
        } catch (e: AdbConnectionLost) {
            emit(Lost(e))
        }
    }.flowOn(Dispatchers.IO).transform { item ->
        if (item is Lost) throw item.error
        emit(item as ShellEvent)
    }

private class Lost(val error: AdbConnectionLost)
