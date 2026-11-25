package io.github.devjardins.sidespeed.companion.update

import io.github.devjardins.sidespeed.companion.testing.EnglishStrings
import io.github.devjardins.sidespeed.companion.testing.english
import io.github.devjardins.sidespeed.companion.ui.text.resolve
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull

class CompanionUpdateTest {

    // Release JSON parsing.

    @Test fun tagNameParsed() =
        assertEquals("v0.7.0", parseLatestTag("""{"tag_name":"v0.7.0","name":"0.7.0"}"""))

    @Test fun tagNameWithSpacesParsed() =
        assertEquals("v0.8.1", parseLatestTag("{ \"tag_name\" : \"v0.8.1\" }"))

    @Test fun missingTagIsNull() = assertNull(parseLatestTag("""{"name":"0.7.0"}"""))

    @Test fun emptyTagIsNull() = assertNull(parseLatestTag("""{"tag_name":""}"""))

    @Test fun garbageIsNull() = assertNull(parseLatestTag("not json at all"))

    @Test fun displayVersionStripsV() {
        assertEquals("0.7.0", displayVersion("v0.7.0"))
        assertEquals("0.7.0", displayVersion("V0.7.0"))
        assertEquals("0.7.0", displayVersion("0.7.0"))
    }

    // Version compare and the pre-release rule.

    @Test fun newerComparesGreater() = assertEquals(1, compareCompanionVersions("v0.8.0", "0.7.0"))

    @Test fun olderComparesLess() = assertEquals(-1, compareCompanionVersions("v0.6.9", "0.7.0"))

    @Test fun equalComparesZero() = assertEquals(0, compareCompanionVersions("v0.7.0", "0.7.0"))

    @Test fun missingPartsCountAsZero() = assertEquals(0, compareCompanionVersions("1.0", "1.0.0"))

    @Test fun longerRunCompares() = assertEquals(1, compareCompanionVersions("1.0.0.1", "1.0"))

    @Test fun preReleaseLatestNeverUpdates() {
        listOf("1.0-alpha", "1.0-beta", "1.0-rc1", "1.0-dev", "1.0-pre", "1.0-snapshot", "1.0-nightly", "2.0BETA2")
            .forEach { assertNull(compareCompanionVersions(it, "1.0"), it) }
    }

    @Test fun preReleaseInstalledNeverUpdates() {
        listOf("1.0alpha", "1.0beta2", "29.47-beta", "1.0-rc.1")
            .forEach { assertNull(compareCompanionVersions("2.0", it), it) }
    }

    @Test fun arcIsNotRc() = assertEquals(1, compareCompanionVersions("1.2.0-arc.3", "1.2.0"))

    @Test fun digitlessVersionsAreUncertain() {
        assertNull(compareCompanionVersions("abc", "1.0"))
        assertNull(compareCompanionVersions("1.0", "abc"))
    }

    // Checksum line parsing.

    private val sums = "abc123".padEnd(64, '0') + "  SideSpeed-Companion.apk\n" +
        "DEF456".padEnd(64, '0') + "  SideSpeed-Companion-0.7.0.apk\n"

    @Test fun exactNameMatches() =
        assertEquals("abc123".padEnd(64, '0'), parseSha256Sums(sums, COMPANION_APK_NAME))

    @Test fun versionedTwinIgnored() =
        assertEquals("def456".padEnd(64, '0'), parseSha256Sums(sums, "SideSpeed-Companion-0.7.0.apk"))

    @Test fun binaryMarkerAccepted() {
        val text = "f".repeat(64) + " *SideSpeed-Companion.apk\n"
        assertEquals("f".repeat(64), parseSha256Sums(text, COMPANION_APK_NAME))
    }

    @Test fun crlfAccepted() {
        val text = "e".repeat(64) + "  SideSpeed-Companion.apk\r\n"
        assertEquals("e".repeat(64), parseSha256Sums(text, COMPANION_APK_NAME))
    }

    @Test fun missingFileIsNull() = assertNull(parseSha256Sums(sums, "SideSpeed.apk"))

    @Test fun shortHashIgnored() =
        assertNull(parseSha256Sums("abc  SideSpeed-Companion.apk\n", COMPANION_APK_NAME))

    // APK verification.

    private val signers = setOf("aa")
    private val apk = ApkFacts(COMPANION_PACKAGE, signers)

    @Test fun verifyOk() {
        assertTrue(verifyCompanionApk("ab", "ab", apk, signers) is CompanionVerifyResult.Ok)
    }

    @Test fun verifyHashCaseInsensitive() {
        assertTrue(verifyCompanionApk("AB", "ab", apk, signers) is CompanionVerifyResult.Ok)
    }

    @Test fun verifyMismatchRejected() {
        val result = assertIs<CompanionVerifyResult.Rejected>(verifyCompanionApk("ab", "cd", apk, signers))
        assertEquals("Checksum mismatch.", result.reason.english())
    }

    @Test fun verifyMissingChecksumRejected() {
        val result = assertIs<CompanionVerifyResult.Rejected>(verifyCompanionApk("ab", null, apk, signers))
        assertEquals("No checksum found for SideSpeed-Companion.apk.", result.reason.english())
        assertEquals(
            "No checksum found for \u2066SideSpeed-Companion.apk\u2069.",
            result.reason.resolve(EnglishStrings, rtl = true),
        )
    }

    @Test fun verifyUnreadableApkRejected() {
        val result = assertIs<CompanionVerifyResult.Rejected>(verifyCompanionApk("ab", "ab", null, signers))
        assertEquals("Could not read the downloaded APK.", result.reason.english())
    }

    @Test fun verifyWrongPackageRejected() {
        val result = assertIs<CompanionVerifyResult.Rejected>(
            verifyCompanionApk("ab", "ab", ApkFacts("com.example.other", signers), signers),
        )
        assertEquals("Wrong package: com.example.other.", result.reason.english())
        assertEquals(
            "Wrong package: \u2066com.example.other\u2069.",
            result.reason.resolve(EnglishStrings, rtl = true),
        )
    }

    @Test fun verifySignerMismatchRejected() {
        val result = assertIs<CompanionVerifyResult.Rejected>(verifyCompanionApk("ab", "ab", apk, setOf("bb")))
        assertEquals("Signer does not match the installed app.", result.reason.english())
    }

    @Test fun verifyUnknownSignerRefused() {
        val missingInstalled = assertIs<CompanionVerifyResult.Rejected>(verifyCompanionApk("ab", "ab", apk, emptySet()))
        assertEquals("Signer unknown: refusing the update.", missingInstalled.reason.english())
        val missingArchive = assertIs<CompanionVerifyResult.Rejected>(
            verifyCompanionApk("ab", "ab", ApkFacts(COMPANION_PACKAGE, emptySet()), signers),
        )
        assertEquals("Signer unknown: refusing the update.", missingArchive.reason.english())
    }

    // Desktop asset picking.

    @Test fun macArm64GetsDmg() =
        assertEquals(MACOS_ASSET, pickDesktopAsset("Mac OS X", "aarch64", dpkgPresent = false))

    @Test fun macIntelHasNoAsset() =
        assertNull(pickDesktopAsset("Mac OS X", "x86_64", dpkgPresent = false))

    @Test fun windowsGetsMsi() =
        assertEquals(WINDOWS_ASSET, pickDesktopAsset("Windows 11", "amd64", dpkgPresent = false))

    @Test fun linuxDebWhenDpkg() =
        assertEquals(LINUX_DEB_ASSET, pickDesktopAsset("Linux", "amd64", dpkgPresent = true))

    @Test fun linuxRpmWithoutDpkg() =
        assertEquals(LINUX_RPM_ASSET, pickDesktopAsset("Linux", "x86_64", dpkgPresent = false))

    @Test fun linuxArmHasNoAsset() =
        assertNull(pickDesktopAsset("Linux", "aarch64", dpkgPresent = true))

    @Test fun unknownOsHasNoAsset() =
        assertNull(pickDesktopAsset("FreeBSD", "amd64", dpkgPresent = false))

    @Test fun downloadUrlAppendsAsset() =
        assertEquals(
            "$LATEST_DOWNLOAD_BASE_URL/$MACOS_ASSET",
            desktopDownloadUrl(MACOS_ASSET),
        )

    // Check states with fake fetchers (no network).

    private fun checkReturning(json: String, installed: String) =
        CompanionUpdateCheck(fetchJson = { json }, installedVersion = installed)

    @Test fun newerReleaseIsAvailable() = runTest {
        val state = checkReturning("""{"tag_name":"v0.8.0"}""", "0.7.0").checkNow()
        assertEquals(CompanionUpdateState.Available("v0.8.0"), state)
    }

    @Test fun sameReleaseIsUpToDate() = runTest {
        val state = checkReturning("""{"tag_name":"v0.7.0"}""", "0.7.0").checkNow()
        assertEquals(CompanionUpdateState.UpToDate, state)
    }

    @Test fun olderReleaseIsUpToDate() = runTest {
        val state = checkReturning("""{"tag_name":"v0.6.0"}""", "0.7.0").checkNow()
        assertEquals(CompanionUpdateState.UpToDate, state)
    }

    @Test fun preReleaseLatestIsUpToDate() = runTest {
        val state = checkReturning("""{"tag_name":"v0.8.0-beta"}""", "0.7.0").checkNow()
        assertEquals(CompanionUpdateState.UpToDate, state)
    }

    @Test fun preReleaseInstalledIsUpToDate() = runTest {
        val state = checkReturning("""{"tag_name":"v0.8.0"}""", "0.7.0-beta").checkNow()
        assertEquals(CompanionUpdateState.UpToDate, state)
    }

    @Test fun garbageTagIsUpToDate() = runTest {
        val state = checkReturning("""{"tag_name":"latest"}""", "0.7.0").checkNow()
        assertEquals(CompanionUpdateState.UpToDate, state)
    }

    @Test fun fetchFailureReportsFailed() = runTest {
        val check = CompanionUpdateCheck(
            fetchJson = { throw IllegalStateException("offline") },
            installedVersion = "0.7.0",
        )
        assertEquals(CompanionUpdateState.Failed(UPDATE_OFFLINE_MESSAGE), check.checkNow())
    }

    @Test fun unparsablePayloadReportsFailed() = runTest {
        val state = checkReturning("not json", "0.7.0").checkNow()
        assertEquals(CompanionUpdateState.Failed(UPDATE_OFFLINE_MESSAGE), state)
    }
}
