package io.github.devjardins.sidespeed.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperMainTest {
    @Test fun pmListInstaller_reads_third_party_installers() {
        assertEquals(
            "null",
            HelperMain.pmListInstaller("package:com.nuvio.tv installer=null", "com.nuvio.tv"),
        )
        assertEquals(
            "com.fdroid",
            HelperMain.pmListInstaller("package:org.fdroid.fdroid installer=com.fdroid", "org.fdroid.fdroid"),
        )
    }

    @Test fun pmListInstaller_api28_format() {
        // `pm list packages -3 -i <pkg>` prints one `package:<pkg> installer=<x>` row,
        // separated by whitespace; an unknown installer reads `installer=null`.
        assertEquals(
            "null",
            HelperMain.pmListInstaller("package:com.example.app installer=null", "com.example.app"),
        )
        assertEquals(
            "com.example.store",
            HelperMain.pmListInstaller("package:com.example.app  installer=com.example.store", "com.example.app"),
        )
    }

    @Test fun pmListInstaller_refuses_anything_but_the_exact_row() {
        val pkg = "com.foo"
        listOf(
            // A longer package name that merely starts with pkg.
            "package:com.foo.bar installer=null",
            // A shorter one pkg merely extends.
            "package:com.fo installer=null",
            "package:com.foo",
            "package:com.foo installer=",
            "package:com.foo installer=null extra",
            "package:com.foo installer =null",
            "package: com.foo installer=null",
            "package:com.foo\tinstaller=null\t",
            "package:COM.FOO installer=null",
            "",
        ).forEach { assertNull(it, HelperMain.pmListInstaller(it, pkg)) }
    }

    @Test fun isThirdPartySideload_allows_unknown_and_third_party_installers() {
        assertTrue(
            HelperMain.isThirdPartySideload(
                listOf("package:com.nuvio.tv installer=null"),
                "com.nuvio.tv",
            ),
        )
        assertTrue(
            HelperMain.isThirdPartySideload(
                listOf("package:com.nuvio.tv installer=com.third.party", "other noise"),
                "com.nuvio.tv",
            ),
        )
    }

    @Test fun isThirdPartySideload_refuses_store_installers() {
        assertFalse(
            HelperMain.isThirdPartySideload(
                listOf("package:com.example.app installer=com.android.vending"),
                "com.example.app",
            ),
        )
        assertFalse(
            HelperMain.isThirdPartySideload(
                listOf("package:com.example.app installer=com.amazon.venezia"),
                "com.example.app",
            ),
        )
    }

    @Test fun isThirdPartySideload_refuses_without_an_exact_row() {
        // Only a longer package name matches: a substring match is refused.
        assertFalse(
            HelperMain.isThirdPartySideload(
                listOf("package:com.foo.bar installer=null"),
                "com.foo",
            ),
        )
        assertFalse(HelperMain.isThirdPartySideload(emptyList(), "com.foo"))
        assertFalse(
            HelperMain.isThirdPartySideload(
                listOf("package:com.foo"),
                "com.foo",
            ),
        )
    }
}
