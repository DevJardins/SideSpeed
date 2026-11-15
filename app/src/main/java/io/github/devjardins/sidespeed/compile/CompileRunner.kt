package io.github.devjardins.sidespeed.compile

import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.adb.ShellEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Runs `pm compile`, falling back to `cmd package compile`, streaming output as it arrives. */
class CompileRunner(private val runner: CommandRunner) {
    fun run(pkg: PackageName, mode: CompileMode): Flow<CompileEvent> = flow {
        val tail = "compile -m ${mode.arg} -f ${pkg.value}"
        val commands = listOf("pm $tail", "cmd package $tail")
        for ((index, command) in commands.withIndex()) {
            emit(CompileEvent.Attempt(command))
            var exit: Int? = null
            var sawSuccess = false
            try {
                runner.shell(command).collect { event ->
                    when (event) {
                        is ShellEvent.Line -> {
                            if (event.text.trim() == "Success") sawSuccess = true
                            emit(CompileEvent.Output(event.text))
                        }
                        is ShellEvent.Exit -> exit = event.code
                    }
                }
            } catch (e: AdbConnectionLost) {
                emit(CompileEvent.Finished(false, usedFallback = index > 0, message = "Connection lost"))
                return@flow
            }
            if (exit == 0 && sawSuccess) {
                emit(CompileEvent.Finished(true, usedFallback = index > 0, message = "Success"))
                return@flow
            }
        }
        emit(CompileEvent.Finished(false, usedFallback = true, message = "Failure"))
    }
}
