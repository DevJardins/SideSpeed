package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun trimCachesForm() {
        assertEquals(
            listOf("/system/bin/pm", "trim-caches", "1000000000"),
            CommandAllowlist.toArgv("pm trim-caches 1000000000"),
        )
        assertEquals(
            listOf("/system/bin/pm", "trim-caches", "5"),
            CommandAllowlist.toArgv("pm trim-caches 5"),
        )
        listOf(
            "pm trim-caches 1G",
            "pm trim-caches -1",
            "pm trim-caches",
            "pm trim-caches 1234567890123456",
            "pm trim-caches 100 extra",
            "pm trim-caches 1.5",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun settingsForms() {
        assertEquals(
            listOf("/system/bin/settings", "get", "global", "window_animation_scale"),
            CommandAllowlist.toArgv("settings get global window_animation_scale"),
        )
        assertEquals(
            listOf("/system/bin/settings", "put", "global", "window_animation_scale", "0.5"),
            CommandAllowlist.toArgv("settings put global window_animation_scale 0.5"),
        )
        assertEquals(
            listOf("/system/bin/settings", "put", "global", "transition_animation_scale", "1.5"),
            CommandAllowlist.toArgv("settings put global transition_animation_scale 1.5"),
        )
        assertEquals(
            listOf("/system/bin/settings", "put", "global", "animator_duration_scale", "10"),
            CommandAllowlist.toArgv("settings put global animator_duration_scale 10"),
        )
        // Restore path: any value `settings get` can plausibly return must pass `put`.
        listOf("0.333", "12", "1.5", "0.000001", "11", "10.01", "100", "0", "1").forEach { value ->
            assertEquals(
                listOf("/system/bin/settings", "put", "global", "window_animation_scale", value),
                CommandAllowlist.toArgv("settings put global window_animation_scale $value"),
            )
        }
        listOf(
            "settings put global window_animation_scale -1",
            "settings put global window_animation_scale 1e3",
            "settings put global window_animation_scale 1.2.3",
            "settings put global window_animation_scale  1",
            "settings put global window_animation_scale 101",
            "settings put global window_animation_scale 1.0000001",
            "settings put global adb_enabled 0",
            "settings put secure android_id 1",
            "settings get global adb_enabled",
            "settings get secure android_id",
            "settings put global window_animation_scale",
            "settings put global window_animation_scale 0.5 extra",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun verifyCompileForm() {
        assertEquals(
            listOf("/system/bin/pm", "compile", "-m", "verify", "-f", "com.nuvio.tv"),
            CommandAllowlist.toArgv("pm compile -m verify -f com.nuvio.tv"),
        )
        assertEquals(
            listOf("/system/bin/cmd", "package", "compile", "-m", "verify", "-f", "com.nuvio.tv"),
            CommandAllowlist.toArgv("cmd package compile -m verify -f com.nuvio.tv"),
        )
    }

    @Test fun launchTimeForm() {
        assertEquals(
            listOf("/system/bin/am", "start", "-W", "-S", "-n", "com.x.y/com.x.y.Main"),
            CommandAllowlist.toArgv("launch-time com.x.y com.x.y.Main"),
        )
        listOf(
            "launch-time com.x.y ../evil",
            "launch-time com.x.y",
            "launch-time com.x.y com.x.y.Main extra",
            "launch-time nodots com.x.y.Main",
            "launch-time com.x.y .Main",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun launchTimeNestedClassForm() {
        assertEquals(
            listOf("/system/bin/am", "start", "-W", "-S", "-n", "com.foo/com.foo.Main\$Launcher"),
            CommandAllowlist.toArgv("launch-time com.foo com.foo.Main\$Launcher"),
        )
        assertEquals(
            listOf("/system/bin/am", "start", "-W", "-S", "-n", "com.foo/com.foo.Outer\$Inner\$Deep"),
            CommandAllowlist.toArgv("launch-time com.foo com.foo.Outer\$Inner\$Deep"),
        )
    }

    @Test fun dollarRejectedEverywhereExceptLaunchTimeClass() {
        listOf(
            "pm compile -m speed -f com.foo\$M",
            "pm compile -m verify -f com.foo\$Bar",
            "cmd package compile -m speed -f com.foo\$M",
            "dumpsys package com.foo\$Bar",
            "launch-time com.fo\$o com.foo.Main",
            "launch-time com.foo\$Bar com.foo.Main",
            "launch-time com.foo com.foo.Main\$",
            "launch-time com.foo \$a.B",
            "launch-time com.foo a..B",
            "launch-time com.foo a.B\$\$C",
            "launch-time com.foo a.B;rm",
            "launch-time com.foo com.foo.Main\$1",
            "launch-time com.foo a.B c",
            "launch-time com.foo com.foo.Main extra",
            "pm trim-caches 10\$0",
            "settings put global window_animation_scale 0.5\$",
            "settings put global window_animation_scale\$ 0.5",
            "settings get global window_animation_scale\$",
            "install-self 5\$",
            "bring-back \$",
            "force-stop com.foo\$Bar",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
        assertNull(CommandAllowlist.installSelfSize("install-self 5\$"))
        assertEquals(false, CommandAllowlist.isPing("ping abcdefghijklmnop\$23"))
        assertNull(CommandAllowlist.pingNonce("ping abcdefghijklmnop\$23"))
    }

    @Test fun install_self_size_parses_bounds() {
        assertEquals(1L, CommandAllowlist.installSelfSize("install-self 1"))
        assertEquals(67108864L, CommandAllowlist.installSelfSize("install-self 67108864"))
        listOf(
            "install-self 0",
            "install-self 67108865",
            "install-self 01",
            "install-self -1",
            "install-self",
            "install-self 1 2",
            "install-self 1 ",
            "install-self +1",
            "install-self 0x10",
        ).forEach { assertNull(it, CommandAllowlist.installSelfSize(it)) }
    }

    @Test fun install_update_parses_package_and_size() {
        assertEquals("com.nuvio.tv" to 5L, CommandAllowlist.installUpdate("install-update com.nuvio.tv 5"))
        assertEquals(
            "com.nuvio.tv" to 209_715_200L,
            CommandAllowlist.installUpdate("install-update com.nuvio.tv 209715200"),
        )
        assertEquals("com.foo.bar" to 1L, CommandAllowlist.installUpdate("install-update com.foo.bar 1"))
    }

    @Test fun install_update_rejections() {
        listOf(
            "install-update",
            "install-update com.nuvio.tv",
            "install-update com.nuvio.tv 5 extra",
            "install-update com.nuvio.tv 5 ",
            " install-update com.nuvio.tv 5",
            "install-update  com.nuvio.tv 5",
            "install-update com.nuvio.tv  5",
            "install-update com.nuvio.tv 0",
            "install-update com.nuvio.tv 209715201",
            "install-update com.nuvio.tv 9999999999",
            "install-update com.nuvio.tv +5",
            "install-update com.nuvio.tv -5",
            "install-update com.nuvio.tv 0x10",
            "install-update com.nuvio.tv 5.0",
            "install-update com.nuvio.tv five",
            "install-update com.nuvio.tv 5\$",
            "install-update nodots 5",
            "install-update  com.nuvio.tv 5",
            "install-update com.nuvio.tv",
            "pm install-update com.nuvio.tv 5",
            "install-self 5",
            "",
        ).forEach { assertNull(it, CommandAllowlist.installUpdate(it)) }
    }

    @Test fun install_update_refuses_self_package() {
        assertNull(CommandAllowlist.installUpdate("install-update io.github.devjardins.sidespeed 10"))
        assertNull(CommandAllowlist.installUpdate("install-update io.github.devjardins.sidespeed 209715200"))
    }

    @Test fun install_update_argv_is_reinstall_without_danger_flags() {
        val argv = CommandAllowlist.installUpdateArgv("com.nuvio.tv", 5)
        assertEquals(
            listOf("/system/bin/cmd", "package", "install", "-r", "--pkg", "com.nuvio.tv", "-S", "5"),
            argv,
        )
        listOf("-d", "-t", "-g", "--user").forEach { flag ->
            assertTrue("argv must never carry $flag: $argv", flag !in argv)
        }
    }

    @Test fun to_argv_never_maps_install_update() {
        assertNull(CommandAllowlist.toArgv("install-update com.nuvio.tv 5"))
    }

    @Test fun to_argv_never_maps_install_self() {
        assertNull(CommandAllowlist.toArgv("install-self 5"))
        assertNull(CommandAllowlist.toArgv("install-self 67108864"))
        assertEquals(
            listOf(
                "/system/bin/cmd", "package", "install", "-r",
                "--pkg", "io.github.devjardins.sidespeed", "-S", "5",
            ),
            CommandAllowlist.installSelfArgv(5),
        )
    }

    @Test fun setHomeForm() {
        assertEquals(
            listOf("/system/bin/cmd", "package", "set-home-activity", "--user", "0", "com.example.home/com.example.home.MainActivity"),
            CommandAllowlist.toArgv("set-home com.example.home com.example.home.MainActivity"),
        )
        // Nested class: `$` is allowed in the class token only.
        assertEquals(
            listOf("/system/bin/cmd", "package", "set-home-activity", "--user", "0", "com.example.home/com.example.home.Main\$Inner"),
            CommandAllowlist.toArgv("set-home com.example.home com.example.home.Main\$Inner"),
        )
    }

    @Test fun setHomeRejections() {
        listOf(
            "set-home com.example.home",
            "set-home com.example.home com.example.home.MainActivity extra",
            "set-home com.example\$home com.example.home.MainActivity",
            "set-home nodots com.example.home.MainActivity",
            "set-home com.example.home .MainActivity",
            "set-home com.example.home com.example.home.Main\$",
            "set-home com.example.home com.example.home.Main\$1",
            "set-home com.example.home a..B",
            "set-home com.example.home com.example.home.Main;id",
            "set-home com.example.home/com.example.home.MainActivity",
            "cmd package set-home-activity --user 0 com.example.home/com.example.home.MainActivity",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun pmDisableUserForm() {
        assertEquals(
            listOf("/system/bin/pm", "disable-user", "--user", "0", "com.example.bloat"),
            CommandAllowlist.toArgv("pm disable-user --user 0 com.example.bloat"),
        )
    }

    @Test fun pmEnableForm() {
        assertEquals(
            listOf("/system/bin/pm", "enable", "com.example.bloat"),
            CommandAllowlist.toArgv("pm enable com.example.bloat"),
        )
    }

    @Test fun pmListDisabledForm() {
        assertEquals(
            listOf("/system/bin/pm", "list", "packages", "-d", "--user", "0"),
            CommandAllowlist.toArgv("pm list packages -d --user 0"),
        )
    }

    @Test fun pmExtrasRejections() {
        listOf(
            "pm disable-user com.example.bloat",
            "pm disable-user --user 10 com.example.bloat",
            "pm disable-user --user 0",
            "pm disable-user --user 0 com.example.bloat extra",
            "pm disable-user 0 --user com.example.bloat",
            "pm disable com.example.bloat",
            "pm disable-user --user 0 com.example\$bloat",
            "pm disable-user --user 0 nodots",
            "pm enable",
            "pm enable com.example.bloat extra",
            "pm enable --user 0 com.example.bloat",
            "pm enable com.example\$bloat",
            "pm uninstall com.example.bloat",
            "pm uninstall -k --user 0 com.example.bloat",
            "pm list packages --user 0 -d",
            "pm list packages -d --user 10",
            "pm list packages -d",
            "pm list packages --user 0",
            "pm list packages -e --user 0",
            "pm list packages -d --user 0 extra",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun homeRoleForm() {
        assertEquals(
            listOf("/system/bin/cmd", "role", "get-role-holders", "android.app.role.HOME"),
            CommandAllowlist.toArgv("home-role"),
        )
    }

    @Test fun homeRoleRejections() {
        listOf(
            "home-role extra",
            "home-role android.app.role.HOME",
            "home-role android.app.role.DIALER",
            "home-role com.example.home",
            "cmd role get-role-holders android.app.role.HOME",
            "cmd role get-role-holders android.app.role.DIALER",
            "role get-role-holders android.app.role.HOME",
            "home-role ",
            " home-role",
            "home-role  ",
            "HOME-ROLE",
            "home_role",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }

    @Test fun bringBackForm() {
        assertEquals(
            listOf(
                "/system/bin/am", "start",
                "--activity-clear-top", "--activity-single-top",
                "-n", "io.github.devjardins.sidespeed/io.github.devjardins.sidespeed.MainActivity",
            ),
            CommandAllowlist.toArgv("bring-back"),
        )
        assertNull(CommandAllowlist.toArgv("bring-back extra"))
    }

    @Test fun forceStopForm() {
        assertEquals(
            listOf("/system/bin/am", "force-stop", "--user", "0", "com.x.y"),
            CommandAllowlist.toArgv("force-stop com.x.y"),
        )
    }

    @Test fun force_stop_refuses_self_package() {
        assertNull(CommandAllowlist.toArgv("force-stop io.github.devjardins.sidespeed"))
    }

    @Test fun forceStopRejections() {
        listOf(
            "force-stop",
            "force-stop com.x.y extra",
            "force-stop --user 0 com.x.y",
            "force-stop --all",
            "force-stop -a",
            "am force-stop com.x.y",
            "/system/bin/am force-stop com.x.y",
            "/system/bin/am force-stop --user 0 com.x.y",
            "force-stop nodots",
            "force-stop com.foo\$Bar",
            "force-stop com.x.y;id",
            "force-stop com.x.y ",
            " force-stop com.x.y",
            "force-stop  com.x.y",
            "FORCE-STOP com.x.y",
            "force_stop com.x.y",
        ).forEach { assertNull(it, CommandAllowlist.toArgv(it)) }
    }
}
