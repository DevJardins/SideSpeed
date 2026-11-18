package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Minimum time between update checks: the release endpoint is polled at most once per 24 h. */
const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

private val TAG_NAME = Regex("\"tag_name\"\\s*:\\s*\"([^\"]*)\"")

/** Extracts `tag_name` from a `releases/latest` payload; null when missing or empty. */
fun parseLatestTag(json: String): String? =
    TAG_NAME.find(json)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

/**
 * Numeric dotted comparison. A leading `v` and any `-suffix`/`+suffix` (e.g. `-beta`) are ignored;
 * a version with no numeric segments compares equal to everything (garbage never triggers a notice).
 */
fun compareVersions(a: String, b: String): Int {
    val pa = parseVersion(a) ?: return 0
    val pb = parseVersion(b) ?: return 0
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private fun parseVersion(v: String): List<Int>? {
    val core = v.trim().removePrefix("v").removePrefix("V").split('-', '+').first()
    if (core.isEmpty()) return null
    return core.split('.').map { part ->
        part.takeWhile { it.isDigit() }.ifEmpty { return null }.toIntOrNull() ?: return null
    }
}

/** True when [latest] is a real version newer than [current] and the user hasn't dismissed it. */
fun shouldShowBanner(latest: String?, dismissed: String?, current: String): Boolean {
    if (latest.isNullOrEmpty()) return false
    if (dismissed == latest) return false
    return compareVersions(latest, current) > 0
}

/** Production fetch: the latest SideSpeed release metadata. Failures (offline, rate limit) throw. */
fun githubReleaseFetch(): suspend () -> String = {
    withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/DevJardins/SideSpeed/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "SideSpeed")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Polls GitHub for a newer SideSpeed release, at most once per 24 h. Every outcome is silent:
 * offline, rate-limited, or unparsable answers keep the previous latest version (if any) and just
 * move the last-check timestamp forward so we don't hammer the API.
 */
class UpdateCheck(
    private val fetch: suspend () -> String,
    private val store: ToolsStore,
    private val clock: () -> Long,
    private val currentVersion: String,
) {
    suspend fun checkIfDue() {
        val now = clock()
        if (now - store.updateLastCheckMs.first() < UPDATE_CHECK_INTERVAL_MS) return
        val latest = try {
            parseLatestTag(fetch())
        } catch (_: Exception) {
            null
        }
        store.setUpdateCheck(now, latest ?: store.updateLatestVersion.first())
    }

    /** The stored latest version, for callers that need it right after [checkIfDue]. */
    suspend fun latestNewerThanCurrent(): String? {
        val latest = store.updateLatestVersion.first()
        return if (shouldShowBanner(latest, store.updateDismissedVersion.first(), currentVersion)) latest else null
    }
}
