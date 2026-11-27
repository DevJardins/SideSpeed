package io.github.devjardins.sidespeed.helper

import io.github.devjardins.sidespeed.compile.ActivityClassName
import io.github.devjardins.sidespeed.compile.PackageName

/**
 * The only commands the shell-uid helper will run. Matching is exact and strict, with no normalisation:
 * tokens are separated by exactly one ASCII space, with no leading or trailing whitespace, no tabs and no
 * control characters. Anything else is refused. The result is a fixed argv for [ProcessBuilder]; it is never
 * passed to a shell.
 */
object CommandAllowlist {
    const val PING = "ping"
    const val GRANT_REBOOT_RECOVERY = "grant-reboot-recovery"
    const val REVOKE_SECURE_SETTINGS = "revoke-secure-settings"
    const val REVOKE_REBOOT_RECOVERY = "revoke-reboot-recovery"
    const val ADB_TRUSTED_NETWORKS = "adb-trusted-networks"

    private const val PERM_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    private const val PERM_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val PERM_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private const val PERM_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** Byte cap for `install-self`, matching the 64 MiB download cap. */
    const val MAX_INSTALL_BYTES = 67_108_864L

    /** Byte cap for `install-update`: 200 MiB, the largest APK the updater will fetch. */
    const val MAX_UPDATE_BYTES = 209_715_200L

    private val INSTALL_SELF_SIZE_DIGITS = Regex("^[0-9]{1,8}$")

    private val INSTALL_UPDATE_SIZE_DIGITS = Regex("^[0-9]{1,9}$")

    private val MODES = setOf("speed", "speed-profile", "verify")

    private val TRIM_CACHES = Regex("^[0-9]{1,15}$")

    private val ANIMATION_KEYS = setOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    /**
     * Decimal form for animation scales, wide enough for anything `settings get` can return:
     * non-negative, up to 6 fractional digits, at most 100. Still digits and one dot only:
     * no signs, exponents or spaces (leading/trailing/repeated spaces already fail tokenising).
     */
    private val ANIMATION_VALUE = Regex("^(0|[1-9][0-9]{0,2})(\\.[0-9]{1,6})?$")

    /**
     * Characters a valid request can contain at all: letters, digits, '.', '_', '-', '$' and single
     * spaces. `$` is admitted here only so the launch-time class token can reach its own validator;
     * every other command's per-token validation still rejects it.
     */
    private val ALLOWED_CHARS = Regex("^[A-Za-z0-9._ \$-]+$")

    /** A ping nonce: 16 to 64 ASCII letters and digits. */
    private val NONCE = Regex("^[A-Za-z0-9]{16,64}$")

    /**
     * True for `ping` and `ping <nonce>`, which the helper answers itself (nothing is executed).
     * Every request on the wire also carries an outer proof nonce; see [HelperProtocol.ACK_DIR].
     */
    fun isPing(command: String): Boolean = command == PING || pingNonce(command) != null

    /** The nonce of a `ping <nonce>` request, or null (also for plain `ping`). */
    fun pingNonce(command: String): String? {
        if (!command.startsWith("$PING ")) return null
        return command.substring(PING.length + 1).takeIf { NONCE.matches(it) }
    }

    /**
     * The byte count of an `install-self <size>` request (1..[MAX_INSTALL_BYTES]), or null.
     * Rejects 0, over-cap, leading zeros, signs, non-digits, overflow and extra tokens. A plain
     * runner can never execute it: [toArgv] stays null for `install-self`.
     */
    fun installSelfSize(command: String): Long? {
        if (!command.startsWith("install-self ")) return null
        val num = command.removePrefix("install-self ")
        if (!INSTALL_SELF_SIZE_DIGITS.matches(num)) return null
        if (num.length > 1 && num.startsWith("0")) return null
        val size = num.toLongOrNull() ?: return null
        if (size < 1 || size > MAX_INSTALL_BYTES) return null
        return size
    }

    /** Fixed argv for an `install-self` of [size] bytes targeting [selfPkg]; [size] and [selfPkg] are the only variable tokens. */
    fun installSelfArgv(size: Long, selfPkg: String): List<String> {
        require(selfPkg in SideSpeedPackages.ALL) { "unknown SideSpeed package: $selfPkg" }
        return listOf(
            "/system/bin/cmd", "package", "install", "-r",
            "--pkg", selfPkg, "-S", size.toString(),
        )
    }

    /**
     * The package and byte count of an `install-update <pkg> <size>` request (1..[MAX_UPDATE_BYTES]),
     * or null. Exactly 3 tokens; [pkg] passes the same validation as every other package token
     * (exact, untrimmed), and SideSpeed's own packages are refused here: self-updates keep
     * `install-self`. A plain runner can never execute it: [toArgv] stays null for `install-update`.
     */
    fun installUpdate(command: String): Pair<String, Long>? {
        val t = command.split(' ')
        if (t.size != 3 || t[0] != "install-update") return null
        val packageName = pkg(t[1]) ?: return null
        if (packageName in SideSpeedPackages.ALL) return null
        if (!INSTALL_UPDATE_SIZE_DIGITS.matches(t[2])) return null
        val size = t[2].toLongOrNull() ?: return null
        if (size < 1 || size > MAX_UPDATE_BYTES) return null
        return packageName to size
    }

    /**
     * Fixed argv for an `install-update` of [size] bytes for [pkg]: reinstall
     * (`-r`, never `-d`) of an already-installed package, with no test, ABI
     * or user flags. [pkg] and [size] are the only variable tokens.
     */
    fun installUpdateArgv(pkg: String, size: Long): List<String> = listOf(
        "/system/bin/cmd", "package", "install", "-r",
        "--pkg", pkg, "-S", size.toString(),
    )

    /** True for `grant-reboot-recovery`, handled specially by [HelperServer] (never through [toArgv]). */
    fun isGrantRebootRecovery(command: String): Boolean = command == GRANT_REBOOT_RECOVERY

    /** True for `revoke-reboot-recovery`, handled specially by [HelperServer] (never through [toArgv]). */
    fun isRevokeRebootRecovery(command: String): Boolean = command == REVOKE_REBOOT_RECOVERY

    /** True for `adb-trusted-networks`, handled specially by [HelperServer] (never through [toArgv]). */
    fun isAdbTrustedNetworks(command: String): Boolean = command == ADB_TRUSTED_NETWORKS

    /**
     * Fixed argvs for granting reboot-recovery permissions to [selfPkg].
     * Grants `WRITE_SECURE_SETTINGS` last so any earlier failure leaves it ungranted.
     */
    fun grantRebootRecoveryArgvs(selfPkg: String): List<List<String>> {
        require(selfPkg in SideSpeedPackages.ALL) { "unknown SideSpeed package: $selfPkg" }
        return listOf(
            listOf("/system/bin/pm", "grant", selfPkg, PERM_COARSE_LOCATION),
            listOf("/system/bin/pm", "grant", selfPkg, PERM_FINE_LOCATION),
            listOf("/system/bin/pm", "grant", selfPkg, PERM_BACKGROUND_LOCATION),
            listOf("/system/bin/pm", "grant", selfPkg, PERM_WRITE_SECURE_SETTINGS),
        )
    }

    /**
     * Fixed argvs for revoking reboot-recovery permissions from [selfPkg].
     * Revokes `WRITE_SECURE_SETTINGS` first before revoking runtime location permissions.
     */
    fun revokeRebootRecoveryArgvs(selfPkg: String): List<List<String>> {
        require(selfPkg in SideSpeedPackages.ALL) { "unknown SideSpeed package: $selfPkg" }
        return listOf(
            listOf("/system/bin/pm", "revoke", selfPkg, PERM_WRITE_SECURE_SETTINGS),
            listOf("/system/bin/pm", "revoke", selfPkg, PERM_BACKGROUND_LOCATION),
            listOf("/system/bin/pm", "revoke", selfPkg, PERM_FINE_LOCATION),
            listOf("/system/bin/pm", "revoke", selfPkg, PERM_COARSE_LOCATION),
        )
    }

    /** Fixed argv for reading ADB's trusted-network keystore inside the helper. */
    fun adbTrustedNetworksArgv(): List<String> = listOf("/system/bin/dumpsys", "adb")

    /** Maps a requested command line to a fixed argv, or null if not allowed. `ping` returns null: see [isPing]. */
    fun toArgv(command: String, selfPkg: String): List<String>? {
        require(selfPkg in SideSpeedPackages.ALL) { "unknown SideSpeed package: $selfPkg" }
        if (command.isEmpty() || command.length > HelperProtocol.MAX_REQUEST_CHARS) return null
        if (!ALLOWED_CHARS.matches(command)) return null
        val t = command.split(' ')
        if (t.any { it.isEmpty() }) return null // leading, trailing or repeated spaces
        return when {
            t.size == 6 && t[0] == "pm" && t[1] == "compile" && t[2] == "-m" && t[4] == "-f" ->
                compile(listOf("/system/bin/pm", "compile"), mode = t[3], pkg = t[5])
            t.size == 7 && t[0] == "cmd" && t[1] == "package" && t[2] == "compile" && t[3] == "-m" && t[5] == "-f" ->
                compile(listOf("/system/bin/cmd", "package", "compile"), mode = t[4], pkg = t[6])
            t.size == 3 && t[0] == "dumpsys" && t[1] == "package" && t[2] == "dexopt" ->
                listOf("/system/bin/dumpsys", "package", "dexopt")
            t.size == 3 && t[0] == "dumpsys" && t[1] == "package" ->
                pkg(t[2])?.let { listOf("/system/bin/dumpsys", "package", it) }
            t.size == 3 && t[0] == "pm" && t[1] == "trim-caches" && TRIM_CACHES.matches(t[2]) ->
                listOf("/system/bin/pm", "trim-caches", t[2])
            t.size == 4 && t[0] == "settings" && t[1] == "get" && t[2] == "global" && t[3] in ANIMATION_KEYS ->
                listOf("/system/bin/settings", "get", "global", t[3])
            t.size == 5 && t[0] == "settings" && t[1] == "put" && t[2] == "global" &&
                t[3] in ANIMATION_KEYS && animationValue(t[4]) ->
                listOf("/system/bin/settings", "put", "global", t[3], t[4])
            t.size == 3 && t[0] == "set-home" ->
                setHome(t[1], t[2])
            t.size == 5 && t[0] == "pm" && t[1] == "disable-user" && t[2] == "--user" && t[3] == "0" ->
                pkg(t[4])?.let { listOf("/system/bin/pm", "disable-user", "--user", "0", it) }
            t.size == 3 && t[0] == "pm" && t[1] == "enable" ->
                pkg(t[2])?.let { listOf("/system/bin/pm", "enable", it) }
            t.size == 6 && t[0] == "pm" && t[1] == "list" && t[2] == "packages" && t[3] == "-d" &&
                t[4] == "--user" && t[5] == "0" ->
                listOf("/system/bin/pm", "list", "packages", "-d", "--user", "0")
            t.size == 3 && t[0] == "launch-time" ->
                launchTime(t[1], t[2])
            t.size == 1 && t[0] == "home-role" ->
                listOf("/system/bin/cmd", "role", "get-role-holders", "android.app.role.HOME")
            t.size == 1 && t[0] == "bring-back" ->
                listOf(
                    "/system/bin/am", "start",
                    "--activity-clear-top", "--activity-single-top",
                    "-n", "$selfPkg/${SideSpeedPackages.MAIN_ACTIVITY}",
                )
            t.size == 1 && t[0] == REVOKE_SECURE_SETTINGS ->
                listOf("/system/bin/pm", "revoke", selfPkg, PERM_WRITE_SECURE_SETTINGS)
            t.size == 2 && t[0] == "force-stop" -> {
                val p = pkg(t[1]) ?: return null
                if (p in SideSpeedPackages.ALL) return null
                listOf("/system/bin/am", "force-stop", "--user", "0", p)
            }
            else -> null
        }
    }

    private fun compile(prefix: List<String>, mode: String, pkg: String): List<String>? {
        if (mode !in MODES) return null
        val p = pkg(pkg) ?: return null
        return prefix + listOf("-m", mode, "-f", p)
    }

    /** A validated package name, and only if validation didn't change it (PackageName.parse trims). */
    private fun pkg(raw: String): String? = PackageName.parse(raw)?.value?.takeIf { it == raw }

    /** An animation scale in decimal form, at most 100 (restores non-standard originals like 0.333). */
    private fun animationValue(raw: String): Boolean =
        ANIMATION_VALUE.matches(raw) && (raw.toDoubleOrNull() ?: Double.NaN) <= 100.0

    /**
     * A launch-timing request: validated package + fully-qualified activity class tokens, mapped to
     * `-n pkg/cls`. The class is a Java binary name ([ActivityClassName], so nested activities like
     * `com.foo.Main$Launcher` are allowed); it is the only token in any command that may contain
     * `$`. The app resolves short names to full ones before sending.
     */
    /** A set-home request: validated package + class tokens, mapped to `pkg/cls` as one argv element. */
    private fun setHome(pkgRaw: String, clsRaw: String): List<String>? {
        val p = pkg(pkgRaw) ?: return null
        val c = ActivityClassName.parse(clsRaw)?.value?.takeIf { it == clsRaw } ?: return null
        return listOf("/system/bin/cmd", "package", "set-home-activity", "--user", "0", "$p/$c")
    }

    private fun launchTime(pkgRaw: String, clsRaw: String): List<String>? {
        val p = pkg(pkgRaw) ?: return null
        val c = ActivityClassName.parse(clsRaw)?.value?.takeIf { it == clsRaw } ?: return null
        return listOf("/system/bin/am", "start", "-W", "-S", "-n", "$p/$c")
    }
}
