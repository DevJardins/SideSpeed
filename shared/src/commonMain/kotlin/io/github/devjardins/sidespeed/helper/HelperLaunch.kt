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
    fun killCommand(pkg: String): String {
        val paths = HelperPaths.of(pkg)
        return "kill \$(pidof ${paths.processName})"
    }

    fun command(pkg: String): String {
        val paths = HelperPaths.of(pkg)
        return "trap \"\" HUP; P=\$(pm path $pkg | cut -d: -f2); " +
            "CLASSPATH=\$P setsid nohup app_process /system/bin --nice-name=${paths.processName} " +
            "io.github.devjardins.sidespeed.helper.HelperMain --apk=\$P --pkg=$pkg </dev/null >/dev/null 2>&1 &"
    }
}
