package io.github.devjardins.sidespeed.compile

import io.github.devjardins.sidespeed.adb.CommandRunner
import io.github.devjardins.sidespeed.adb.FakeAdbBackend
import io.github.devjardins.sidespeed.adb.ShellEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileRunnerTest {
    private val pkg = PackageName.parse("com.nuvio.tv")!!
    private val primary = "pm compile -m speed -f com.nuvio.tv"
    private val fallback = "cmd package compile -m speed -f com.nuvio.tv"

    @Test fun primarySucceeds() = runTest {
        val fake = FakeAdbBackend()
        fake.on(primary) { listOf("Success") to 0 }
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(
            listOf(CompileEvent.Attempt(primary), CompileEvent.Output("Success"), CompileEvent.Finished(true, false, CompileOutcome.Success)),
            events,
        )
        assertEquals(listOf(primary), fake.commands)
    }

    @Test fun primaryFailsFallbackSucceeds() = runTest {
        val fake = FakeAdbBackend()
        fake.on(primary) { listOf("Error: unknown command") to 1 }
        fake.on(fallback) { listOf("Success") to 0 }
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(
            listOf(
                CompileEvent.Attempt(primary),
                CompileEvent.Output("Error: unknown command"),
                CompileEvent.Attempt(fallback),
                CompileEvent.Output("Success"),
                CompileEvent.Finished(true, true, CompileOutcome.Success),
            ),
            events,
        )
    }

    @Test fun bothFail() = runTest {
        val fake = FakeAdbBackend()
        val msg = "Failure: package com.gone.app not found"
        fake.on(primary) { listOf(msg) to 1 }
        fake.on(fallback) { listOf(msg) to 1 }
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(CompileEvent.Finished(false, true, CompileOutcome.Failure), events.last())
        assertEquals(2, events.count { it == CompileEvent.Output(msg) })
    }

    @Test fun exitZeroWithoutSuccessLineFallsBack() = runTest {
        val fake = FakeAdbBackend()
        fake.on(primary) { listOf("something odd") to 0 }
        fake.on(fallback) { listOf("Success") to 0 }
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(listOf(primary, fallback), fake.commands)
        assertEquals(CompileEvent.Finished(true, true, CompileOutcome.Success), events.last())
    }

    @Test fun successLineWithNonZeroExitFallsBack() = runTest {
        val fake = FakeAdbBackend()
        fake.on(primary) { listOf("Success") to 1 }
        fake.on(fallback) { emptyList<String>() to 1 }
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(CompileEvent.Finished(false, true, CompileOutcome.Failure), events.last())
    }

    @Test fun connectionLostOnPrimary() = runTest {
        val fake = FakeAdbBackend()
        fake.on(primary) { listOf("Compiling", "more") to 0 }
        fake.dropAfterLines(primary, 1)
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(CompileEvent.Finished(false, false, CompileOutcome.ConnectionLost), events.last())
        assertEquals(listOf(primary), fake.commands)
    }

    @Test fun connectionLostDuringFallbackMarksUsedFallback() = runTest {
        val fake = FakeAdbBackend()
        fake.on(primary) { emptyList<String>() to 1 }
        fake.on(fallback) { listOf("Compiling") to 0 }
        fake.dropAfterLines(fallback, 1)
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(CompileEvent.Finished(false, true, CompileOutcome.ConnectionLost), events.last())
    }

    @Test fun flowEndingWithoutExitIsFailure() = runTest {
        val fake = FakeAdbBackend() // unscripted emits Exit(127); both fail
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED).toList()
        assertEquals(CompileEvent.Finished(false, true, CompileOutcome.Failure), events.last())
    }

    @Test fun speedProfileMode() = runTest {
        val fake = FakeAdbBackend()
        val cmd = "pm compile -m speed-profile -f com.nuvio.tv"
        fake.on(cmd) { listOf("Success") to 0 }
        val events = CompileRunner(fake).run(pkg, CompileMode.SPEED_PROFILE).toList()
        assertEquals(CompileEvent.Attempt(cmd), events.first())
        assertTrue(events.last() is CompileEvent.Finished)
    }

    @Test fun runsOnAnyCommandRunner() = runTest {
        val seen = mutableListOf<String>()
        val runner = object : CommandRunner {
            override fun shell(command: String) = flowOf<ShellEvent>(ShellEvent.Line("Success"), ShellEvent.Exit(0))
                .also { seen += command }
        }
        val last = CompileRunner(runner).run(pkg, CompileMode.SPEED).toList().last()
        assertEquals(CompileEvent.Finished(true, false, CompileOutcome.Success), last)
        assertEquals(listOf(primary), seen)
    }
}
