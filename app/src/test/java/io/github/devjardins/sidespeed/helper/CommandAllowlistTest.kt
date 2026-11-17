package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandAllowlistTest {
    @Test fun pmCompileForms() {
        assertEquals(
            listOf("/system/bin/pm", "compile", "-m", "speed", "-f", "com.nuvio.tv"),
            CommandAllowlist.toArgv("pm compile -m speed -f com.nuvio.tv"),
        )
        // The self-compile command for our own package is allowed too.
        assertEquals(
            listOf("/system/bin/pm", "compile", "-m", "speed", "-f", "io.github.devjardins.sidespeed"),
            CommandAllowlist.toArgv("pm compile -m speed -f io.github.devjardins.sidespeed"),
        )
        assertEquals(
            listOf("/system/bin/pm", "compile", "-m", "speed-profile", "-f", "com.nuvio.tv"),
            CommandAllowlist.toArgv("pm compile -m speed-profile -f com.nuvio.tv"),
        )
    }

    @Test fun cmdPackageCompileForms() {
        assertEquals(
            listOf("/system/bin/cmd", "package", "compile", "-m", "speed", "-f", "com.nuvio.tv"),
            CommandAllowlist.toArgv("cmd package compile -m speed -f com.nuvio.tv"),
        )
        assertEquals(
            listOf("/system/bin/cmd", "package", "compile", "-m", "speed-profile", "-f", "org.xbmc.kodi"),
            CommandAllowlist.toArgv("cmd package compile -m speed-profile -f org.xbmc.kodi"),
        )
    }

    @Test fun dumpsysForms() {
        assertEquals(listOf("/system/bin/dumpsys", "package", "dexopt"), CommandAllowlist.toArgv("dumpsys package dexopt"))
        assertEquals(
            listOf("/system/bin/dumpsys", "package", "com.nuvio.tv"),
            CommandAllowlist.toArgv("dumpsys package com.nuvio.tv"),
        )
    }

    @Test fun pingIsAnsweredByTheHelperNotExecuted() {
        assertNull(CommandAllowlist.toArgv("ping"))
        assertEquals(true, CommandAllowlist.isPing("ping"))
        assertEquals(false, CommandAllowlist.isPing("ping "))
        assertEquals(false, CommandAllowlist.isPing("ping x"))
    }

    @Test fun pingWithNonce() {
        val ok = "abcdefghijklmnop1234"
        assertEquals(true, CommandAllowlist.isPing("ping $ok"))
        assertEquals(ok, CommandAllowlist.pingNonce("ping $ok"))
        assertEquals("A".repeat(16), CommandAllowlist.pingNonce("ping " + "A".repeat(16)))
        assertEquals("z9".repeat(32), CommandAllowlist.pingNonce("ping " + "z9".repeat(32)))
        assertNull(CommandAllowlist.pingNonce("ping"))
        assertNull(CommandAllowlist.toArgv("ping $ok"))
        listOf(
            "ping short",
            "ping bad;chars",
            "ping bad;charsbad;chars",
            "ping " + "a".repeat(65),
            "ping " + "a".repeat(15),
            "ping  $ok",
            "ping $ok ",
            "ping $ok x",
            "ping abcdefghijklmnop-123",
            "ping abcdefghijklmnop_123",
            "ping abcdefghijklmnöp123",
            "PING $ok",
        ).forEach {
            assertEquals(it, false, CommandAllowlist.isPing(it))
            assertNull(it, CommandAllowlist.pingNonce(it))
            assertNull(it, CommandAllowlist.toArgv(it))
        }
    }

    @Test fun briefRejections() {
        listOf(
            "pm compile -m speed -f com.foo;reboot",
            "pm uninstall com.foo",
            "dumpsys package com.foo bar",
            "pm compile -m everything -f com.foo",
            "",
            "sh -c id",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun whitespaceAndMetacharactersRejected() {
        listOf(
            " pm compile -m speed -f com.foo",
            "pm compile -m speed -f com.foo ",
            "pm  compile -m speed -f com.foo",
            "pm compile -m speed -f  com.foo",
            "pm\tcompile -m speed -f com.foo",
            "pm compile -m speed -f com.foo\n",
            "pm compile -m speed -f com.foo\r",
            "pm compile -m speed -f com.foo -f com.bar",
            "pm compile -m speed -f com.foo extra",
            "pm compile -f com.foo -m speed",
            "pm compile -m speed com.foo",
            "pm compile -m speed -f com.foo&&id",
            "pm compile -m speed -f \$(id)",
            "pm compile -m speed -f `id`",
            "pm compile -m speed -f com.foo|id",
            "pm compile -m speed -f com.foo>x",
            "pm compile -m speed -f com.foo'",
            "pm compile -m speed -f nodots",
            "pm compile -m SPEED -f com.foo",
            "PM compile -m speed -f com.foo",
            "/system/bin/pm compile -m speed -f com.foo",
            "pm compile -m speed -f com.föo",
            "pm compile -m speed -f com.foo\u0000",
            "cmd package compile -m speed -f com.foo;id",
            "cmd package uninstall com.foo",
            "dumpsys package",
            "dumpsys package dexopt extra",
            "dumpsys activity",
            "dumpsys package -h",
            "dumpsys package com.foo;id",
            "dumpsys  package dexopt",
            "settings put global adb_enabled 0",
            "id",
            "ping x",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun overlongRejected() {
        val pkg = "com." + "a".repeat(600)
        assertNull(CommandAllowlist.toArgv("dumpsys package $pkg"))
    }
}
