package io.github.devjardins.sidespeed.companion.tv

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Manual device check, skipped unless `-Psidespeed.tv=<ip>[:port]` is given:
 *
 *   ./gradlew :companion:desktopTest --tests '*ManualFireTvCheck*' -Psidespeed.tv=192.168.5.140:5555
 *
 * Uses the real desktop key directory (override with `-Psidespeed.keyDir=<dir>`), so "Always allow" on the TV
 * carries over to later runs and to the app.
 */
class ManualFireTvCheck {
    @Test
    fun printSerial() = runBlocking {
        val target = System.getProperty("sidespeed.tv")?.takeIf { it.isNotBlank() }
        if (target == null) {
            println("ManualFireTvCheck skipped: pass -Psidespeed.tv=<ip>[:port]")
            return@runBlocking
        }
        val host = target.substringBefore(':')
        val port = target.substringAfter(':', "5555").toInt()
        val keyDir = System.getProperty("sidespeed.keyDir")?.takeIf { it.isNotBlank() }?.let(::File) ?: DesktopDirs.keyDir()
        println("ManualFireTvCheck: key dir $keyDir, connecting to $host:$port (accept \"Allow debugging?\" on the TV)")
        val started = System.currentTimeMillis()
        val result = runCatching {
            KadbTvConnector(keyDir).open(host, port).use { it.run("getprop ro.serialno") }
        }
        val ms = System.currentTimeMillis() - started
        result.fold(
            { println("ManualFireTvCheck: ro.serialno = $it (${ms} ms)") },
            { println("ManualFireTvCheck: FAILED after ${ms} ms with ${it::class.simpleName}: ${it.cause ?: it.message}") },
        )
        result.getOrThrow()
        Unit
    }
}
