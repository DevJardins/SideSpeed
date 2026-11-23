package io.github.devjardins.sidespeed.helper

import java.io.File

/**
 * Log privacy for the shell-uid helper. Plain JVM (no android.*) so it is unit-tested;
 * [HelperMain] supplies the chmod that makes the files owner-only.
 */
object HelperLog {
    /** At most this many chars of a refused request reach the log. */
    const val MAX_REQUEST_CHARS = 80

    /** Log files are owner-only: the helper's shell uid reads them over adb shell. */
    const val LOG_MODE = 384 // 0600: the Companion reads the log over adb shell, the same uid

    /**
     * Request text safe for the log: every char outside printable ASCII (0x20..0x7E)
     * becomes `?`, at most [MAX_REQUEST_CHARS] chars are kept, and anything cut is
     * replaced by `…(N bytes)` with the request's UTF-8 byte count.
     */
    fun sanitize(request: String): String {
        val clean = StringBuilder(request.length)
        for (c in request) clean.append(if (c.code in 0x20..0x7E) c else '?')
        if (clean.length <= MAX_REQUEST_CHARS) return clean.toString()
        return clean.substring(0, MAX_REQUEST_CHARS) +
            "…(${request.toByteArray(Charsets.UTF_8).size} bytes)"
    }

    /**
     * Makes [file] owner-only. [chmod] is injected ([HelperMain] passes Os.chmod)
     * so tests use a fake.
     */
    fun ensurePrivate(file: File, chmod: (file: File, mode: Int) -> Unit) {
        chmod(file, LOG_MODE)
    }
}
