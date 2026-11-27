package io.github.devjardins.sidespeed.helper

/**
 * Identifies which SideSpeed package a helper serves and binds that package to the APK the
 * helper is running from. Plain JVM (no android.*) so it is unit-tested; [HelperMain] runs
 * `pm path` and exits before binding when either check fails.
 */
internal object HelperIdentity {
    private const val PKG_FLAG = "--pkg"
    private const val PKG_PREFIX = "--pkg="

    /** `--pkg=<p>` if exactly one and p in ALL; RELEASE when absent; null when repeated or unknown. */
    fun packageFrom(args: Array<String>): String? {
        val matches = args.filter { it == PKG_FLAG || it.startsWith(PKG_PREFIX) }
        if (matches.isEmpty()) return SideSpeedPackages.RELEASE
        if (matches.size > 1) return null
        val arg = matches.single()
        if (!arg.startsWith(PKG_PREFIX)) return null
        val pkg = arg.removePrefix(PKG_PREFIX)
        return pkg.takeIf { it in SideSpeedPackages.ALL }
    }

    /** True when a `package:<path>` line of `pm path` output equals [apk] exactly. */
    fun ownsApk(pmPathLines: List<String>, apk: String): Boolean =
        apk.isNotEmpty() && pmPathLines.any { it == "package:$apk" }
}
