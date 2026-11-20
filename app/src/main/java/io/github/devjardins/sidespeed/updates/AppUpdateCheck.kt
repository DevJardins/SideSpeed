package io.github.devjardins.sidespeed.updates

import io.github.devjardins.sidespeed.BuildConfig
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_REDIRECTS = 5

/** Outcome of one catalog fetch. Failures stay silent: the last good copy is kept. */
sealed interface AppUpdateOutcome {
    data object NotModified : AppUpdateOutcome
    data class Data(val body: String, val etag: String?) : AppUpdateOutcome
    data object Failed : AppUpdateOutcome
}

/**
 * Refreshes the sideloaded-app catalog at most once per 24 h. A 304 keeps the
 * stored copy; a 200 stores the new body and ETag. Failures and unparsable
 * bodies keep the last good copy; every outcome moves the check time forward
 * so a dead endpoint is not hammered.
 */
class AppUpdateCheck(
    private val fetch: suspend (etag: String?) -> AppUpdateOutcome,
    private val store: AppUpdateStore,
    private val clock: () -> Long,
) {
    suspend fun checkIfDue() {
        val now = clock()
        if (now - store.lastCheckMs.first() < APP_UPDATES_INTERVAL_MS) return
        val etag = store.etag.first()
        val oldJson = store.json.first()
        val outcome = try {
            fetch(etag)
        } catch (_: Exception) {
            AppUpdateOutcome.Failed
        }
        when (outcome) {
            is AppUpdateOutcome.Data ->
                if (parseAppUpdates(outcome.body) == null) {
                    store.setCheck(now, oldJson, etag)
                } else {
                    store.setCheck(now, outcome.body, outcome.etag)
                }
            AppUpdateOutcome.NotModified -> store.setCheck(now, oldJson, etag)
            AppUpdateOutcome.Failed -> store.setCheck(now, oldJson, etag)
        }
    }
}

/** Debug builds only: plain http to the emulator host loopback (host-matched, no suffix tricks). */
private fun isDebugHttpUrl(url: String): Boolean {
    if (!BuildConfig.DEBUG) return false
    if (!url.startsWith("http://")) return false
    return url.removePrefix("http://").substringBefore('/').substringBefore(':') == "10.0.2.2"
}

private fun isAllowedFinalUrl(url: String): Boolean =
    url.startsWith("https://") || isDebugHttpUrl(url)

/**
 * Production fetch of the catalog: conditional GET with the stored ETag,
 * 15 s connect / 30 s read timeouts, capped body, manual redirects.
 * Throws or returns [AppUpdateOutcome.Failed] when quiet is the answer.
 */
fun appUpdateFetch(url: String, etag: String?): AppUpdateOutcome {
    if (!isAllowedFinalUrl(url)) return AppUpdateOutcome.Failed
    var current = url
    repeat(MAX_REDIRECTS + 1) {
        val conn = URL(current).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (etag != null) conn.setRequestProperty("If-None-Match", etag)
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            conn.disconnect()
            return AppUpdateOutcome.NotModified
        }
        if (code in 300..399) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            current = loc?.let { URL(URL(current), it).toString() } ?: return AppUpdateOutcome.Failed
            if (!isAllowedFinalUrl(current)) return AppUpdateOutcome.Failed
            return@repeat
        }
        if (code !in 200..299) {
            conn.disconnect()
            return AppUpdateOutcome.Failed
        }
        if (conn.contentLengthLong > APP_UPDATES_MAX_BYTES) {
            conn.disconnect()
            return AppUpdateOutcome.Failed
        }
        return try {
            conn.inputStream.use { body ->
                val out = StringBuilder()
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > APP_UPDATES_MAX_BYTES) return AppUpdateOutcome.Failed
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
                AppUpdateOutcome.Data(out.toString(), conn.getHeaderField("ETag"))
            }
        } catch (_: Exception) {
            AppUpdateOutcome.Failed
        } finally {
            conn.disconnect()
        }
    }
    return AppUpdateOutcome.Failed
}

/** Production fetch wired to the catalog URL (debug builds honor the emulator override). */
fun httpAppUpdateFetch(url: String = BuildConfig.APP_UPDATES_URL): suspend (String?) -> AppUpdateOutcome =
    { etag -> appUpdateFetch(url, etag) }
