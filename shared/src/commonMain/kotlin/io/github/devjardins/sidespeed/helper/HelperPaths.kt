package io.github.devjardins.sidespeed.helper

/**
 * Per-package process name and files for the shell-uid helper, so debug and release helpers can run
 * side by side without colliding. Release values stay byte-identical to [HelperProtocol].
 */
data class HelperPaths(
    val processName: String,
    val portFile: String,
    val logFile: String,
    val ackDir: String,
) {
    companion object {
        fun of(pkg: String): HelperPaths {
            require(pkg in SideSpeedPackages.ALL) { "unknown SideSpeed package: $pkg" }
            return when (pkg) {
                SideSpeedPackages.RELEASE -> HelperPaths(
                    processName = "sidespeed_helper",
                    portFile = HelperProtocol.PORT_FILE,
                    logFile = HelperProtocol.LOG_FILE,
                    ackDir = HelperProtocol.ACK_DIR,
                )
                SideSpeedPackages.DEBUG -> HelperPaths(
                    processName = "sidespeed_helper_debug",
                    portFile = "/data/local/tmp/sidespeed_helper_debug.port",
                    logFile = "/data/local/tmp/sidespeed_helper_debug.log",
                    ackDir = "/data/local/tmp/sidespeed_helper_debug.acks",
                )
                else -> throw IllegalArgumentException("unknown SideSpeed package: $pkg")
            }
        }
    }
}
