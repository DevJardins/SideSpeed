package io.github.devjardins.sidespeed.status

import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.ShellEvent
import kotlinx.coroutines.flow.toList
import java.io.IOException

/** Compiler-status badges for many packages at once; lets the app list be tested without a helper. */
fun interface BadgeSource {
    suspend fun badges(pkgs: Collection<String>): Map<String, StatusBadge>
}

/**
 * Reads a package's compiler filter via `dumpsys package dexopt`, falling back to
 * `dumpsys package <pkg>` when that command fails or has no usable block for the package.
 * [io.github.devjardins.sidespeed.adb.AdbConnectionLost] is not caught: it propagates to the caller.
 */
class StatusChecker(private val runner: CommandRunner) : BadgeSource {
    suspend fun check(pkg: String): CompilerStatus {
        val (code, dump) = run("dumpsys package dexopt")
        if (code == 0) {
            val status = DexoptParser.parse(dump, pkg)
            if (status.filter != null) return status
        }
        return DexoptParser.parse(run("dumpsys package $pkg").second, pkg)
    }

    /**
     * Badges for many packages from ONE `dumpsys package dexopt` call (no per-package fallback).
     * Throws [IOException] if the command fails, so the caller can show no badges at all.
     */
    override suspend fun badges(pkgs: Collection<String>): Map<String, StatusBadge> {
        val (code, dump) = run("dumpsys package dexopt")
        if (code != 0) throw IOException("dumpsys package dexopt exited with $code")
        val lines = dump.lines()
        return pkgs.associateWith { DexoptParser.badge(DexoptParser.parse(lines, it)) }
    }

    private suspend fun run(command: String): Pair<Int, String> {
        val events = runner.shell(command).toList()
        val code = events.filterIsInstance<ShellEvent.Exit>().lastOrNull()?.code ?: -1
        val text = events.filterIsInstance<ShellEvent.Line>().joinToString("\n") { it.text }
        return code to text
    }
}
