package io.github.devjardins.sidespeed.helper

import kotlin.test.Test
import kotlin.test.assertEquals

class HelperLaunchTest {
    @Test fun exactCommand() = assertEquals(
        "trap \"\" HUP; P=\$(pm path io.github.devjardins.sidespeed | cut -d: -f2); CLASSPATH=\$P setsid nohup app_process " +
            "/system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P " +
            "</dev/null >/dev/null 2>&1 &",
        HelperLaunch.command(),
    )

    /** libadb runs commands over the legacy PTY shell; without ignoring SIGHUP first the helper dies when the session closes (seen on the onn). */
    @Test fun ignoresHangupBeforeBackgrounding() =
        assertEquals(true, HelperLaunch.command().startsWith("trap \"\" HUP; "))
}
