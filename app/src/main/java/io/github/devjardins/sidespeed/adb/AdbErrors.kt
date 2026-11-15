package io.github.devjardins.sidespeed.adb

import kotlin.coroutines.cancellation.CancellationException

/** Like runCatching, but never swallows coroutine cancellation. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** Wraps stream-open failures as [AdbConnectionLost]; cancellation and other errors pass through. */
fun Throwable.asConnectionLost(): Throwable = when (this) {
    is CancellationException -> this
    is AdbConnectionLost -> this
    is java.io.IOException, is InterruptedException, is IllegalStateException -> AdbConnectionLost(this)
    else -> this
}
