package io.github.devjardins.sidespeed.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HelperPathsTest {
    @Test fun releasePathsUnchanged() {
        assertEquals(
            HelperPaths(
                processName = "sidespeed_helper",
                portFile = HelperProtocol.PORT_FILE,
                logFile = HelperProtocol.LOG_FILE,
                ackDir = HelperProtocol.ACK_DIR,
            ),
            HelperPaths.of(SideSpeedPackages.RELEASE),
        )
    }

    @Test fun debugPathsDiffer() {
        assertEquals(
            HelperPaths(
                processName = "sidespeed_helper_debug",
                portFile = "/data/local/tmp/sidespeed_helper_debug.port",
                logFile = "/data/local/tmp/sidespeed_helper_debug.log",
                ackDir = "/data/local/tmp/sidespeed_helper_debug.acks",
            ),
            HelperPaths.of(SideSpeedPackages.DEBUG),
        )
    }

    @Test fun unknownPackageRejected() {
        assertFailsWith<IllegalArgumentException> { HelperPaths.of("com.example") }
        assertFailsWith<IllegalArgumentException> { HelperLaunch.command("com.example") }
        assertFailsWith<IllegalArgumentException> { HelperLaunch.killCommand("com.example") }
    }
}
