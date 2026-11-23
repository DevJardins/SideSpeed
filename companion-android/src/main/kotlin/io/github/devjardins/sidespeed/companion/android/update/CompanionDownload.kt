package io.github.devjardins.sidespeed.companion.android.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_REDIRECTS = 5

/** Same 64 MB cap as the TV app's installs, so a runaway response can't fill the disk. */
const val MAX_APK_BYTES = 67_108_864L

/** Small-text cap for the checksums file. */
const val MAX_SUMS_BYTES = 65_536L

/** Downloads release files over HTTPS with redirect, cap and cleanup handling. */
class CompanionDownload(private val open: (url: String) -> Opened) {
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
        if (!isHttps(opened.finalUrl)) {
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

    /** Fetches a small text file (checksums, release metadata), capped at [maxBytes]. */
    suspend fun fetchText(url: String, maxBytes: Long = MAX_SUMS_BYTES): Result<String> {
        val opened: Opened = try {
            open(url)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (!isHttps(opened.finalUrl)) {
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
        private fun isHttps(url: String): Boolean = url.startsWith("https://")

        /**
         * Production opener: follows redirects manually, HTTPS only, at most [MAX_REDIRECTS] hops.
         * Throws on a non-https hop or too many hops.
         */
        fun httpOpen(url: String): Opened {
            var current = url
            repeat(MAX_REDIRECTS + 1) {
                if (!isHttps(current)) {
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
