package io.github.devjardins.sidespeed.update

import java.io.File
import java.security.MessageDigest

private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
/** SideSpeed's own package: the only package a self-update may install. */
const val SELF_UPDATE_PACKAGE = "io.github.devjardins.sidespeed"

/** A parsed APK's identity: package, version, and signing-certificate SHA-256 set. */
data class ApkFacts(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

/** Either verified or rejected with a one-line plain reason. */
sealed interface VerifyResult {
    object Ok : VerifyResult
    data class Rejected(val reason: String) : VerifyResult
}

/**
 * Returns the lowercase hex SHA-256 for [fileName]'s exact line in a SHA256SUMS text, or null.
 * Accepts `hash  name`, `hash *name` (binary marker), CRLF endings, and ignores other files'
 * lines (including versioned twins like `SideSpeed-0.5.0.apk`). The hash must be 64 hex chars.
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

/**
 * All checks from the design, in order: checksum, parse, package, version, signer set equality.
 * [expectedPackage] is SideSpeed's own package for self-updates, or the updated app's package
 * for sideloaded-app updates. [expectedVersionCode] is null for self-updates (any newer version
 * is accepted) and the catalog variant's version code for app updates (the APK must match it
 * exactly, and it must be newer than the installed one).
 */
fun verifyUpdate(
    fileSha256: String,
    expectedSha256: String?,
    apk: ApkFacts?,
    installedVersionCode: Long,
    installedSignerSha256: Set<String>,
    expectedPackage: String = SELF_UPDATE_PACKAGE,
    expectedVersionCode: Long? = null,
): VerifyResult {
    if (expectedSha256 == null) return VerifyResult.Rejected("No checksum found for this update.")
    if (!fileSha256.equals(expectedSha256, ignoreCase = true)) {
        return VerifyResult.Rejected("Checksum mismatch.")
    }
    if (apk == null) return VerifyResult.Rejected("Could not read the downloaded APK.")
    if (apk.packageName != expectedPackage) return VerifyResult.Rejected("Wrong package: ${apk.packageName}.")
    if (expectedVersionCode != null && apk.versionCode != expectedVersionCode) {
        return VerifyResult.Rejected("Version does not match the update.")
    }
    if (apk.versionCode <= installedVersionCode) return VerifyResult.Rejected("Not newer than the installed version.")
    if (apk.signerSha256.isEmpty() || installedSignerSha256.isEmpty()) {
        return VerifyResult.Rejected("Signer unknown: refusing the update.")
    }
    if (apk.signerSha256 != installedSignerSha256) return VerifyResult.Rejected("Signer does not match the installed app.")
    return VerifyResult.Ok
}

/** Lowercase hex SHA-256 of a file's bytes. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
