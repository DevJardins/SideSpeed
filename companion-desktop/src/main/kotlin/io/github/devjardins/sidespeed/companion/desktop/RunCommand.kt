package io.github.devjardins.sidespeed.companion.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads process output off the caller's thread. Not the common pool: a busy one made the read miss
 * its deadline and the command look empty, so a router address that was in the ARP table was saved as null.
 */
private val commandReaders = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "sidespeed-command").apply { isDaemon = true }
}

/** Runs a fixed command, returning stdout, or "" if it fails or takes over 5 s (a hung command is killed). */
internal fun runCommand(vararg cmd: String): String {
    val p = try {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.outputStream.close() }
    } catch (e: Exception) {
        return ""
    }
    // Read on another thread: reading here would block past the timeout if the command hangs.
    val out = CompletableFuture.supplyAsync(
        { runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") },
        commandReaders,
    )
    return try {
        out.get(5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        ""
    } finally {
        if (p.isAlive) p.destroyForcibly()
    }
}
