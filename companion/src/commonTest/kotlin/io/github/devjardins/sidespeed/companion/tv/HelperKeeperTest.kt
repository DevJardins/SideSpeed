package io.github.devjardins.sidespeed.companion.tv

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HelperKeeperTest {
    private val apk = "/data/app/x/base.apk"
    private val allowed = Regex("cat /proc/\\d+/cmdline")
    private val constants = setOf(
        TvCommands.SERIAL, TvCommands.PKG_PATH, TvCommands.PIDOF, TvCommands.KILL, TvCommands.LAUNCH,
        TvCommands.MANUFACTURER, TvCommands.MODEL, TvCommands.OS_VERSION,
    )

    private fun cmdline(path: String) = "app_process\u0000/system/bin\u0000--apk=$path\u0000--x"

    private fun base(pidof: String, extra: Map<String, String> = emptyMap()) = FakeTvSession(
        mapOf(
            TvCommands.SERIAL to "SER1",
            TvCommands.PKG_PATH to "package:$apk",
            TvCommands.PIDOF to pidof,
            TvCommands.KILL to "",
            TvCommands.LAUNCH to "",
        ) + extra,
    )

    private fun assertOnlyFixed(s: FakeTvSession) =
        s.sent.forEach { assertTrue(it in constants || allowed.matches(it), "unexpected command: $it") }

    /** Wraps a fake so that PIDOF starts returning [pid] after [after] polls following LAUNCH. */
    private class Appearing(val fake: FakeTvSession, val pid: String, val after: Int) : TvSession by fake {
        var launched = false
        var polls = 0
        override suspend fun run(command: String): String {
            if (command == TvCommands.LAUNCH) launched = true
            if (command == TvCommands.PIDOF && launched) {
                polls++
                fake.outputs[TvCommands.PIDOF] = if (polls >= after) pid else ""
            }
            return fake.run(command)
        }
    }

    @Test fun serialMismatchIsWrongDevice() = runTest {
        val s = base("")
        assertEquals(VisitResult.WrongDevice, HelperKeeper().visit(s, "OTHER"))
        assertEquals(listOf(TvCommands.SERIAL), s.sent)
    }

    @Test fun emptyPmPathIsNotInstalled() = runTest {
        val s = base("").also { it.outputs[TvCommands.PKG_PATH] = "" }
        assertEquals(VisitResult.NotInstalled, HelperKeeper().visit(s, "SER1"))
        assertEquals(listOf(TvCommands.SERIAL, TvCommands.PKG_PATH), s.sent)
    }

    @Test fun runningAndCurrent() = runTest {
        val s = base("123", mapOf(TvCommands.cmdline(123) to cmdline(apk)))
        assertEquals(VisitResult.Running, HelperKeeper().visit(s, "SER1"))
        assertTrue(TvCommands.KILL !in s.sent && TvCommands.LAUNCH !in s.sent)
        assertOnlyFixed(s)
    }

    @Test fun notRunningIsStarted() = runTest {
        val s = base("")
        val t = Appearing(s, "99", after = 3)
        assertEquals(VisitResult.Started, HelperKeeper().visit(t, "SER1"))
        assertEquals(1, s.sent.count { it == TvCommands.LAUNCH })
        val afterLaunch = s.sent.dropWhile { it != TvCommands.LAUNCH }.drop(1)
        assertEquals(3, afterLaunch.size)
        assertTrue(afterLaunch.all { it == TvCommands.PIDOF })
        assertTrue(TvCommands.KILL !in s.sent)
        assertOnlyFixed(s)
    }

    @Test fun staleIsRestarted() = runTest {
        val s = base("123", mapOf(TvCommands.cmdline(123) to cmdline("/data/app/OLD/base.apk")))
        val t = Appearing(s, "124", after = 1)
        assertEquals(VisitResult.Restarted, HelperKeeper().visit(t, "SER1"))
        assertTrue(s.sent.indexOf(TvCommands.KILL) < s.sent.indexOf(TvCommands.LAUNCH))
        assertOnlyFixed(s)
    }

    @Test fun missingApkArgIsStale() = runTest {
        val s = base("123", mapOf(TvCommands.cmdline(123) to "app_process\u0000--x"))
        val t = Appearing(s, "124", after = 1)
        assertEquals(VisitResult.Restarted, HelperKeeper().visit(t, "SER1"))
    }

    @Test fun twoPidsOneStale() = runTest {
        val s = base(
            "123 456",
            mapOf(
                TvCommands.cmdline(123) to cmdline(apk),
                TvCommands.cmdline(456) to cmdline("/data/app/OLD/base.apk"),
            ),
        )
        val t = Appearing(s, "789", after = 1)
        assertEquals(VisitResult.Restarted, HelperKeeper().visit(t, "SER1"))
        assertTrue(TvCommands.cmdline(123) in s.sent && TvCommands.cmdline(456) in s.sent)
        assertOnlyFixed(s)
    }

    @Test fun garbagePidIsTreatedAsNotRunning() = runTest {
        val s = base("12a")
        val t = Appearing(s, "5", after = 1)
        assertEquals(VisitResult.Started, HelperKeeper().visit(t, "SER1"))
        assertTrue(s.sent.none { allowed.matches(it) })
        assertOnlyFixed(s)
        assertFailsWith<IllegalArgumentException> { TvCommands.cmdline(0) }
        assertFailsWith<IllegalArgumentException> { TvCommands.cmdline(-1) }
    }

    @Test fun splitApksUseBaseApk() = runTest {
        val s = base("123", mapOf(TvCommands.cmdline(123) to cmdline(apk)))
        s.outputs[TvCommands.PKG_PATH] = "package:/data/app/x/split_config.apk\npackage:$apk"
        assertEquals(VisitResult.Running, HelperKeeper().visit(s, "SER1"))
    }

    @Test fun launchThatNeverAppearsFails() = runTest {
        val s = base("")
        val result = HelperKeeper().visit(s, "SER1")
        assertIs<VisitResult.StartFailed>(result)
        assertEquals(1, s.sent.count { it == TvCommands.LAUNCH })
        assertOnlyFixed(s)
    }
}
