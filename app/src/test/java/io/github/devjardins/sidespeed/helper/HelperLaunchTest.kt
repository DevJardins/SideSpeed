package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperLaunchTest {
    @Test fun exactCommand() = assertEquals(
        "P=\$(pm path io.github.devjardins.sidespeed | cut -d: -f2); CLASSPATH=\$P setsid nohup app_process " +
            "/system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P " +
            "</dev/null >/dev/null 2>&1 &",
        HelperLaunch.command(),
    )

    @Test fun namesTheRealMainClass() =
        assertEquals(true, HelperLaunch.command().contains(" ${HelperMain::class.java.name} "))
}
