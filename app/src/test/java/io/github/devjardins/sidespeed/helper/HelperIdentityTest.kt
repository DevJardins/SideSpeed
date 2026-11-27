package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperIdentityTest {
    @Test fun noPkgArgTrustsRelease() {
        assertEquals(SideSpeedPackages.RELEASE, HelperIdentity.packageFrom(arrayOf("--apk=/x")))
        assertEquals(SideSpeedPackages.RELEASE, HelperIdentity.packageFrom(emptyArray()))
    }

    @Test fun releasePkgAccepted() {
        assertEquals(
            SideSpeedPackages.RELEASE,
            HelperIdentity.packageFrom(arrayOf("--apk=/x", "--pkg=io.github.devjardins.sidespeed")),
        )
    }

    @Test fun debugPkgAccepted() {
        assertEquals(
            SideSpeedPackages.DEBUG,
            HelperIdentity.packageFrom(arrayOf("--apk=/x", "--pkg=io.github.devjardins.sidespeed.debug")),
        )
    }

    @Test fun unknownPkgRefused() {
        assertNull(HelperIdentity.packageFrom(arrayOf("--apk=/x", "--pkg=com.example")))
        assertNull(HelperIdentity.packageFrom(arrayOf("--apk=/x", "--pkg=io.github.devjardins.sidespeed.other")))
    }

    @Test fun repeatedPkgRefused() {
        assertNull(
            HelperIdentity.packageFrom(
                arrayOf(
                    "--apk=/x",
                    "--pkg=io.github.devjardins.sidespeed",
                    "--pkg=io.github.devjardins.sidespeed.debug",
                ),
            ),
        )
        assertNull(
            HelperIdentity.packageFrom(
                arrayOf(
                    "--apk=/x",
                    "--pkg=io.github.devjardins.sidespeed",
                    "--pkg=io.github.devjardins.sidespeed",
                ),
            ),
        )
    }

    @Test fun emptyPkgRefused() {
        assertNull(HelperIdentity.packageFrom(arrayOf("--apk=/x", "--pkg=")))
        assertNull(HelperIdentity.packageFrom(arrayOf("--apk=/x", "--pkg")))
    }

    @Test fun ownsApkExactMatchOnly() {
        val apk = "/data/app/x/base.apk"
        assertTrue(HelperIdentity.ownsApk(listOf("package:$apk"), apk))
        assertTrue(HelperIdentity.ownsApk(listOf("package:/data/app/x/split_config.apk", "package:$apk"), apk))
        assertFalse(HelperIdentity.ownsApk(listOf("package:/data/app/other/base.apk"), apk))
        assertFalse(HelperIdentity.ownsApk(listOf("package:$apk.extra"), apk))
        assertFalse(HelperIdentity.ownsApk(listOf("package:/data/app/x/base"), apk))
        assertFalse(HelperIdentity.ownsApk(listOf(apk), apk))
        assertFalse(HelperIdentity.ownsApk(emptyList(), apk))
        assertFalse(HelperIdentity.ownsApk(listOf("package:"), ""))
    }
}
