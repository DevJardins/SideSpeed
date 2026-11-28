package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.companion.tv.ContentCallResult
import io.github.devjardins.sidespeed.companion.tv.InstalledPackageInfo
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvCommandsInstallTest {
    @Test
    fun package_commands_build_exact_strings_and_reject_invalid_packages() {
        assertEquals("pm path com.example.app", TvCommands.packagePath("com.example.app"))
        assertEquals("dumpsys package com.example.app", TvCommands.packageDump("com.example.app"))
        assertEquals(
            "content call --uri content://io.github.devjardins.sidespeed.status/now " +
                "--method compile_package --arg com.example.app --extra keep:b:true",
            TvCommands.callCompilePackage("com.example.app", keepOptimized = true),
        )
        assertEquals(
            "content call --uri content://io.github.devjardins.sidespeed.status/now " +
                "--method compile_package --arg com.example.app --extra keep:b:false",
            TvCommands.callCompilePackage("com.example.app", keepOptimized = false),
        )
        assertEquals(
            "content call --uri content://io.github.devjardins.sidespeed.status/now --method optimize_now",
            TvCommands.CALL_OPTIMIZE_NOW,
        )

        val invalid = listOf(
            "",
            "app",
            " com.example.app",
            "com.example.app ",
            "com.example.app\n",
            "com.example;id",
            "com..example",
            "com.1app",
            "a." + "b".repeat(255),
        )
        for (bad in invalid) {
            assertFailsWith<IllegalArgumentException>(bad) { TvCommands.packagePath(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { TvCommands.packageDump(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { TvCommands.callCompilePackage(bad, true) }
        }
    }

    @Test
    fun sdk_prop_builds_fixed_command_and_parses_levels() {
        assertEquals("getprop ro.build.version.sdk", TvCommands.PROP_SDK)
        assertEquals(28, TvCommands.parseSdkLevel("28"))
        assertEquals(34, TvCommands.parseSdkLevel("  34\n"))
        assertNull(TvCommands.parseSdkLevel(""))
        assertNull(TvCommands.parseSdkLevel("abc"))
        assertNull(TvCommands.parseSdkLevel("0"))
        assertNull(TvCommands.parseSdkLevel("-1"))
    }

    @Test
    fun parseContentCallResult_handles_ok_error_and_older_tv_responses() {
        assertEquals(
            ContentCallResult.Ok(queued = 1, reqSeq = 5L),
            TvCommands.parseContentCallResult("Result: Bundle[{ok=true, queued=1, req_seq=5}]"),
        )
        assertEquals(
            ContentCallResult.Ok(queued = 0, reqSeq = 9L),
            TvCommands.parseContentCallResult("Result: Bundle[{req_seq=9, queued=0, ok=true}]"),
        )
        assertEquals(
            ContentCallResult.Error(error = "unknown_method", reqSeq = 0L),
            TvCommands.parseContentCallResult("Result: Bundle[{ok=false, error=unknown_method, queued=0, req_seq=0}]"),
        )
        assertEquals(
            ContentCallResult.Unsupported,
            TvCommands.parseContentCallResult("java.lang.UnsupportedOperationException: Unknown method compile_package"),
        )
        assertEquals(
            ContentCallResult.Unsupported,
            TvCommands.parseContentCallResult(
                "Error while accessing provider:io.github.devjardins.sidespeed.status\n" +
                    "java.lang.IllegalStateException: Could not find provider: io.github.devjardins.sidespeed.status",
            ),
        )
        assertEquals(
            ContentCallResult.Unsupported,
            TvCommands.parseContentCallResult("Result: null"),
        )
        assertNull(TvCommands.parseContentCallResult(""))
        assertNull(TvCommands.parseContentCallResult("Result: Bundle[{queued=1}]"))
    }

    @Test
    fun parsePackageDump_parses_android_9_and_14_outputs_and_ignores_hidden_system_packages() {
        val android9PlaySystem = """
            Packages:
              Package [com.example.tv] (1234abcd):
                userId=10042
                codePath=/data/app/com.example.tv-1
                versionCode=120 minSdk=26 targetSdk=33
                versionName=1.2.0
                installerPackageName=com.android.vending
                pkgFlags=[ SYSTEM HAS_CODE ]
        """.trimIndent()
        assertEquals(
            InstalledPackageInfo(
                versionCode = 120L,
                versionName = "1.2.0",
                installerPackage = "com.android.vending",
                isSystem = true,
            ),
            TvCommands.parsePackageDump(android9PlaySystem),
        )
        assertTrue(TvCommands.parsePackageDump(android9PlaySystem)!!.isStoreInstalled)

        val android14Amazon = """
            Packages:
              Package [com.example.tv] (9876fedc):
                appId=10123
                versionCode=240 minSdk=26 targetSdk=34
                versionName=2.4.0
                flags=[ HAS_CODE ALLOW_BACKUP ]
                pkgFlags=[ HAS_CODE ALLOW_BACKUP ]
                User 0: ceDataInode=123 installed=true
                  installerPackageName=null
                  initiatingPackageName=com.amazon.venezia
                  installingPackageName=com.amazon.venezia

            Hidden system packages:
              Package [com.example.tv] (1111aaaa):
                versionCode=10 minSdk=21 targetSdk=28
                versionName=0.1.0
                pkgFlags=[ SYSTEM HAS_CODE ]
        """.trimIndent()
        val parsed14 = TvCommands.parsePackageDump(android14Amazon)!!
        assertEquals(240L, parsed14.versionCode)
        assertEquals("2.4.0", parsed14.versionName)
        assertEquals("com.amazon.venezia", parsed14.installerPackage)
        assertFalse(parsed14.isSystem)
        assertTrue(parsed14.isStoreInstalled)

        val sideloaded = """
            Packages:
              Package [com.example.side] (abcd):
                versionCode=50 minSdk=26 targetSdk=34
                versionName=5.0
                pkgFlags=[ HAS_CODE ]
                installerPackageName=null
        """.trimIndent()
        val parsedSide = TvCommands.parsePackageDump(sideloaded)!!
        assertEquals(50L, parsedSide.versionCode)
        assertNull(parsedSide.installerPackage)
        assertFalse(parsedSide.isSystem)
        assertFalse(parsedSide.isStoreInstalled)

        assertNull(TvCommands.parsePackageDump(""))
        assertNull(TvCommands.parsePackageDump("Unable to find package: com.example.missing"))
    }

    @Test
    fun parsePackageDump_stops_at_a_new_section_before_any_package_block() {
        val strayBlock = """
            Packages:
            Stale section:
              Package [com.example.decoy] (abcd):
                versionCode=42 minSdk=26 targetSdk=34
                versionName=9.9
                pkgFlags=[ HAS_CODE ]
        """.trimIndent()
        assertNull(TvCommands.parsePackageDump(strayBlock))

        val sectionAfterBlock = """
            Packages:
              Package [com.example.tv] (1234abcd):
                versionCode=120 minSdk=26 targetSdk=33
                versionName=1.2.0
                pkgFlags=[ HAS_CODE ]
            Hidden system packages:
              Package [com.example.tv] (1111aaaa):
                versionCode=10 minSdk=21 targetSdk=28
                versionName=0.1.0
                pkgFlags=[ SYSTEM HAS_CODE ]
        """.trimIndent()
        assertEquals(120L, TvCommands.parsePackageDump(sectionAfterBlock)!!.versionCode)
    }
}
