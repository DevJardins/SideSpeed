package io.github.devjardins.sidespeed.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperLaunchTest {
    @Test fun releaseCommand() = assertEquals(
        "trap \"\" HUP; P=\$(pm path io.github.devjardins.sidespeed | cut -d: -f2); CLASSPATH=\$P setsid nohup app_process " +
            "/system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P " +
            "--pkg=io.github.devjardins.sidespeed </dev/null >/dev/null 2>&1 &",
        HelperLaunch.command(SideSpeedPackages.RELEASE),
    )

    @Test fun debugCommand() = assertEquals(
        "trap \"\" HUP; P=\$(pm path io.github.devjardins.sidespeed.debug | cut -d: -f2); CLASSPATH=\$P setsid nohup app_process " +
            "/system/bin --nice-name=sidespeed_helper_debug io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P " +
            "--pkg=io.github.devjardins.sidespeed.debug </dev/null >/dev/null 2>&1 &",
        HelperLaunch.command(SideSpeedPackages.DEBUG),
    )

    /** libadb runs commands over the legacy PTY shell; without ignoring SIGHUP first the helper dies when the session closes (seen on the onn). */
    @Test fun ignoresHangupAndHasNoSingleQuotes() {
        for (pkg in SideSpeedPackages.ALL) {
            val cmd = HelperLaunch.command(pkg)
            assertTrue(cmd.startsWith("trap \"\" HUP; "), pkg)
            assertFalse(cmd.contains('\''), pkg)
        }
    }

    @Test fun killCommandsMatchProcessName() {
        assertEquals("kill \$(pidof sidespeed_helper)", HelperLaunch.killCommand(SideSpeedPackages.RELEASE))
        assertEquals("kill \$(pidof sidespeed_helper_debug)", HelperLaunch.killCommand(SideSpeedPackages.DEBUG))
    }
}
