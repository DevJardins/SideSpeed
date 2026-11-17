package io.github.devjardins.sidespeed.adb

import kotlinx.coroutines.flow.Flow

/** Runs one shell command. Implemented by [AdbBackend] and by the shell-uid helper client. */
interface CommandRunner {
    /** Emits Lines then exactly one Exit; throws [AdbConnectionLost] if the stream dies first. */
    fun shell(command: String): Flow<ShellEvent>
}
