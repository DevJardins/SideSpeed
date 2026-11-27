package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.companion.store.MiniJson
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Inspects a local or streamed APK or split bundle (`.apks`, `.xapk`, `.apkm`, `.zip`) without executing code
 * or writing ZIP entry paths to disk.
 */
object ApkInspector {
    const val MAX_INSTALL_BYTES = 2_147_483_648L
    const val MAX_BUNDLE_ENTRIES = 1_024
    const val MAX_BUNDLE_APKS = 512
    const val MAX_MANIFEST_BYTES = 4_194_304
    private const val MAX_METADATA_BYTES = 1_048_576
    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    private class SizeLimitExceededException : IOException()

    fun inspect(source: InstallSource): Result<InspectedPackage> {
        val reportedSize = source.sizeBytes
        if (reportedSize != null) {
            if (reportedSize < 0L) return InstallError.CorruptArchive.asFailure()
            if (reportedSize > MAX_INSTALL_BYTES) return InstallError.TooLarge.asFailure()
        }

        val hasMagic = try {
            source.openStream().use { hasZipLocalHeaderMagic(it) }
        } catch (e: IOException) {
            return Result.failure(InstallException(InstallError.CorruptArchive, e))
        }
        if (!hasMagic) return InstallError.NotAnApk.asFailure()

        val zipFile = source.asZipFile()
        return if (zipFile != null) {
            inspectZipFile(source, zipFile, reportedSize)
        } else {
            inspectStreamedApk(source, reportedSize)
        }
    }

    private fun inspectZipFile(
        source: InstallSource,
        zipFile: ZipFile,
        reportedSize: Long?,
    ): Result<InspectedPackage> {
        val rootManifest = zipFile.getEntry(MANIFEST_ENTRY)
        if (rootManifest != null && !rootManifest.isDirectory) {
            return inspectSingleApkInZipFile(source, zipFile, rootManifest, reportedSize)
        }

        val rawApks = ArrayList<Pair<ZipEntry, String>>()
        var entryCount = 0
        var totalApkCount = 0
        var apkmInfoEntry: ZipEntry? = null
        var xapkManifestEntry: ZipEntry? = null

        try {
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                if (entryCount > MAX_BUNDLE_ENTRIES) return InstallError.TooManyEntries.asFailure()

                val normalized = normalizeZipPath(entry.name)
                if (!entry.isDirectory && isObbPath(normalized)) {
                    return InstallError.ObbNotSupported.asFailure()
                }
                if (!entry.isDirectory) {
                    when {
                        normalized.equals("info.json", ignoreCase = true) -> apkmInfoEntry = entry
                        normalized.equals("manifest.json", ignoreCase = true) -> xapkManifestEntry = entry
                        normalized.endsWith(".apk", ignoreCase = true) -> {
                            totalApkCount++
                            if (totalApkCount > MAX_BUNDLE_APKS) return InstallError.TooManyEntries.asFailure()
                            rawApks += entry to normalized
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return Result.failure(InstallException(InstallError.CorruptArchive, e))
        }

        val hasSplitsDir = rawApks.any { it.second.startsWith("splits/", ignoreCase = true) }
        val apkEntries = if (hasSplitsDir) {
            rawApks.filterNot { it.second.startsWith("standalones/", ignoreCase = true) }
        } else {
            rawApks
        }
        if (apkEntries.isEmpty()) return InstallError.NotAnApk.asFailure()

        val inspectedEntries = ArrayList<ApkEntryMeta>(apkEntries.size)
        for ((entry, _) in apkEntries) {
            if (entry.size > MAX_INSTALL_BYTES) return InstallError.TooLarge.asFailure()
            val entryResult = inspectInnerApkEntry(zipFile, entry)
            val meta = entryResult.getOrElse { return Result.failure(it) }
            inspectedEntries += meta
        }

        val bases = inspectedEntries.filter { it.manifest.splitName == null }
        if (bases.isEmpty()) return InstallError.MissingBaseApk.asFailure()
        if (bases.size > 1) return InstallError.MixedBundle.asFailure()
        val base = bases.single()
        val splits = inspectedEntries.filter { it !== base }

        for (split in splits) {
            if (split.manifest.packageName != base.manifest.packageName ||
                split.manifest.versionCode != base.manifest.versionCode
            ) {
                return InstallError.MixedBundle.asFailure()
            }
        }

        val jsonHint = readJsonLabelHint(zipFile, apkmInfoEntry, "app_name")
            ?: readJsonLabelHint(zipFile, xapkManifestEntry, "name")
        val label = pickDisplayLabel(base.manifest, jsonHint, source.displayName)

        return Result.success(
            InspectedPackage(
                packageName = base.manifest.packageName,
                displayLabel = label,
                versionCode = base.manifest.versionCode,
                versionName = base.manifest.versionName,
                isBundle = true,
                base = base,
                splits = splits,
            ),
        )
    }

    private fun inspectSingleApkInZipFile(
        source: InstallSource,
        zipFile: ZipFile,
        manifestEntry: ZipEntry,
        reportedSize: Long?,
    ): Result<InspectedPackage> {
        if (manifestEntry.size > MAX_MANIFEST_BYTES) return InstallError.TooLarge.asFailure()
        val manifestBytes = try {
            zipFile.getInputStream(manifestEntry).use { readBoundedBytes(it, MAX_MANIFEST_BYTES) }
        } catch (e: IOException) {
            return Result.failure(InstallException(InstallError.CorruptArchive, e))
        } ?: return InstallError.TooLarge.asFailure()

        val manifest = AxmlManifestParser.parse(manifestBytes) ?: return InstallError.CorruptArchive.asFailure()
        val apkSize = if (reportedSize != null) {
            reportedSize
        } else {
            try {
                measureStreamSize(source)
            } catch (_: SizeLimitExceededException) {
                return InstallError.TooLarge.asFailure()
            } catch (e: IOException) {
                return Result.failure(InstallException(InstallError.CorruptArchive, e))
            }
        }

        val base = ApkEntryMeta(zipEntryName = null, sizeBytes = apkSize, manifest = manifest)
        return Result.success(
            InspectedPackage(
                packageName = manifest.packageName,
                displayLabel = pickDisplayLabel(manifest, null, source.displayName),
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
                isBundle = false,
                base = base,
                splits = emptyList(),
            ),
        )
    }

    private fun inspectInnerApkEntry(zipFile: ZipFile, entry: ZipEntry): Result<ApkEntryMeta> {
        if (entry.size > MAX_INSTALL_BYTES) return InstallError.TooLarge.asFailure()
        return try {
            zipFile.getInputStream(entry).use { rawStream ->
                val bounded = CountingBoundedInputStream(rawStream, MAX_INSTALL_BYTES)
                val pushback = PushbackInputStream(bounded, 4)
                val magic = ByteArray(4)
                val magicRead = readFullyUpTo(pushback, magic, 4)
                if (magicRead < 4 || !isZipMagic(magic)) {
                    return InstallError.CorruptArchive.asFailure()
                }
                pushback.unread(magic, 0, magicRead)

                val manifestBytes = readManifestFromZipStream(pushback)
                    ?: return InstallError.CorruptArchive.asFailure()
                val manifest = AxmlManifestParser.parse(manifestBytes)
                    ?: return InstallError.CorruptArchive.asFailure()
                val entrySize = if (entry.size >= 0L) {
                    entry.size
                } else {
                    drainBounded(bounded)
                    bounded.bytesRead
                }
                Result.success(
                    ApkEntryMeta(
                        zipEntryName = entry.name,
                        sizeBytes = entrySize,
                        manifest = manifest,
                    ),
                )
            }
        } catch (_: SizeLimitExceededException) {
            InstallError.TooLarge.asFailure()
        } catch (e: ZipException) {
            Result.failure(InstallException(InstallError.CorruptArchive, e))
        } catch (e: IOException) {
            Result.failure(InstallException(InstallError.CorruptArchive, e))
        }
    }

    private fun inspectStreamedApk(source: InstallSource, reportedSize: Long?): Result<InspectedPackage> {
        return try {
            source.openStream().use { rawStream ->
                val bounded = CountingBoundedInputStream(rawStream, MAX_INSTALL_BYTES)
                val zis = ZipInputStream(bounded)
                var manifestBytes: ByteArray? = null
                var sawObb = false
                var sawApkEntry = false
                var scannedUncompressed = 0L

                while (true) {
                    val entry = zis.nextEntry ?: break
                    val normalized = normalizeZipPath(entry.name)
                    if (!entry.isDirectory && isObbPath(normalized)) {
                        sawObb = true
                    }
                    if (!entry.isDirectory && normalized.endsWith(".apk", ignoreCase = true)) {
                        sawApkEntry = true
                    }
                    if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                        if (entry.size > MAX_MANIFEST_BYTES) {
                            return InstallError.TooLarge.asFailure()
                        }
                        manifestBytes = readBoundedBytes(zis, MAX_MANIFEST_BYTES)
                            ?: return InstallError.TooLarge.asFailure()
                        break
                    }
                    if (!entry.isDirectory) {
                        if (entry.size > MAX_INSTALL_BYTES) {
                            return InstallError.TooLarge.asFailure()
                        }
                        scannedUncompressed += drainEntryWithinBudget(
                            zis,
                            MAX_INSTALL_BYTES - scannedUncompressed,
                        )
                    }
                }

                if (manifestBytes == null) {
                    return when {
                        sawObb -> InstallError.ObbNotSupported.asFailure()
                        sawApkEntry -> InstallError.CorruptArchive.asFailure()
                        else -> InstallError.NotAnApk.asFailure()
                    }
                }

                val manifest = AxmlManifestParser.parse(manifestBytes)
                    ?: return InstallError.CorruptArchive.asFailure()
                val apkSize = if (reportedSize != null) {
                    reportedSize
                } else {
                    drainBounded(bounded)
                    bounded.bytesRead
                }
                val base = ApkEntryMeta(zipEntryName = null, sizeBytes = apkSize, manifest = manifest)
                Result.success(
                    InspectedPackage(
                        packageName = manifest.packageName,
                        displayLabel = pickDisplayLabel(manifest, null, source.displayName),
                        versionCode = manifest.versionCode,
                        versionName = manifest.versionName,
                        isBundle = false,
                        base = base,
                        splits = emptyList(),
                    ),
                )
            }
        } catch (_: SizeLimitExceededException) {
            InstallError.TooLarge.asFailure()
        } catch (e: ZipException) {
            Result.failure(InstallException(InstallError.CorruptArchive, e))
        } catch (e: IOException) {
            Result.failure(InstallException(InstallError.CorruptArchive, e))
        }
    }

    /** Returns null when [MANIFEST_ENTRY] is missing; throws [SizeLimitExceededException] if over cap. */
    private fun readManifestFromZipStream(stream: InputStream): ByteArray? {
        val zis = ZipInputStream(stream)
        var scannedUncompressed = 0L
        while (true) {
            val entry = zis.nextEntry ?: return null
            if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                if (entry.size > MAX_MANIFEST_BYTES) throw SizeLimitExceededException()
                return readBoundedBytes(zis, MAX_MANIFEST_BYTES) ?: throw SizeLimitExceededException()
            }
            if (!entry.isDirectory) {
                if (entry.size > MAX_INSTALL_BYTES) throw SizeLimitExceededException()
                scannedUncompressed += drainEntryWithinBudget(
                    zis,
                    MAX_INSTALL_BYTES - scannedUncompressed,
                )
            }
        }
    }

    private fun drainEntryWithinBudget(stream: InputStream, remainingBudget: Long): Long {
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        var readForEntry = 0L
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) return readForEntry
            readForEntry += n.toLong()
            if (readForEntry > remainingBudget) throw SizeLimitExceededException()
        }
    }

    private fun readJsonLabelHint(zipFile: ZipFile, entry: ZipEntry?, key: String): String? {
        if (entry == null || entry.size > MAX_METADATA_BYTES) return null
        val bytes = try {
            zipFile.getInputStream(entry).use { readBoundedBytes(it, MAX_METADATA_BYTES) }
        } catch (_: IOException) {
            null
        } ?: return null
        val parsed = try {
            MiniJson.parse(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        val raw = (parsed as? Map<*, *>)?.get(key) as? String ?: return null
        return sanitizeLabel(raw)
    }

    private fun pickDisplayLabel(
        manifest: ApkManifestInfo,
        jsonHint: String?,
        displayName: String,
    ): String {
        manifest.literalLabel?.let(::sanitizeLabel)?.let { return it }
        jsonHint?.let(::sanitizeLabel)?.let { return it }
        val fileName = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
        val stem = if ('.' in fileName) fileName.substringBeforeLast('.').trim() else fileName
        return sanitizeLabel(stem) ?: manifest.packageName
    }

    private fun sanitizeLabel(raw: String): String? =
        raw.replace(Regex("[\\r\\n\\t]+"), " ").trim().takeIf { it.isNotEmpty() }

    private fun measureStreamSize(source: InstallSource): Long {
        return source.openStream().use { stream ->
            val bounded = CountingBoundedInputStream(stream, MAX_INSTALL_BYTES)
            drainBounded(bounded)
            bounded.bytesRead
        }
    }

    private fun drainBounded(stream: InputStream) {
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        while (stream.read(buf) != -1) {
            // Bounded stream enforces MAX_INSTALL_BYTES.
        }
    }

    private fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(minOf(STREAM_BUFFER_BYTES, maxBytes + 1))
        var total = 0
        while (true) {
            val toRead = minOf(buf.size, maxBytes - total + 1)
            val n = stream.read(buf, 0, toRead)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun hasZipLocalHeaderMagic(stream: InputStream): Boolean {
        val magic = ByteArray(4)
        return readFullyUpTo(stream, magic, 4) == 4 && isZipMagic(magic)
    }

    private fun readFullyUpTo(stream: InputStream, dst: ByteArray, count: Int): Int {
        var read = 0
        while (read < count) {
            val n = stream.read(dst, read, count - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    private fun isZipMagic(magic: ByteArray): Boolean =
        magic[0] == 0x50.toByte() &&
            magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() &&
            magic[3] == 0x04.toByte()

    private fun normalizeZipPath(raw: String): String =
        raw.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")

    private fun isObbPath(normalized: String): Boolean =
        normalized.startsWith("android/obb/", ignoreCase = true) ||
            normalized.contains("/android/obb/", ignoreCase = true)

    private class CountingBoundedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b != -1) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        private fun advance(delta: Long) {
            bytesRead += delta
            if (bytesRead > maxBytes) throw SizeLimitExceededException()
        }
    }
}
