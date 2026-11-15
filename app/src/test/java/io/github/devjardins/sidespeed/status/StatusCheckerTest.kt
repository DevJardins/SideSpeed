package io.github.devjardins.sidespeed.status

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.adb.ShellEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCheckerTest {
    private val pkg = "com.nuvio.tv"

    @Test fun fallsBackToPackageDumpWhenDexoptHasNoBlock() = runTest {
        val fake = FakeAdbBackend()
        fake.on("dumpsys package dexopt") { listOf("Dexopt state:", "  [other.pkg]", "    path: x", "      arm64: [status=speed]") to 0 }
        fake.on("dumpsys package $pkg") { listOf("Dexopt state:", "  [$pkg]", "    path: x", "      arm64: [status=speed-profile]") to 0 }
        assertEquals("speed-profile", StatusChecker(fake).check(pkg).filter)
        assertEquals(listOf("dumpsys package dexopt", "dumpsys package $pkg"), fake.commands)
    }

    @Test fun fallsBackWhenDexoptExitsNonZero() = runTest {
        val fake = FakeAdbBackend()
        fake.on("dumpsys package dexopt") { emptyList<String>() to 1 }
        fake.on("dumpsys package $pkg") { listOf("  [$pkg]", "      arm64: [status=speed]") to 0 }
        assertEquals("speed", StatusChecker(fake).check(pkg).filter)
    }

    @Test fun noFallbackWhenDexoptHasBlock() = runTest {
        val fake = FakeAdbBackend()
        fake.on("dumpsys package dexopt") { listOf("  [$pkg]", "      arm64: [status=verify]") to 0 }
        assertEquals("verify", StatusChecker(fake).check(pkg).filter)
        assertEquals(listOf("dumpsys package dexopt"), fake.commands)
    }

    @Test(expected = AdbConnectionLost::class) fun connectionLostPropagates() = runTest {
        val fake = FakeAdbBackend()
        fake.dropAfterLines("dumpsys package dexopt", 0)
        StatusChecker(fake).check(pkg)
    }

    @Test fun runsOnAnyCommandRunner() = runTest {
        val runner = object : CommandRunner {
            override fun shell(command: String) =
                flowOf<ShellEvent>(ShellEvent.Line("  [$pkg]"), ShellEvent.Line("      arm64: [status=speed]"), ShellEvent.Exit(0))
        }
        assertEquals("speed", StatusChecker(runner).check(pkg).filter)
    }
}
