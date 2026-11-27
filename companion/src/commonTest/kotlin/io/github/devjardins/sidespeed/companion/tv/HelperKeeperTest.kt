package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HelperKeeperTest {
    private val apk = "/data/app/x/base.apk"
    private val allowed = Regex("cat /proc/\\d+/cmdline|grep .* /data/local/tmp/sidespeed_helper\\.log")
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

    private fun startup(pid: Int, path: String) =
        "2026-11-16 09:21:54.790 [pid $pid] startup: uid=2000 pid=$pid apk=$path size=1 mtime=2"

    @Test fun bareCmdlineWithMatchingLogLineIsRunning() = runTest {
        val s = base("123", mapOf(
            TvCommands.cmdline(123) to "sidespeed_helper",
            TvCommands.startupLine(123) to startup(123, apk),
        ))
        assertEquals(VisitResult.Running, HelperKeeper().visit(s, "SER1"))
        assertTrue(TvCommands.KILL !in s.sent && TvCommands.LAUNCH !in s.sent)
        assertOnlyFixed(s)
    }

    @Test fun bareCmdlineWithOldApkInLogIsRestarted() = runTest {
        val s = base("123", mapOf(
            TvCommands.cmdline(123) to "sidespeed_helper",
            TvCommands.startupLine(123) to startup(123, "/data/app/OLD/base.apk"),
        ))
        val t = Appearing(s, "124", after = 1)
        assertEquals(VisitResult.Restarted, HelperKeeper().visit(t, "SER1"))
        assertOnlyFixed(s)
    }

    @Test fun unknownApkIsNotKilled() = runTest {
        val s = base("123", mapOf(
            TvCommands.cmdline(123) to "app_process\u0000--x",
            TvCommands.startupLine(123) to "",
        ))
        assertEquals(VisitResult.Running, HelperKeeper().visit(s, "SER1"))
        assertTrue(TvCommands.KILL !in s.sent && TvCommands.LAUNCH !in s.sent)
        assertOnlyFixed(s)
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
        assertOnlyFixed(s)
    }

    @Test fun blankSerialsAreWrongDevice() = runTest {
        val s = base("").also { it.outputs[TvCommands.SERIAL] = "" }
        assertEquals(VisitResult.WrongDevice, HelperKeeper().visit(s, ""))
        assertEquals(listOf(TvCommands.SERIAL), s.sent)
        val s2 = base("").also { it.outputs[TvCommands.SERIAL] = "" }
        assertEquals(VisitResult.WrongDevice, HelperKeeper().visit(s2, "SER1"))
        assertEquals(listOf(TvCommands.SERIAL), s2.sent)
        val s3 = base("")
        assertEquals(VisitResult.WrongDevice, HelperKeeper().visit(s3, ""))
        assertEquals(listOf(TvCommands.SERIAL), s3.sent)
    }

    @Test fun commandLiteralsArePinned() {
        assertEquals("getprop ro.serialno", TvCommands.SERIAL)
        assertEquals("pm path io.github.devjardins.sidespeed", TvCommands.PKG_PATH)
        assertEquals("pidof sidespeed_helper", TvCommands.PIDOF)
        assertEquals("kill \$(pidof sidespeed_helper)", TvCommands.KILL)
        assertEquals("grep \"\\[pid 123\\] startup: uid\" /data/local/tmp/sidespeed_helper.log", TvCommands.startupLine(123))
        assertEquals("cat /proc/123/cmdline", TvCommands.cmdline(123))
        assertEquals(HelperLaunch.command(SideSpeedPackages.RELEASE), TvCommands.LAUNCH)
        assertEquals("getprop ro.product.manufacturer", TvCommands.MANUFACTURER)
        assertEquals("getprop ro.product.model", TvCommands.MODEL)
        assertEquals("getprop ro.build.version.name", TvCommands.OS_VERSION)
    }

    @Test fun maliciousPidofOutputIsNeverUsed() = runTest {
        for (bad in listOf("123;reboot", "+5", "-1", "\u0661\u0662\u0663", "123 99999999999")) {
            val s = base(bad)
            val t = Appearing(s, "5", after = 1)
            assertEquals(VisitResult.Started, HelperKeeper().visit(t, "SER1"), bad)
            assertTrue(s.sent.none { allowed.matches(it) }, bad)
            assertOnlyFixed(s)
        }
    }

    @Test fun invalidPollSettingsRejected() {
        assertFailsWith<IllegalArgumentException> { HelperKeeper(pollIntervalMs = 0) }
        assertFailsWith<IllegalArgumentException> { HelperKeeper(pollTimeoutMs = -1) }
    }

    @Test fun launchThatNeverAppearsFails() = runTest {
        val s = base("")
        val result = HelperKeeper().visit(s, "SER1")
        assertIs<VisitResult.StartFailed>(result)
        assertEquals(1, s.sent.count { it == TvCommands.LAUNCH })
        assertOnlyFixed(s)
    }
}
