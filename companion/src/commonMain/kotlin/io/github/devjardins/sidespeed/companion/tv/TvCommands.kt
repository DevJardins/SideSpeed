package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.helper.HelperLaunch
import io.github.devjardins.sidespeed.helper.HelperProtocol
import io.github.devjardins.sidespeed.helper.PackageNames
import io.github.devjardins.sidespeed.helper.SideSpeedPackages

/** Parsed output from `adb shell content call --uri .../status/now`. */
sealed interface ContentCallResult {
    data class Ok(val queued: Int, val reqSeq: Long) : ContentCallResult
    data class Error(val error: String?, val reqSeq: Long = 0L) : ContentCallResult
    data object Unsupported : ContentCallResult
}

/** Installed package metadata extracted from `dumpsys package <pkg>`. */
data class InstalledPackageInfo(
    val versionCode: Long,
    val versionName: String?,
    val installerPackage: String?,
    val isSystem: Boolean,
) {
    val isStoreInstalled: Boolean get() = installerPackage in PackageNames.STORE_INSTALLERS
}

/**
 * The only shell commands the companion ever sends to a TV. The TV trusts the companion's key, so nothing
 * else is built from outside input. Parameters are validated explicitly (pid, checked hex, or [PackageNames.isValid]).
 */
object TvCommands {
    private val HEX = Regex("^[0-9a-f]+$")
    private const val STATUS_URI = "content://io.github.devjardins.sidespeed.status/now"

    private object Tokens {
        const val CMD_PM_PATH_PREFIX = "pm path "
        const val CMD_DUMPSYS_PACKAGE_PREFIX = "dumpsys package "
        const val BUNDLE_PREFIX = "Bundle[{"
        const val BUNDLE_SUFFIX = "}]"
        const val RESULT_NULL = "Result: null"
        const val UNSUPPORTED_OP = "UnsupportedOperationException"
    }

    private val NO_RESULT_PATTERN = Regex("""^No\s+result\s+found\.$""")
    private val PACKAGE_HEADER_PATTERN = Regex("""^\s*Package\s+\[""")
    private val PACKAGES_SECTION_PATTERN = Regex("""^\s*Packages:\s*$""")
    private val HIDDEN_SYSTEM_PATTERN = Regex("""^\s*Hidden\s+system\s+packages:""")
    private val VERSION_CODE_PATTERN = Regex("""\bversionCode=(\d+)\b""")
    private val VERSION_NAME_PATTERN = Regex("""\bversionName=(\S+)""")
    private val FLAGS_PATTERN = Regex("""\b(?:pkgFlags|flags)=\[\s*([^\]]*)\]""")
    private val INSTALLER_PATTERN = Regex("""^(?:installerPackageName|installingPackageName|initiatingPackageName)=(\S+)$""")

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

    private fun requirePackage(pkg: String) {
        require(PackageNames.isValid(pkg)) { "invalid package name" }
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
    const val STATUS_QUERY = "content query --uri $STATUS_URI"

    // Pre-flight device properties and package state.
    const val PROP_ABILIST = "getprop ro.product.cpu.abilist"
    const val PROP_ABI = "getprop ro.product.cpu.abi"
    const val PROP_DENSITY = "getprop ro.sf.lcd_density"
    const val PROP_LOCALE = "getprop persist.sys.locale"
    const val PROP_LOCALE_FALLBACK = "getprop ro.product.locale"

    const val CALL_OPTIMIZE_NOW = "content call --uri $STATUS_URI --method optimize_now"

    private val SESSION_ID = Regex("^[0-9]{1,10}$")

    /** Arguments for `cmd package install-abandon`, run on a fresh session to drop a half-written install session. */
    fun installAbandonArgs(sessionId: String): Array<String> {
        require(SESSION_ID.matches(sessionId)) { "invalid install session id" }
        return arrayOf("install-abandon", sessionId)
    }

    fun packagePath(pkg: String): String {
        requirePackage(pkg)
        return "${Tokens.CMD_PM_PATH_PREFIX}$pkg"
    }

    fun packageDump(pkg: String): String {
        requirePackage(pkg)
        return "${Tokens.CMD_DUMPSYS_PACKAGE_PREFIX}$pkg"
    }

    fun callCompilePackage(pkg: String, keepOptimized: Boolean): String {
        requirePackage(pkg)
        val keepFlag = if (keepOptimized) "true" else "false"
        return "content call --uri $STATUS_URI --method compile_package --arg $pkg --extra keep:b:$keepFlag"
    }

    /** Parses `adb shell content call` stdout; returns null when output is unparseable. */
    fun parseContentCallResult(output: String): ContentCallResult? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        if (isProviderMissing(output) ||
            output.contains(Tokens.UNSUPPORTED_OP) ||
            trimmed == Tokens.RESULT_NULL ||
            NO_RESULT_PATTERN.matches(trimmed)
        ) {
            return ContentCallResult.Unsupported
        }
        val start = output.indexOf(Tokens.BUNDLE_PREFIX)
        if (start < 0) return null
        val end = output.indexOf(Tokens.BUNDLE_SUFFIX, start + Tokens.BUNDLE_PREFIX.length)
        if (end < 0) return null
        val body = output.substring(start + Tokens.BUNDLE_PREFIX.length, end)
        val map = LinkedHashMap<String, String>()
        if (body.isNotBlank()) {
            for (pair in body.split(", ")) {
                val cut = pair.indexOf('=')
                if (cut > 0) {
                    map[pair.substring(0, cut).trim()] = pair.substring(cut + 1).trim()
                }
            }
        }
        val ok = map["ok"]?.toBooleanStrictOrNull() ?: return null
        val reqSeq = map["req_seq"]?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L
        if (ok) {
            val queued = map["queued"]?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
            return ContentCallResult.Ok(queued = queued, reqSeq = reqSeq)
        }
        val error = map["error"]?.takeIf { it.isNotEmpty() }
        return ContentCallResult.Error(error = error, reqSeq = reqSeq)
    }

    /** Parses the active package block of `dumpsys package <pkg>`; returns null when absent. */
    fun parsePackageDump(output: String): InstalledPackageInfo? {
        if (output.isBlank()) return null
        val lines = output.lines()
        val packagesStart = lines.indexOfFirst { PACKAGES_SECTION_PATTERN.matches(it) }
        val searchFrom = if (packagesStart >= 0) packagesStart + 1 else 0
        var blockStart = -1
        for (i in searchFrom until lines.size) {
            val line = lines[i]
            if (HIDDEN_SYSTEM_PATTERN.matches(line)) break
            // A new top-level section before any Package block: the match would be outside Packages.
            if (line.isNotEmpty() && !line[0].isWhitespace() && line.trim().endsWith(":")) break
            if (PACKAGE_HEADER_PATTERN.containsMatchIn(line)) {
                blockStart = i
                break
            }
        }
        if (blockStart < 0) return null

        var versionCode: Long? = null
        var versionName: String? = null
        var isSystem = false
        val installers = ArrayList<String>()

        for (i in blockStart + 1 until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (HIDDEN_SYSTEM_PATTERN.matches(line) || PACKAGE_HEADER_PATTERN.containsMatchIn(line)) break
            if (line.isNotEmpty() && !line[0].isWhitespace() && trimmed.endsWith(":")) break

            if (versionCode == null) {
                VERSION_CODE_PATTERN.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()?.let {
                    versionCode = it
                }
            }
            if (versionName == null) {
                if (trimmed.startsWith("versionName=")) {
                    versionName = trimmed.removePrefix("versionName=").trim()
                        .takeIf { it.isNotEmpty() && it != "null" }
                } else {
                    VERSION_NAME_PATTERN.find(trimmed)?.groupValues?.get(1)
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { versionName = it }
                }
            }
            FLAGS_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { flagBody ->
                val tokens = flagBody.trim().split(Regex("\\s+"))
                if ("SYSTEM" in tokens || "UPDATED_SYSTEM_APP" in tokens) {
                    isSystem = true
                }
            }
            INSTALLER_PATTERN.find(trimmed)?.groupValues?.get(1)?.let { candidate ->
                if (candidate != "null" && PackageNames.isValid(candidate)) {
                    installers += candidate
                }
            }
        }

        val resolvedCode = versionCode ?: return null
        val installer = installers.firstOrNull { it in PackageNames.STORE_INSTALLERS }
            ?: installers.firstOrNull()
        return InstalledPackageInfo(
            versionCode = resolvedCode,
            versionName = versionName,
            installerPackage = installer,
            isSystem = isSystem,
        )
    }

    // Add/scan only.
    const val MANUFACTURER = "getprop ro.product.manufacturer"
    const val MODEL = "getprop ro.product.model"
    const val OS_VERSION = "getprop ro.build.version.name"
}
