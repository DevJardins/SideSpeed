package io.github.devjardins.sidespeed.helper

/** The shell command that starts the helper as the shell user. Run from `adb shell`; also shown on screen. */
object HelperLaunch {
    fun command(): String =
        "P=\$(pm path io.github.devjardins.sidespeed | cut -d: -f2); " +
            "CLASSPATH=\$P setsid nohup app_process /system/bin --nice-name=sidespeed_helper " +
            "io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P </dev/null >/dev/null 2>&1 &"
}
