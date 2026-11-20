package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.apps.AppEntry

/** The sideloaded-app catalog is refreshed at most once per 24 h. */
const val APP_UPDATES_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** Cap for the updates.json body: the catalog is small metadata; anything larger is a glitch. */
const val APP_UPDATES_MAX_BYTES = 256 * 1024L

/**
 * One catalog entry from updates.json. Only the version name is required;
 * the version code, source, and links are all optional.
 */
data class UpdateEntry(
    val name: String = "",
    val versionName: String = "",
    val versionCode: Long? = null,
    val source: String? = null,
    val page: String? = null,
    val short: String? = null,
)

/**
 * A catalog entry proven newer than the installed app. Notify only:
 * the app never downloads or installs other apps.
 */
data class AvailableUpdate(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long?,
    val source: String?,
    val page: String?,
    val short: String?,
) {
    /** The link to show and encode: the short link when present, else the full page. */
    val link: String? get() = short?.takeIf { it.isNotBlank() } ?: page?.takeIf { it.isNotBlank() }
}

private val SCHEMA_RE = Regex("\"schema\"\\s*:\\s*([0-9]+)")
private val APPS_RE = Regex("\"apps\"\\s*:\\s*\\{")
private val ENTRY_RE = Regex("\"([^\"]+)\"\\s*:\\s*\\{([^{}]*)\\}")
private val STRING_RE = Regex("\"([A-Za-z]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val CODE_RE = Regex("\"versionCode\"\\s*:\\s*([0-9]+)")

/**
 * Parses the updates.json catalog. Unknown fields are ignored. Returns null
 * when the payload is not a schema-1 catalog (wrong or missing schema,
 * truncated JSON), so callers keep the last good copy instead.
 */
fun parseAppUpdates(json: String): Map<String, UpdateEntry>? {
    if (SCHEMA_RE.find(json)?.groupValues?.get(1) != "1") return null
    val block = appsBlock(json) ?: return null
    val out = LinkedHashMap<String, UpdateEntry>()
    for (entry in ENTRY_RE.findAll(block)) {
        val pkg = entry.groupValues[1]
        val body = entry.groupValues[2]
        val strings = HashMap<String, String>()
        for (s in STRING_RE.findAll(body)) {
            strings.putIfAbsent(s.groupValues[1], unescape(s.groupValues[2]))
        }
        val versionName = strings["versionName"].orEmpty()
        if (versionName.isBlank()) continue
        out[pkg] = UpdateEntry(
            name = strings["name"].orEmpty(),
            versionName = versionName,
            versionCode = CODE_RE.find(body)?.groupValues?.get(1)?.toLongOrNull(),
            source = strings["source"],
            page = strings["page"],
            short = strings["short"],
        )
    }
    return out
}

/** The `"apps": {...}` block, brace-matched so entry regexes never escape it. Null when truncated. */
private fun appsBlock(json: String): String? {
    val open = APPS_RE.find(json) ?: return null
    var depth = 0
    var inString = false
    var escaped = false
    var i = open.range.last
    val start = i
    while (i < json.length) {
        val c = json[i]
        if (inString) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i + 1)
                }
            }
        }
        i++
    }
    return null
}

private fun unescape(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                '"', '\\', '/' -> out.append(raw[i + 1])
                'n' -> out.append('\n')
                't' -> out.append('\t')
                else -> {
                    out.append(c)
                    out.append(raw[i + 1])
                }
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Strict numeric dotted comparison, or null when either side is not purely
 * numeric (after one leading `v`/`V`). Missing trailing parts count as zero,
 * so "1.0" equals "1.0.0". Anything uncertain compares as "not newer".
 */
fun compareStrictVersions(catalog: String, installed: String): Int? {
    val a = parseStrict(catalog) ?: return null
    val b = parseStrict(installed) ?: return null
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private fun parseStrict(v: String): List<Int>? {
    val core = v.trim().removePrefix("v").removePrefix("V")
    if (core.isEmpty()) return null
    return core.split('.').map { part ->
        if (part.isEmpty() || part.any { !it.isDigit() }) return null
        part.toIntOrNull() ?: return null
    }
}

/**
 * The update to badge, or null when there is none. A badge needs certainty:
 * the app must be installed, non-system, and not store-installed; the version
 * code decides when the catalog has one, else the version name must be purely
 * numeric and greater. Equal, older, or uncertain versions never badge.
 */
fun updateFor(
    app: AppEntry,
    installedVersionName: String?,
    installedVersionCode: Long,
    entry: UpdateEntry?,
): AvailableUpdate? {
    if (entry == null) return null
    if (!app.isSideloaded) return null
    if (entry.versionName.isBlank()) return null
    val newer = if (entry.versionCode != null) {
        installedVersionCode < entry.versionCode
    } else {
        val installed = installedVersionName?.takeIf { it.isNotBlank() } ?: return null
        (compareStrictVersions(entry.versionName, installed) ?: return null) > 0
    }
    if (!newer) return null
    return AvailableUpdate(
        packageName = app.packageName,
        name = entry.name.ifBlank { app.label },
        versionName = entry.versionName,
        versionCode = entry.versionCode,
        source = entry.source,
        page = entry.page,
        short = entry.short,
    )
}

/** "Update: 29.47": the My Apps row pill for an app with a proven newer version. */
fun updatePillLabel(versionName: String): String = "Update: $versionName"

/** "SmartTube 29.47 is out (you have 29.46.)": the Compile screen card title. */
fun updateCardTitle(name: String, latest: String, installed: String): String =
    "$name $latest is out (you have $installed.)"

/** "From GitHub." and friends; null when the source is missing or unknown. */
fun updateSourceLine(source: String?): String? = when (source?.lowercase()) {
    "github" -> "From GitHub."
    "fdroid" -> "From F-Droid."
    "izzy" -> "From IzzyOnDroid."
    else -> null
}

/** How many installed apps have a proven update: the Home tile's update count. */
fun myAppsUpdatesCount(apps: List<AppEntry>, catalog: Map<String, UpdateEntry>): Int =
    apps.count { updateFor(it, it.versionName, it.versionCode, catalog[it.packageName]) != null }
