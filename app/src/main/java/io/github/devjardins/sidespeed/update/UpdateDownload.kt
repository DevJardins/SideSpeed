package io.github.devjardins.sidespeed.update

import io.github.devjardins.sidespeed.BuildConfig
import io.github.devjardins.sidespeed.helper.CommandAllowlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_REDIRECTS = 5
private const val TAG_RE = "^v?[0-9]+(\\.[0-9]+){1,3}$"

/**
 * Release asset URL, or null when [tag] is not a plain version tag. Plain http is never allowed,
 * except the debug test hook: `http://10.0.2.2` (the emulator's host loopback) in debug builds.
 */
fun releaseAssetUrl(
    tag: String,
    asset: String,
    baseUrl: String = BuildConfig.UPDATE_BASE_URL,
): String? {
    if (!Regex(TAG_RE).matches(tag)) return null
    if (asset.isEmpty() || '/' in asset || asset == "." || asset == "..") return null
    val base = baseUrl.trimEnd('/')
    if (base.startsWith("https://")) return "$base/$tag/$asset"
    if (isDebugHttpUrl(base)) return "$base/$tag/$asset"
    return null
}

/**
 * Debug builds only: plain http whose host is the emulator host loopback (host-matched, so no
 * suffix tricks). Applies to bases and to full URLs, since the host parse drops any path.
 */
private fun isDebugHttpUrl(url: String): Boolean {
    if (!BuildConfig.DEBUG) return false
    if (!url.startsWith("http://")) return false
    return url.removePrefix("http://").substringBefore('/').substringBefore(':') == "10.0.2.2"
}

/** Final-URL gate for downloads: https, or the debug emulator-host hook. */
private fun isAllowedFinalUrl(url: String): Boolean =
    url.startsWith("https://") || isDebugHttpUrl(url)

/** Downloads release files over HTTPS with redirect, cap and cleanup handling. */
class UpdateDownload(private val open: (url: String) -> Opened) {
    data class Opened(val finalUrl: String, val contentLength: Long, val body: InputStream)

    /** Downloads [url] to [dest] via `<dest>.tmp`, renamed only on complete success. */
    suspend fun downloadTo(
        url: String,
        dest: File,
        maxBytes: Long = CommandAllowlist.MAX_INSTALL_BYTES,
        onProgress: (Int) -> Unit = {},
    ): Result<File> {
        val part = File(dest.path + ".tmp")
        fun fail(e: Throwable): Result<File> {
            part.delete()
            dest.delete()
            return Result.failure(e)
        }
        val opened: Opened = try {
            open(url)
        } catch (e: Exception) {
            return fail(e)
        }
        if (!isAllowedFinalUrl(opened.finalUrl)) {
            opened.body.close()
            return fail(IllegalStateException("Non-https final URL: ${opened.finalUrl}"))
        }
        if (opened.contentLength > maxBytes) {
            opened.body.close()
            return fail(IllegalStateException("Content length ${opened.contentLength} exceeds cap $maxBytes"))
        }
        return try {
            opened.body.use { body ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = body.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return fail(IllegalStateException("Download exceeds cap $maxBytes"))
                        out.write(buf, 0, n)
                        if (opened.contentLength > 0) {
                            val percent = ((total * 100) / opened.contentLength).toInt().coerceAtMost(100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    if (opened.contentLength >= 0 && total != opened.contentLength) {
                        return fail(IllegalStateException("Short body: got $total of ${opened.contentLength}"))
                    }
                    if (lastPercent != 100) onProgress(100)
                }
            }
            if (!part.renameTo(dest)) {
                return fail(IllegalStateException("Could not move download into place"))
            }
            Result.success(dest)
        } catch (e: CancellationException) {
            part.delete()
            dest.delete()
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Fetches a small text file (checksums), capped at [maxBytes]. */
    suspend fun fetchText(url: String, maxBytes: Long = 65_536): Result<String> {
        val opened: Opened = try {
            open(url)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (!isAllowedFinalUrl(opened.finalUrl)) {
            opened.body.close()
            return Result.failure(IllegalStateException("Non-https final URL: ${opened.finalUrl}"))
        }
        if (opened.contentLength > maxBytes) {
            opened.body.close()
            return Result.failure(IllegalStateException("Text length ${opened.contentLength} exceeds cap $maxBytes"))
        }
        return try {
            opened.body.use { body ->
                val out = StringBuilder()
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = body.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return Result.failure(IllegalStateException("Text exceeds cap $maxBytes"))
                    out.append(String(buf, 0, n, Charsets.UTF_8))
                }
                Result.success(out.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /**
         * Production opener: follows redirects manually, HTTPS only, at most [MAX_REDIRECTS] hops.
         * Throws on a non-https hop or too many hops.
         */
        fun httpOpen(url: String): Opened {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                if (!isAllowedFinalUrl(current)) {
                    throw IllegalStateException("Non-https URL: $current")
                }
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location from $current")
                    conn.disconnect()
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code from $current")
                }
                return Opened(current, conn.contentLengthLong, conn.inputStream)
            }
            throw IllegalStateException("Too many redirects from $url")
        }
    }
}
