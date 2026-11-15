package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SideSpeedUidTest {
    private val pkg = "io.github.devjardins.sidespeed"

    @Test fun fromPmList() {
        assertEquals(10255, SideSpeedUid.fromPmList(listOf("package:$pkg uid:10255"), pkg))
    }

    @Test fun pmListIgnoresLookalikePackages() {
        assertNull(SideSpeedUid.fromPmList(listOf("package:$pkg.evil uid:10300", "package:x$pkg uid:10301"), pkg))
        assertEquals(10255, SideSpeedUid.fromPmList(listOf("package:$pkg.evil uid:10300", "package:$pkg uid:10255"), pkg))
    }

    @Test fun pmListRejectsNonAppUids() {
        assertNull(SideSpeedUid.fromPmList(listOf("package:$pkg uid:2000"), pkg))
        assertNull(SideSpeedUid.fromPmList(listOf("package:$pkg uid:0"), pkg))
        assertNull(SideSpeedUid.fromPmList(listOf("package:$pkg uid:1010255"), pkg))
        assertNull(SideSpeedUid.fromPmList(emptyList(), pkg))
    }

    @Test fun fromDumpsysTakesUserIdInsideOwnPackageSection() {
        val dump = listOf(
            "Packages:",
            "  Package [$pkg.evil] (abc):",
            "    userId=10300",
            "  Package [$pkg] (def):",
            "    userId=10255",
            "    pkg=Package{def $pkg}",
        )
        assertEquals(10255, SideSpeedUid.fromDumpsys(dump, pkg))
        assertNull(SideSpeedUid.fromDumpsys(listOf("    userId=10255"), pkg))
    }
}
