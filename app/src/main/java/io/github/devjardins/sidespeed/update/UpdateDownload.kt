package io.github.devjardins.sidespeed.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

const val MAX_APK_BYTES = 67_108_664L
private const val MAX_REDIRECTS = 5
private const val TAG_RE = "^v?[0-9]+(\\.[0-9]+){1,3}$"
private const val RELEASE_BASE = "https://github.com/DevJardins/SideSpeed/releases/download"

/** Release asset URL, or null when [tag] is not a plain version tag. */
fun releaseAssetUrl(tag: String, asset: String, baseUrl: String = RELEASE_BASE): String? {
    if (!Regex(TAG_RE).matches(tag)) return null
    if (asset.isEmpty() || '/' in asset || asset == "." || asset == "..") return null
    return "$baseUrl/$tag/$asset"
}

/** Downloads release files over HTTPS with redirect, cap and cleanup handling. */
class UpdateDownload(private val open: (url: String) -> Opened) {
    data class Opened(val finalUrl: String, val contentLength: Long, val body: InputStream)

    /** Downloads [url] to [dest] via `<dest>.tmp`, renamed only on complete success. */
    suspend fun downloadTo(
        url: String,
        dest: File,
        maxBytes: Long = MAX_APK_BYTES,
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
        if (!opened.finalUrl.startsWith("https://")) {
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
        if (!opened.finalUrl.startsWith("https://")) {
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
                if (!current.startsWith("https://")) throw IllegalStateException("Non-https URL: $current")
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
