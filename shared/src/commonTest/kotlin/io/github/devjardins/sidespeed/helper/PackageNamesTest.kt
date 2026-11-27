package io.github.devjardins.sidespeed.helper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageNamesTest {
    @Test fun acceptsValidPackageNames() {
        assertTrue(PackageNames.isValid("com.example.app"))
        assertTrue(PackageNames.isValid("a.b_1.C2"))
    }

    @Test fun acceptsPackageNameUpToMaxLength() {
        val maxLenPkg = "a." + "b".repeat(PackageNames.MAX_LENGTH - 2)
        assertEquals(255, maxLenPkg.length)
        assertTrue(PackageNames.isValid(maxLenPkg))
    }

    @Test fun rejectsInvalidPackageNames() {
        val tooLong = "a." + "b".repeat(PackageNames.MAX_LENGTH - 1)
        assertEquals(256, tooLong.length)
        val invalid = listOf(
            "",
            "app",
            " com.example.app",
            "com.example.app ",
            "com.a;id",
            "com..a",
            "com.1a",
            "1com.a",
            ".com.a",
            "com.a.",
            tooLong,
        )
        for (raw in invalid) {
            assertFalse(PackageNames.isValid(raw), "Expected rejection for '$raw'")
        }
    }

    @Test fun storeInstallersContainPlayAndAmazon() {
        assertEquals(setOf("com.android.vending", "com.amazon.venezia"), PackageNames.STORE_INSTALLERS)
    }
}
