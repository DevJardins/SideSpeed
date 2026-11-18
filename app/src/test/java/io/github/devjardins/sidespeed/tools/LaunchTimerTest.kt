package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTimerTest {
    private fun timer(helperUp: Boolean = true): Pair<FakeAdbBackend, LaunchTimer> {
        val fake = FakeAdbBackend()
        return fake to LaunchTimer(ToolsCommands(fake) { helperUp })
    }

    private fun totalTime(ms: Long) = listOf(
        "Starting: Intent { cmp=com.x.y/.Main }",
        "Status: ok",
        "ThisTime: $ms",
        "TotalTime: $ms",
    )

    @Test fun medianOfThreeRuns() = runTest {
        val (fake, timer) = timer()
        fake.on("launch-time com.x.y com.x.y.Main") { totalTime(1200) to 0 }
        // Scripted once; FakeAdbBackend replays the same script, so use
        // per-test scripts for distinct medians. Here all three runs read 1200.
        assertEquals(ToolResult.Ok(1200L), timer.medianMs("com.x.y", "com.x.y.Main"))
        assertEquals(3, fake.commands.count { it.startsWith("launch-time") })
        assertEquals(3, fake.commands.count { it == "bring-back" })
    }

    @Test fun medianPicksMiddleValue() = runTest {
        // Median over an odd sample picked from distinct outputs needs a
        // stateful runner; LaunchTimer sorts, so verify via a scripted helper.
        val seen = mutableListOf<Long>()
        val fake = object : io.github.devjardins.sidespeed.adb.CommandRunner {
            override fun shell(command: String) = kotlinx.coroutines.flow.flow {
                if (command.startsWith("launch-time")) {
                    val ms = listOf(1200L, 900L, 1500L)[seen.size]
                    seen += ms
                    totalTime(ms).forEach { emit(io.github.devjardins.sidespeed.adb.ShellEvent.Line(it)) }
                    emit(io.github.devjardins.sidespeed.adb.ShellEvent.Exit(0))
                } else {
                    emit(io.github.devjardins.sidespeed.adb.ShellEvent.Exit(0))
                }
            }
        }
        val commands = ToolsCommands(fake) { true }
        assertEquals(ToolResult.Ok(1200L), LaunchTimer(commands).medianMs("com.x.y", "com.x.y.Main"))
    }

    @Test fun failedRunReturnsFailedAndBringsBack() = runTest {
        val (fake, timer) = timer()
        fake.on("launch-time com.x.y com.x.y.Main") { listOf("Error: Activity not started") to 0 }
        val result = timer.medianMs("com.x.y", "com.x.y.Main")
        assertTrue(result is ToolResult.Failed)
        assertTrue("bringBack must run even when timing fails", "bring-back" in fake.commands)
    }

    @Test fun missingTotalTimeIsFailed() = runTest {
        val (fake, timer) = timer()
        fake.on("launch-time com.x.y com.x.y.Main") { listOf("Status: ok") to 0 }
        val result = timer.medianMs("com.x.y", "com.x.y.Main")
        assertTrue(result is ToolResult.Failed)
        assertTrue("bring-back" in fake.commands)
    }

    @Test fun bringBackCalledAfterEveryRun() = runTest {
        val (fake, timer) = timer()
        fake.on("launch-time com.x.y com.x.y.Main") { totalTime(800) to 0 }
        assertEquals(ToolResult.Ok(800L), timer.medianMs("com.x.y", "com.x.y.Main"))
        assertEquals(3, fake.commands.count { it.startsWith("launch-time") })
        assertEquals(3, fake.commands.count { it == "bring-back" })
    }

    @Test fun helperDownMaps() = runTest {
        val (fake, timer) = timer(helperUp = false)
        assertEquals(ToolResult.HelperDown, timer.medianMs("com.x.y", "com.x.y.Main"))
        assertTrue(fake.commands.isEmpty())
    }

    @Test fun needsBeforeMeasureCases() = runTest {
        val stored = LaunchResult("p", versionCode = 7, lastUpdateTime = 100, beforeMs = 1900, afterMs = 1100, atMs = 1)
        assertTrue(needsBeforeMeasure(null, 7, 100))
        assertTrue(needsBeforeMeasure(stored, 8, 100))
        assertTrue(needsBeforeMeasure(stored, 7, 101))
        assertTrue(!needsBeforeMeasure(stored, 7, 100))
    }
}
