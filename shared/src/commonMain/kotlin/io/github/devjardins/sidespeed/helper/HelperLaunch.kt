package io.github.devjardins.sidespeed.helper

/**
 * The shell command that starts the helper as the shell user. Run from `adb shell`; also shown on screen.
 *
 * It starts with `trap "" HUP` because libadb runs commands over the legacy PTY shell: when that session
 * closes, the hang-up can reach the backgrounded job before `setsid`/`nohup` take effect, killing the helper
 * before it starts (reproduced on the onn 4K Pro). An ignored SIGHUP is inherited, so there is no race.
 * No single quotes: the PC command wraps this in single quotes.
 */
object HelperLaunch {
    /** Stops a running helper (matched by its process name). */
    const val KILL_COMMAND = "kill \$(pidof sidespeed_helper)"

    fun command(): String =
        "trap \"\" HUP; P=\$(pm path io.github.devjardins.sidespeed | cut -d: -f2); " +
            "CLASSPATH=\$P setsid nohup app_process /system/bin --nice-name=sidespeed_helper " +
            "io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P </dev/null >/dev/null 2>&1 &"
}
