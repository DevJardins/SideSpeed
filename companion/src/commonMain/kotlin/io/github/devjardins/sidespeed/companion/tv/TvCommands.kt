package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.HelperProtocol
import io.github.devjardins.sidespeed.helper.SideSpeedPackages

/**
 * The only shell commands the companion ever sends to a TV. The TV trusts the companion's key, so nothing
 * else is built from outside input. A parameter is a pid, which must be positive, or checked hex:
 * a 16-character companion id, or 64 characters of secret or nonce.
 */
object TvCommands {
    private val HEX = Regex("^[0-9a-f]+$")

    /** Directory on the TV that holds one secret file per companion. Shell-owned, mode 0700. */
    const val IDENTITY_DIR = "/data/local/tmp/.sidespeed-id"

    /**
     * Creates [IDENTITY_DIR] with umask 077 (directory 0700, file 0600), writes [secretHex] to a temp file,
     * then moves it onto this companion's file. Both arguments are checked hex; nothing else is inserted.
     */
    fun writeSecret(companionId: String, secretHex: String): String {
        requireHex(companionId, 16)
        requireHex(secretHex, 64)
        val tmp = "$IDENTITY_DIR/$companionId.tmp"
        val dest = "$IDENTITY_DIR/$companionId"
        return "umask 077 && mkdir -p $IDENTITY_DIR && printf '%s' $secretHex > $tmp && mv -f $tmp $dest"
    }

    /**
     * Prints the hash of this companion's secret file followed by [nonceHex]. Uses `sha256sum` when the TV
     * has it, otherwise `sha1sum`. The answer's length says which one ran.
     */
    fun proveSecret(companionId: String, nonceHex: String): String {
        requireHex(companionId, 16)
        requireHex(nonceHex, 64)
        val file = "$IDENTITY_DIR/$companionId"
        return "if command -v sha256sum >/dev/null 2>&1; then h=sha256sum; else h=sha1sum; fi; " +
            "{ cat $file && printf '%s' $nonceHex; } | \$h"
    }

    /** Removes this companion's secret file. Other companions' files in the directory are left alone. */
    fun forgetSecret(companionId: String): String {
        requireHex(companionId, 16)
        return "rm -f $IDENTITY_DIR/$companionId"
    }

    private fun requireHex(value: String, length: Int) {
        require(value.length == length && HEX.matches(value)) { "expected $length lowercase hex characters" }
    }

    const val SERIAL = "getprop ro.serialno"
    const val PKG_PATH = "pm path io.github.devjardins.sidespeed"
    const val PIDOF = "pidof sidespeed_helper"

    /** `pid` is an Int, so it can only ever be digits; zero and negatives are refused. */
    fun cmdline(pid: Int): String {
        require(pid > 0) { "pid must be positive" }
        return "cat /proc/$pid/cmdline"
    }

    /**
     * The helper's own startup line for [pid] in its log. Some boxes (the Fire TV Cube) show only the process
     * name in /proc/<pid>/cmdline, so the install a helper runs from is read from here instead.
     */
    fun startupLine(pid: Int): String {
        require(pid > 0) { "pid must be positive" }
        return "grep \"\\[pid $pid\\] startup: uid\" ${HelperProtocol.LOG_FILE}"
    }

    val KILL: String = HelperLaunch.killCommand(SideSpeedPackages.RELEASE)
    val LAUNCH: String = HelperLaunch.command(SideSpeedPackages.RELEASE)

    /** Live compile status for the TV card; parsed by `parseActivityRow` in `TvActivity.kt`. */
    const val STATUS_QUERY = "content query --uri content://io.github.devjardins.sidespeed.status/now"

    // Add/scan only.
    const val MANUFACTURER = "getprop ro.product.manufacturer"
    const val MODEL = "getprop ro.product.model"
    const val OS_VERSION = "getprop ro.build.version.name"
}
