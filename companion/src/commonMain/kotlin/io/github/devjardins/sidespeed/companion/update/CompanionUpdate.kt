package io.github.devjardins.sidespeed.companion.update

/**
 * Shared update logic for both Companion apps: parsing the GitHub `releases/latest` payload,
 * deciding whether a tag is newer than the installed version, verifying a downloaded APK, and
 * picking the desktop download. No I/O here: each app injects its own fetcher, so this file is
 * fully unit-testable with fakes.
 */

/** The release metadata endpoint both Companion apps check. */
const val RELEASES_API_URL = "https://api.github.com/repos/DevJardins/SideSpeed/releases/latest"

/** Cap for the `releases/latest` body: metadata is tiny; anything larger is a glitch or abuse. */
const val COMPANION_UPDATE_CHECK_MAX_BYTES = 256 * 1024L

/** Opened when no desktop asset fits the current OS/arch. */
const val RELEASES_PAGE_URL = "https://github.com/DevJardins/SideSpeed/releases"

/** Fixed-name downloads served at `releases/latest/download/<name>`. */
const val LATEST_DOWNLOAD_BASE_URL = "https://github.com/DevJardins/SideSpeed/releases/latest/download"

/** The Android Companion APK's fixed download name. */
const val COMPANION_APK_NAME = "SideSpeed-Companion.apk"

/** The checksums file shipped next to the APK. */
const val COMPANION_SUMS_NAME = "SHA256SUMS.txt"

/** Package the downloaded APK must carry. */
const val COMPANION_PACKAGE = "io.github.devjardins.sidespeed.companion"

/** Shown when the check fails for any reason, matching the TV About screen's wording. */
const val UPDATE_OFFLINE_MESSAGE = "Couldn't reach GitHub. Try again later."

private val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]*)\"")

/** Extracts `tag_name` from a `releases/latest` payload; null when missing or empty. */
fun parseLatestTag(json: String): String? =
    TAG_NAME.find(json)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

/** Display form of a release tag: the `v` prefix is just packaging. */
fun displayVersion(tag: String): String = tag.removePrefix("v").removePrefix("V")

/**
 * Pre-release markers: matched as a word, or after a separator or a digit, so "1.2.0-arc.3"
 * stays stable ("arc" is not "rc") while "29.47-beta" and "1.0beta2" count as pre-releases.
 * Same rule as the TV sideloaded-app update badges.
 */
private val PRERELEASE_RE = Regex("(?i)(?<![a-z])(alpha|beta|rc|dev|pre|snapshot|nightly)(?![a-z])")
private val NUMERIC_RUNS_RE = Regex("\\d+")

/**
 * Compares the numeric runs in two version names: "1.2.0-arc.3" is [1, 2, 0, 3], "32.80s" is
 * [32, 80]. Missing parts count as zero, so "1.0" equals "1.0.0". Returns null when either side
 * is a pre-release or has no digits at all: uncertain versions never report an update.
 */
fun compareCompanionVersions(latest: String, installed: String): Int? {
    if (PRERELEASE_RE.containsMatchIn(latest) || PRERELEASE_RE.containsMatchIn(installed)) return null
    val a = numericRuns(latest)
    val b = numericRuns(installed)
    if (a.isEmpty() || b.isEmpty()) return null
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val diff = a.getOrElse(i) { 0L }.compareTo(b.getOrElse(i) { 0L })
        if (diff != 0) return diff
    }
    return 0
}

/** The numeric runs in a version name, or an empty list when it has no digits. */
private fun numericRuns(v: String): List<Long> {
    val out = ArrayList<Long>()
    for (m in NUMERIC_RUNS_RE.findAll(v)) {
        out.add(m.value.toLongOrNull() ?: return emptyList())
    }
    return out
}

private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

/**
 * Returns the lowercase hex SHA-256 for [fileName]'s exact line in a SHA256SUMS text, or null.
 * Accepts `hash  name`, `hash *name` (binary marker) and CRLF endings, and ignores other files'
 * lines (including versioned twins like `SideSpeed-Companion-0.7.0.apk`). The hash must be 64 hex chars.
 */
fun parseSha256Sums(text: String, fileName: String): String? {
    for (raw in text.split('\n')) {
        val line = raw.trimEnd('\r').trim()
        if (line.isEmpty()) continue
        val parts = line.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) continue
        val (hash, name) = parts
        if (!HEX64.matches(hash)) continue
        val bare = if (name.startsWith("*")) name.substring(1) else name
        if (bare == fileName) return hash.lowercase()
    }
    return null
}

/** A downloaded APK's identity: package and signing-certificate SHA-256 set. */
data class ApkFacts(val packageName: String, val signerSha256: Set<String>)

/** Either verified or rejected with a one-line plain reason. */
sealed interface CompanionVerifyResult {
    data object Ok : CompanionVerifyResult
    data class Rejected(val reason: String) : CompanionVerifyResult
}

/** All checks, in order: checksum, parse, package, signer set equality. */
fun verifyCompanionApk(
    fileSha256: String,
    expectedSha256: String?,
    apk: ApkFacts?,
    installedSignerSha256: Set<String>,
): CompanionVerifyResult {
    if (expectedSha256 == null) return CompanionVerifyResult.Rejected("No checksum found for $COMPANION_APK_NAME.")
    if (!fileSha256.equals(expectedSha256, ignoreCase = true)) {
        return CompanionVerifyResult.Rejected("Checksum mismatch.")
    }
    if (apk == null) return CompanionVerifyResult.Rejected("Could not read the downloaded APK.")
    if (apk.packageName != COMPANION_PACKAGE) return CompanionVerifyResult.Rejected("Wrong package: ${apk.packageName}.")
    if (apk.signerSha256.isEmpty() || installedSignerSha256.isEmpty()) {
        return CompanionVerifyResult.Rejected("Signer unknown: refusing the update.")
    }
    if (apk.signerSha256 != installedSignerSha256) {
        return CompanionVerifyResult.Rejected("Signer does not match the installed app.")
    }
    return CompanionVerifyResult.Ok
}

/** Fixed desktop asset names served under `releases/latest/download/`. */
const val MACOS_ASSET = "SideSpeed-Companion-macos-arm64.dmg"
const val WINDOWS_ASSET = "SideSpeed-Companion-windows-x64.msi"
const val LINUX_DEB_ASSET = "SideSpeed-Companion-linux-amd64.deb"
const val LINUX_RPM_ASSET = "SideSpeed-Companion-linux-x86_64.rpm"

/**
 * Picks the desktop asset for an OS/arch pair, or null when nothing fits (then the caller opens
 * the releases page). macOS has only an arm64 build; Windows takes the x64 MSI; Linux takes the
 * .deb when dpkg is present and the .rpm otherwise. Builds are not code-signed or notarized, so
 * the desktop app never installs silently: this only chooses what the browser opens.
 */
fun pickDesktopAsset(osName: String, arch: String, dpkgPresent: Boolean): String? {
    val os = osName.lowercase()
    val a = arch.lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") ->
            if (a == "aarch64" || a == "arm64") MACOS_ASSET else null
        os.contains("win") -> WINDOWS_ASSET
        os.contains("linux") || os.contains("nix") || os.contains("nux") ->
            if (a == "amd64" || a == "x86_64") {
                if (dpkgPresent) LINUX_DEB_ASSET else LINUX_RPM_ASSET
            } else {
                null
            }
        else -> null
    }
}

/** Full download URL for a fixed-name release asset. */
fun desktopDownloadUrl(asset: String): String = "$LATEST_DOWNLOAD_BASE_URL/$asset"

/**
 * About-screen update state. The check ([CompanionUpdateCheck]) only ever produces Checking,
 * UpToDate, Available and Failed; the Android app additionally drives Downloading and Installing
 * during its in-app update.
 */
sealed interface CompanionUpdateState {
    data object Idle : CompanionUpdateState
    data object Checking : CompanionUpdateState
    data object UpToDate : CompanionUpdateState
    data class Available(val tag: String) : CompanionUpdateState
    data class Downloading(val percent: Int) : CompanionUpdateState
    data object Installing : CompanionUpdateState
    data class Failed(val reason: String) : CompanionUpdateState
}

/**
 * One manual update check against the latest GitHub release. No throttle and no background
 * polling: the About screen runs this when it opens and when its button is pressed.
 */
class CompanionUpdateCheck(
    private val fetchJson: suspend () -> String,
    private val installedVersion: String,
) {
    suspend fun checkNow(): CompanionUpdateState {
        val json = try {
            fetchJson()
        } catch (_: Exception) {
            return CompanionUpdateState.Failed(UPDATE_OFFLINE_MESSAGE)
        }
        val tag = parseLatestTag(json) ?: return CompanionUpdateState.Failed(UPDATE_OFFLINE_MESSAGE)
        // Same rule as the TV badges: pre-releases and uncertain versions never report an update.
        val cmp = compareCompanionVersions(tag, installedVersion)
            ?: return CompanionUpdateState.UpToDate
        return if (cmp > 0) CompanionUpdateState.Available(tag) else CompanionUpdateState.UpToDate
    }
}
