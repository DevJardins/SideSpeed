package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.helper.CommandAllowlist
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The sideloaded-app catalog is refreshed at most once per 24 h. */
const val APP_UPDATES_INTERVAL_MS = 24 * 60 * 60 * 1000L

/**
 * Cap for the updates-v2.json body: the real catalog holds thousands of
 * F-Droid/IzzyOnDroid entries (~3.05 MB), so allow 8 MB before calling it a glitch.
 */
const val APP_UPDATES_MAX_BYTES = 8 * 1024 * 1024L

/**
 * One catalog entry from updates-v2.json. Only the version name is required;
 * the source and links are all optional. The catalog's version code, when
 * present, is ignored: catalog codes can differ from the installed build
 * (e.g. per-ABI codes), so the version name alone decides.
 */
data class UpdateEntry(
    val name: String = "",
    val versionName: String = "",
    val source: String? = null,
    val page: String? = null,
    val short: String? = null,
    /** Downloadable APKs of exactly [versionName]; empty for old entries (QR card only). */
    val apk: List<ApkVariant> = emptyList(),
)

/**
 * One downloadable APK of exactly the entry's version name. An empty
 * [abis] list means a universal build; an empty [signer] set means the
 * feed names no signing key.
 */
data class ApkVariant(
    val url: String,
    val sha256: String,
    val size: Long,
    val versionCode: Long,
    val abis: List<String> = emptyList(),
    val minSdk: Int? = null,
    val signer: Set<String> = emptySet(),
)

/**
 * A catalog entry proven newer than the installed app. The Compile screen
 * offers Update now only when [pickVariant] finds a variant for this TV;
 * otherwise the card is the QR and short link, and nothing is downloaded.
 */
data class AvailableUpdate(
    val packageName: String,
    val name: String,
    val versionName: String,
    val source: String?,
    val page: String?,
    val short: String?,
    val apk: List<ApkVariant> = emptyList(),
) {
    /** The link to show and encode: the short link when present, else the full page. */
    val link: String? get() = short?.takeIf { it.isNotBlank() } ?: page?.takeIf { it.isNotBlank() }
}

/**
 * Parses the updates-v2.json catalog. Unknown fields are ignored. Returns null
 * when the payload is not a schema-1 or schema-2 catalog (wrong or missing schema,
 * truncated JSON), so callers keep the last good copy instead. updates.json stays
 * schema 1 with no `apk` for old apps; v2 adds `apk` with the same entry shape.
 *
 * When [installedPackages] is given, only entries for those packages are
 * kept, so the in-memory map stays small against a thousand-entry catalog.
 */
fun parseAppUpdates(json: String, installedPackages: Set<String>? = null): Map<String, UpdateEntry>? {
    val root = try {
        JSONObject(json)
    } catch (_: JSONException) {
        return null
    }
    if (root.optInt("schema", -1) != 1 && root.optInt("schema", -1) != 2) return null
    val apps = try {
        root.getJSONObject("apps")
    } catch (_: JSONException) {
        return null
    }
    val out = LinkedHashMap<String, UpdateEntry>()
    val keys = apps.keys()
    while (keys.hasNext()) {
        val pkg = keys.next()
        if (installedPackages != null && pkg !in installedPackages) continue
        val entry = try {
            apps.getJSONObject(pkg)
        } catch (_: JSONException) {
            continue
        }
        val versionName = entry.opt("versionName") as? String ?: continue
        if (versionName.isBlank()) continue
        out[pkg] = UpdateEntry(
            name = entry.opt("name") as? String ?: "",
            versionName = versionName,
            source = entry.opt("source") as? String,
            page = entry.opt("page") as? String,
            short = entry.opt("short") as? String,
            apk = parseApkVariants(entry.opt("apk")),
        )
    }
    return out
}

/** A variant's checksum: exactly 64 lowercase hex chars, as the feed writes it. */
private val VARIANT_HEX64 = Regex("^[0-9a-f]{64}$")

/** A variant's signer fingerprint: the same 64 lowercase hex chars. */
private val SIGNER_HEX64 = Regex("^[0-9a-f]{64}$")

/**
 * The entry's `apk` array, dropping any variant that is not well formed:
 * non-https URL, bad checksum, bad size, missing version code, or a
 * malformed ABI or signer list. A missing or non-array value means an
 * old entry with no downloads.
 */
private fun parseApkVariants(raw: Any?): List<ApkVariant> {
    if (raw == null || raw == JSONObject.NULL || raw !is JSONArray) return emptyList()
    val out = ArrayList<ApkVariant>(raw.length())
    for (i in 0 until raw.length()) {
        (raw.opt(i) as? JSONObject)?.let { parseApkVariant(it)?.let(out::add) }
    }
    return out
}

/** One well-formed variant, or null. See [parseApkVariants] for the rules. */
private fun parseApkVariant(o: JSONObject): ApkVariant? {
    val url = (o.opt("url") as? String)
        ?.takeIf { it.startsWith("https://") && it.length > "https://".length }
        ?: return null
    val sha256 = (o.opt("sha256") as? String)?.takeIf { VARIANT_HEX64.matches(it) } ?: return null
    val size = (o.opt("size") as? Number)?.toLong()
        ?.takeIf { it in 1..CommandAllowlist.MAX_UPDATE_BYTES }
        ?: return null
    if (o.isNull("versionCode")) return null
    val versionCode = (o.opt("versionCode") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val abis = when (val a = o.opt("abis")) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until a.length()).map { a.opt(it) as? String ?: return null }
        else -> return null
    }
    val minSdk = when (val m = o.opt("minSdk")) {
        null, JSONObject.NULL -> null
        is Number -> m.toLong().takeIf { it >= 0 }?.toInt() ?: return null
        else -> return null
    }
    val signer = when (val s = o.opt("signer")) {
        null, JSONObject.NULL -> emptySet()
        is JSONArray -> (0 until s.length()).mapTo(LinkedHashSet()) {
            (s.opt(it) as? String)?.takeIf { SIGNER_HEX64.matches(it) } ?: return null
        }
        else -> return null
    }
    return ApkVariant(url, sha256, size, versionCode, abis, minSdk, signer)
}

/**
 * The variant to offer Update now for, or null when none fits this TV (then
 * the card stays QR-only). A variant fits when its [ApkVariant.minSdk] is
 * at most [sdk] (or absent), its version code is newer than the installed
 * one, and its signer set is empty (the feed names none) or equals the
 * installed set. Among the fits, the first ABI in [supportedAbis] order
 * (from `Build.SUPPORTED_ABIS`) that some variant lists wins; otherwise a
 * universal variant (empty [ApkVariant.abis]) wins.
 */
fun pickVariant(
    variants: List<ApkVariant>,
    supportedAbis: List<String>,
    sdk: Int,
    installedVersionCode: Long,
    installedSigners: Set<String>,
): ApkVariant? {
    val fitting = variants.filter { v ->
        (v.minSdk == null || v.minSdk <= sdk) &&
            v.versionCode > installedVersionCode &&
            (v.signer.isEmpty() || v.signer == installedSigners)
    }
    if (fitting.isEmpty()) return null
    for (abi in supportedAbis) {
        fitting.firstOrNull { abi in it.abis }?.let { return it }
    }
    return fitting.firstOrNull { it.abis.isEmpty() }
}

/**
 * Pre-release markers: matched as a word, or after a separator or a digit,
 * so "1.2.0-arc.3" stays stable ("arc" is not "rc") while "29.47-beta" and
 * "1.0beta2" count as pre-releases.
 */
private val PRERELEASE_RE = Regex("(?i)(?<![a-z])(alpha|beta|rc|dev|pre|snapshot|nightly)(?![a-z])")
private val NUMERIC_RUNS_RE = Regex("\\d+")

/**
 * Compares the sequence of numeric runs in two version names: "1.2.0-arc.3"
 * is [1, 2, 0, 3], "32.80s" is [32, 80], "v29.47" is [29, 47]. Missing parts
 * count as zero, so "1.0" equals "1.0.0". Returns null when either side is a
 * pre-release or has no digits at all: uncertain versions never badge.
 */
fun compareVersionNames(catalog: String, installed: String): Int? {
    if (PRERELEASE_RE.containsMatchIn(catalog) || PRERELEASE_RE.containsMatchIn(installed)) return null
    val a = numericRuns(catalog)
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

/**
 * The update to badge, or null when there is none. A badge needs certainty:
 * the app must be installed, non-system, and not store-installed, and the
 * catalog version name must compare strictly greater by numeric runs.
 * Pre-release names, equal or older versions, and uncertain comparisons
 * never badge. The version code is never consulted.
 */
fun updateFor(
    app: AppEntry,
    installedVersionName: String?,
    entry: UpdateEntry?,
): AvailableUpdate? {
    if (entry == null) return null
    if (!app.isSideloaded) return null
    if (entry.versionName.isBlank()) return null
    val installed = installedVersionName?.takeIf { it.isNotBlank() } ?: return null
    if ((compareVersionNames(entry.versionName, installed) ?: return null) <= 0) return null
    return AvailableUpdate(
        packageName = app.packageName,
        name = entry.name.ifBlank { app.label },
        versionName = entry.versionName,
        source = entry.source,
        page = entry.page,
        short = entry.short,
        apk = entry.apk,
    )
}

/** "Update: 29.47": the My Apps row pill for an app with a proven newer version. */
fun updatePillLabel(versionName: String): String = "Update: $versionName"

/** "SmartTube 29.47 is out. You have 29.46.": the Compile screen card title. */
fun updateCardTitle(name: String, latest: String, installed: String): String =
    "$name $latest is out. You have $installed."

/** "From GitHub." and friends; null when the source is missing or unknown. */
fun updateSourceLine(source: String?): String? = when (source?.lowercase()) {
    "github" -> "From GitHub."
    "fdroid" -> "From F-Droid."
    "izzy" -> "From IzzyOnDroid."
    else -> null
}

/** How many installed apps have a proven update: the Home tile's update count. */
fun myAppsUpdatesCount(apps: List<AppEntry>, catalog: Map<String, UpdateEntry>): Int =
    apps.count { updateFor(it, it.versionName, catalog[it.packageName]) != null }
