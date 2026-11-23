package io.github.devjardins.sidespeed.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateVerifyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sums_picks_exact_name_line() {
        val other = "a".repeat(64)
        val good = "b".repeat(64)
        val text = "$other  SideSpeed-0.5.0.apk\n$good  SideSpeed.apk\r\n"
        assertEquals(good, parseSha256Sums(text, "SideSpeed.apk"))
    }

    @Test fun sums_accepts_star_binary_marker_and_uppercase() {
        val upper = "AB".repeat(32)
        assertEquals(upper.lowercase(), parseSha256Sums("$upper  *SideSpeed.apk", "SideSpeed.apk"))
    }

    @Test fun sums_missing_or_malformed_returns_null() {
        val good = "b".repeat(64)
        assertNull(parseSha256Sums("$good  SideSpeed-0.5.0.apk\n", "SideSpeed.apk"))
        assertNull(parseSha256Sums("", "SideSpeed.apk"))
        assertNull(parseSha256Sums("zzz  SideSpeed.apk\n", "SideSpeed.apk"))
        assertNull(parseSha256Sums("${"c".repeat(63)}  SideSpeed.apk\n", "SideSpeed.apk"))
        assertNull(parseSha256Sums("${"c".repeat(65)}  SideSpeed.apk\n", "SideSpeed.apk"))
        assertNull(parseSha256Sums("${"g".repeat(64)}  SideSpeed.apk\n", "SideSpeed.apk"))
    }

    @Test fun verify_ok_when_all_match() {
        val hash = "d".repeat(64)
        val apk = ApkFacts(
            packageName = "io.github.devjardins.sidespeed",
            versionCode = 5,
            signerSha256 = setOf("e".repeat(64)),
        )
        assertEquals(
            VerifyResult.Ok,
            verifyUpdate(
                fileSha256 = hash,
                expectedSha256 = hash,
                apk = apk,
                installedVersionCode = 4,
                installedSignerSha256 = setOf("e".repeat(64)),
            ),
        )
    }

    @Test fun verify_rejects() {
        val hash = "d".repeat(64)
        val signer = setOf("e".repeat(64))
        fun okApk() = ApkFacts(
            packageName = "io.github.devjardins.sidespeed",
            versionCode = 5,
            signerSha256 = signer,
        )
        val cases = listOf(
            "sha mismatch" to verifyUpdate("f".repeat(64), hash, okApk(), 4, signer),
            "null expected" to verifyUpdate(hash, null, okApk(), 4, signer),
            "null apk" to verifyUpdate(hash, hash, null, 4, signer),
            "wrong package" to verifyUpdate(hash, hash, okApk().copy(packageName = "com.evil.app"), 4, signer),
            "same version" to verifyUpdate(hash, hash, okApk().copy(versionCode = 4), 4, signer),
            "older version" to verifyUpdate(hash, hash, okApk().copy(versionCode = 3), 4, signer),
            "signer differs" to verifyUpdate(hash, hash, okApk().copy(signerSha256 = setOf("0".repeat(64))), 4, signer),
            "signer subset" to verifyUpdate(hash, hash, okApk().copy(signerSha256 = signer + "0".repeat(64)), 4, signer),
        )
        for ((name, result) in cases) {
            assertTrue(name, result is VerifyResult.Rejected)
            assertTrue(name, (result as VerifyResult.Rejected).reason.isNotEmpty())
        }
    }

    @Test fun verify_rejects_empty_signer_sets() {
        val hash = "d".repeat(64)
        val pkg = "io.github.devjardins.sidespeed"
        val signer = setOf("e".repeat(64))
        val cases = listOf(
            "both empty" to verifyUpdate(hash, hash, ApkFacts(pkg, 5, emptySet()), 4, emptySet()),
            "apk empty" to verifyUpdate(hash, hash, ApkFacts(pkg, 5, emptySet()), 4, signer),
            "installed empty (lookup failed)" to verifyUpdate(hash, hash, ApkFacts(pkg, 5, signer), 4, emptySet()),
        )
        for ((name, result) in cases) {
            assertTrue(name, result is VerifyResult.Rejected)
            assertTrue(name, (result as VerifyResult.Rejected).reason.isNotEmpty())
        }
    }

    @Test fun app_update_ok_when_all_match() {
        val hash = "d".repeat(64)
        val signer = setOf("e".repeat(64))
        val apk = ApkFacts(packageName = "com.nuvio.tv", versionCode = 5, signerSha256 = signer)
        assertEquals(
            VerifyResult.Ok,
            verifyUpdate(hash, hash, apk, 4, signer, "com.nuvio.tv", 5),
        )
    }

    @Test fun app_update_rejects() {
        val hash = "d".repeat(64)
        val signer = setOf("e".repeat(64))
        fun okApk() = ApkFacts(packageName = "com.nuvio.tv", versionCode = 5, signerSha256 = signer)
        val cases = listOf(
            "sha mismatch" to verifyUpdate("f".repeat(64), hash, okApk(), 4, signer, "com.nuvio.tv", 5),
            "null apk" to verifyUpdate(hash, hash, null, 4, signer, "com.nuvio.tv", 5),
            "wrong package" to verifyUpdate(hash, hash, okApk().copy(packageName = "com.evil.app"), 4, signer, "com.nuvio.tv", 5),
            "version is not the listed one" to verifyUpdate(hash, hash, okApk().copy(versionCode = 6), 4, signer, "com.nuvio.tv", 5),
            "listed version is not newer" to verifyUpdate(hash, hash, okApk(), 5, signer, "com.nuvio.tv", 5),
            "older than installed" to verifyUpdate(hash, hash, okApk().copy(versionCode = 3), 4, signer, "com.nuvio.tv", 5),
            "signer differs" to verifyUpdate(hash, hash, okApk().copy(signerSha256 = setOf("0".repeat(64))), 4, signer, "com.nuvio.tv", 5),
        )
        for ((name, result) in cases) {
            assertTrue(name, result is VerifyResult.Rejected)
            assertTrue(name, (result as VerifyResult.Rejected).reason.isNotEmpty())
        }
    }

    @Test fun self_update_ignores_expected_version() {
        // Self-updates accept any newer version: no listed version is passed.
        val hash = "d".repeat(64)
        val signer = setOf("e".repeat(64))
        val apk = ApkFacts("io.github.devjardins.sidespeed", 9, signer)
        assertEquals(VerifyResult.Ok, verifyUpdate(hash, hash, apk, 4, signer))
    }

    @Test fun sha256_hex_matches_file_bytes() {
        val f: File = tmp.newFile("a.bin")
        f.writeBytes("hello".toByteArray())
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha256Hex(f))
    }
}
