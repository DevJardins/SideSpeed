package io.github.devjardins.sidespeed.companion.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.devjardins.sidespeed.companion.install.ApkInspector
import io.github.devjardins.sidespeed.companion.install.InstallSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

private const val STAGING_DIR = "install-staging"

/** Deletes staged bundles a killed process left behind. Run at process start, before any install can begin. */
fun clearStaleInstallStaging(cacheDir: File) {
    File(cacheDir, STAGING_DIR).listFiles()?.forEach { runCatching { it.delete() } }
}

/**
 * A file picked through the system file picker. A single `.apk` streams straight from the picker's stream and is
 * never copied. Anything else is a bundle: it is copied once into the cache, on first use of [asZipFile] (off the
 * UI thread, after the Trusted Network check), because reading one needs a seekable archive. The copy is deleted by
 * [close], and [clearStaleInstallStaging] removes any left by a killed process.
 */
class AndroidInstallSource(
    override val displayName: String,
    /** The size the picker reported, or a negative number when it did not say. */
    reportedSize: Long,
    private val opener: () -> InputStream,
    private val cacheDir: File,
    private val maxBytes: Long = ApkInspector.MAX_INSTALL_BYTES,
) : InstallSource {
    override val sizeBytes: Long? = reportedSize.takeIf { it >= 0 }
    private val isBundle = !displayName.endsWith(".apk", ignoreCase = true)
    private var staged: File? = null
    private var zip: ZipFile? = null
    private var stageFailed = false

    /** Set by [close] before it takes the lock, so a copy in progress stops at its next read. */
    @Volatile private var closed = false

    /** The picker stream a copy is reading, so [close] can unblock a read that is waiting on the provider. */
    @Volatile private var stagingInput: InputStream? = null

    @Synchronized
    override fun openStream(): InputStream = staged?.inputStream() ?: opener()

    @Synchronized
    override fun asZipFile(): ZipFile? {
        if (!isBundle || closed) return null
        zip?.let { return it }
        if (stageFailed) return null
        val dir = File(cacheDir, STAGING_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.zip")
        return try {
            copyCapped(file)
            if (closed) throw IOException("Closed while staging")
            staged = file
            ZipFile(file).also { zip = it }
        } catch (_: IOException) {
            // Too big, unreadable or cancelled: the caller falls back to the stream, which reports the real error.
            stageFailed = true
            file.delete()
            null
        }
    }

    private fun copyCapped(target: File) {
        opener().use { input ->
            stagingInput = input
            try {
                // Closed between the check above and taking the stream: stop before reading anything.
                if (closed) throw IOException("Closed while staging")
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (!closed) {
                        val n = input.read(buf)
                        if (n < 0) return
                        total += n
                        if (total > maxBytes) throw IOException("Install file is over the size limit")
                        out.write(buf, 0, n)
                    }
                    throw IOException("Closed while staging")
                }
            } finally {
                stagingInput = null
            }
        }
    }

    override fun close() {
        // Before the lock: a bundle copy holds it, and this makes the copy stop and delete its partial file.
        closed = true
        stagingInput?.let { runCatching { it.close() } }
        closeLocked()
    }

    @Synchronized
    private fun closeLocked() {
        runCatching { zip?.close() }
        zip = null
        staged?.let { runCatching { it.delete() } }
        staged = null
    }

    companion object {
        /** Reads the name and size the picker knows; both are cheap queries. */
        fun fromUri(resolver: ContentResolver, uri: Uri, cacheDir: File): AndroidInstallSource {
            var name = uri.lastPathSegment ?: "install.apk"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            }
            return AndroidInstallSource(
                name, size,
                { resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri") },
                cacheDir,
            )
        }
    }
}
