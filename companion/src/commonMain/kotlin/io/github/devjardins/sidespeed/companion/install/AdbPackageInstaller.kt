package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.tv.ContentCallResult
import io.github.devjardins.sidespeed.companion.tv.HelperKeeper
import io.github.devjardins.sidespeed.companion.tv.InstallStream
import io.github.devjardins.sidespeed.companion.tv.InstalledPackageInfo
import io.github.devjardins.sidespeed.companion.tv.TlsNotNegotiated
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.UnsupportedInstallStreamException
import io.github.devjardins.sidespeed.companion.tv.VisitResult
import io.github.devjardins.sidespeed.helper.PackageNames
import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipException

/** Typed TV pre-flight facts used to render the review step before transferring APK bytes. */
data class PreflightResult(
    val device: DeviceProfile,
    val selection: SplitSelection,
    val installed: InstalledPackageInfo?,
    /** The inspected file with exactly one SDK variant selected; [InspectedPackage.variants] is null. */
    val pkg: InspectedPackage,
) {
    val isInstalled: Boolean get() = installed != null
    val installedVersionName: String? get() = installed?.versionName
    val installedVersionCode: Long? get() = installed?.versionCode
    val installerPackage: String? get() = installed?.installerPackage
    val isStoreInstalled: Boolean get() = installed?.isStoreInstalled == true
    val isSystemApp: Boolean get() = installed?.isSystem == true
}

/** Progress and phase events emitted during [AdbPackageInstaller.install]. */
sealed interface InstallTransferPhase {
    data class Transferring(
        val bytesSent: Long,
        val totalBytes: Long,
        val bytesPerSec: Long,
        val splitIndex: Int,
        val splitCount: Int,
    ) : InstallTransferPhase

    data object Committing : InstallTransferPhase

    data object StartingCompile : InstallTransferPhase
}

/** Result of a completed remote APK/bundle install and post-install TV handoff. */
data class InstallOutcome(
    val packageName: String,
    val displayLabel: String,
    val compileRequested: Boolean,
    val reqSeq: Long? = null,
    val tvNeedsUpdate: Boolean = false,
    val helperVisit: VisitResult? = null,
)

/**
 * Inspects TV properties/installed state and streams selected APK splits directly into Android's
 * `package install-create` / `install-write` / `install-commit` session over a verified [TvSession].
 */
class AdbPackageInstaller(
    private val helperKeeper: HelperKeeper = HelperKeeper(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val abortScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private class LocalSourceReadException(cause: Throwable? = null) : Exception(cause)

    suspend fun preflight(session: TvSession, pkg: InspectedPackage): Result<PreflightResult> {
        if (!PackageNames.isValid(pkg.packageName)) return InstallError.NotAnApk.asFailure()
        return try {
            val abiRaw = session.run(TvCommands.PROP_ABILIST).ifBlank {
                session.run(TvCommands.PROP_ABI)
            }
            val abiList = abiRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val densityDpi = session.run(TvCommands.PROP_DENSITY).trim().toIntOrNull()?.takeIf { it > 0 } ?: 0
            val localeTag = session.run(TvCommands.PROP_LOCALE).ifBlank {
                session.run(TvCommands.PROP_LOCALE_FALLBACK)
            }.trim()

            val device = DeviceProfile(
                abiList = abiList,
                densityDpi = densityDpi,
                localeTag = localeTag,
            )
            val effectivePkg = if (pkg.variants != null) {
                val tvSdk = TvCommands.parseSdkLevel(session.run(TvCommands.PROP_SDK))
                    ?: return InstallError.MixedBundle.asFailure()
                ApkInspector.selectVariant(pkg, tvSdk).getOrElse { return Result.failure(it) }
            } else {
                pkg
            }
            val selection = SplitSelector.select(effectivePkg, device).getOrElse { return Result.failure(it) }

            val pathOut = session.run(TvCommands.packagePath(effectivePkg.packageName))
            val pathPresent = pathOut.lineSequence().any { it.trim().startsWith(Tokens.PM_PATH_PREFIX) }
            val installed = if (pathPresent) {
                TvCommands.parsePackageDump(session.run(TvCommands.packageDump(effectivePkg.packageName)))
            } else {
                null
            }
            if (installed != null && effectivePkg.versionCode < installed.versionCode) {
                return InstallError.DowngradeNotAllowed(installed.versionName).asFailure()
            }

            Result.success(
                PreflightResult(
                    device = device,
                    selection = selection,
                    installed = installed,
                    pkg = effectivePkg,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbConnectionLost) {
            Result.failure(InstallException(InstallError.TvUnavailable, e))
        } catch (e: TlsNotNegotiated) {
            Result.failure(InstallException(InstallError.TvUnavailable, e))
        } catch (e: IOException) {
            Result.failure(InstallException(InstallError.TvUnavailable, e))
        }
    }

    suspend fun install(
        session: TvSession,
        tvSerial: String,
        pkg: InspectedPackage,
        selection: SplitSelection,
        source: InstallSource,
        keepOptimized: Boolean,
        onPhase: (InstallTransferPhase) -> Unit,
        abandonOnFreshSession: suspend (String) -> Unit,
    ): Result<InstallOutcome> {
        if (!PackageNames.isValid(pkg.packageName)) return InstallError.NotAnApk.asFailure()
        // Never install from an unresolved multi-variant file: the caller must pick one
        // variant with ApkInspector.selectVariant (preflight does) and hand that down.
        if (pkg.variants != null) return InstallError.CorruptArchive.asFailure()
        if (selection.splits.isEmpty() ||
            selection.totalBytes <= 0L ||
            selection.totalBytes > ApkInspector.MAX_INSTALL_BYTES
        ) {
            return InstallError.CorruptArchive.asFailure()
        }
        for (split in selection.splits) {
            if (!SAFE_SPLIT_PATTERN.matches(split.safeName) || split.entry.sizeBytes <= 0L) {
                return InstallError.CorruptArchive.asFailure()
            }
        }

        var sessionId: String? = null
        var committed = false

        suspend fun bestEffortAbandon() {
            val id = sessionId ?: return
            if (committed) return
            withContext(NonCancellable) {
                runCatching { abandonOnFreshSession(id) }
            }
        }

        val activeStream = AtomicReference<InstallStream?>(null)

        fun <T> withTrackedStream(ctx: CoroutineContext, stream: InstallStream, block: (InstallStream) -> T): T {
            activeStream.set(stream)
            if (!ctx.isActive) {
                activeStream.compareAndSet(stream, null)
                runCatching { stream.close() }
                ctx.ensureActive()
            }
            return try {
                stream.use(block)
            } finally {
                activeStream.compareAndSet(stream, null)
            }
        }

        try {
            coroutineScope {
                val cleanExit = AtomicBoolean(false)
                val cancelWatcher = launch(Dispatchers.Unconfined) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!cleanExit.get()) {
                            val stream = activeStream.getAndSet(null)
                            abortScope.launch {
                                runCatching { session.close() }
                                runCatching { stream?.close() }
                            }
                        }
                    }
                }
                try {
                    val createOut = withContext(ioDispatcher) {
                        val ctx = currentCoroutineContext()
                        val stream = session.openPackageCmd(
                            Tokens.CMD_INSTALL_CREATE,
                            Tokens.ARG_REPLACE,
                            Tokens.ARG_SIZE,
                            selection.totalBytes.toString(),
                        )
                        withTrackedStream(ctx, stream) { readNonBlankResponse(it) }
                    }

                    val createdId = parseCreatedSessionId(createOut)
                        ?: throw InstallException(mapInstallFailure(createOut))
                    sessionId = createdId

                    val zipFile = if (pkg.isBundle) {
                        source.asZipFile() ?: throw InstallException(InstallError.CorruptArchive)
                    } else {
                        null
                    }

                    val totalBytes = selection.totalBytes
                    val splitCount = selection.splits.size
                    val startedAtMs = clock()
                    var totalSent = 0L
                    var lastEmitAtMs = startedAtMs
                    var lastEmitBytes = 0L

                    withContext(ioDispatcher) {
                        val ctx = currentCoroutineContext()
                        val buffer = ByteArray(CHUNK_BYTES)
                        selection.splits.forEachIndexed { index, split ->
                            ctx.ensureActive()
                            val splitIndex = index + 1
                            onPhase(
                                InstallTransferPhase.Transferring(
                                    bytesSent = totalSent,
                                    totalBytes = totalBytes,
                                    bytesPerSec = transferRate(totalSent, startedAtMs, clock()),
                                    splitIndex = splitIndex,
                                    splitCount = splitCount,
                                ),
                            )

                            val splitSize = split.entry.sizeBytes
                            val input = openSplitStream(pkg.isBundle, source, zipFile, split)
                            val writeOut = input.use { src ->
                                val stream = session.openPackageCmd(
                                    Tokens.CMD_INSTALL_WRITE,
                                    Tokens.ARG_SIZE,
                                    splitSize.toString(),
                                    createdId,
                                    split.safeName,
                                    Tokens.STDIN_TOKEN,
                                )
                                withTrackedStream(ctx, stream) { dst ->
                                    var writtenForSplit = 0L
                                    while (writtenForSplit < splitSize) {
                                        ctx.ensureActive()
                                        val toRead = minOf(buffer.size.toLong(), splitSize - writtenForSplit).toInt()
                                        val n = try {
                                            src.read(buffer, 0, toRead)
                                        } catch (e: IOException) {
                                            throw LocalSourceReadException(e)
                                        }
                                        if (n < 0) {
                                            throw LocalSourceReadException()
                                        }
                                        if (n == 0) continue
                                        ctx.ensureActive()
                                        dst.write(buffer, 0, n)
                                        writtenForSplit += n.toLong()
                                        totalSent += n.toLong()
                                        val now = clock()
                                        val shouldEmit = totalSent == totalBytes ||
                                            (now - lastEmitAtMs) >= PROGRESS_INTERVAL_MS ||
                                            (totalSent - lastEmitBytes) >= PROGRESS_BYTES_STEP
                                        if (shouldEmit) {
                                            lastEmitAtMs = now
                                            lastEmitBytes = totalSent
                                            onPhase(
                                                InstallTransferPhase.Transferring(
                                                    bytesSent = totalSent,
                                                    totalBytes = totalBytes,
                                                    bytesPerSec = transferRate(totalSent, startedAtMs, now),
                                                    splitIndex = splitIndex,
                                                    splitCount = splitCount,
                                                ),
                                            )
                                        }
                                    }
                                    val trailing = try {
                                        src.read()
                                    } catch (e: IOException) {
                                        throw LocalSourceReadException(e)
                                    }
                                    if (trailing != -1) {
                                        throw LocalSourceReadException()
                                    }
                                    ctx.ensureActive()
                                    dst.flush()
                                    readNonBlankResponse(dst)
                                }
                            }

                            if (!isInstallSuccess(writeOut)) {
                                throw InstallException(mapInstallFailure(writeOut))
                            }
                        }
                    }

                    currentCoroutineContext().ensureActive()
                    onPhase(InstallTransferPhase.Committing)

                    val commitOut = withContext(ioDispatcher) {
                        val ctx = currentCoroutineContext()
                        val stream = session.openPackageCmd(Tokens.CMD_INSTALL_COMMIT, createdId)
                        withTrackedStream(ctx, stream) { readNonBlankResponse(it) }
                    }

                    if (!isInstallSuccess(commitOut)) {
                        throw InstallException(mapInstallFailure(commitOut))
                    }
                    committed = true
                } finally {
                    cleanExit.set(true)
                    cancelWatcher.cancel()
                }
            }
        } catch (e: InstallException) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw CancellationException(null, e)
            return Result.failure(e)
        } catch (e: UnsupportedInstallStreamException) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw CancellationException(null, e)
            return Result.failure(InstallException(InstallError.UnsupportedStreamTransport, e))
        } catch (e: LocalSourceReadException) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw CancellationException(null, e)
            return Result.failure(InstallException(InstallError.CorruptArchive, e))
        } catch (e: CancellationException) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw e
            return Result.failure(InstallException(InstallError.TvUnavailable, e))
        } catch (e: AdbConnectionLost) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw CancellationException(null, e)
            return Result.failure(InstallException(InstallError.TvUnavailable, e))
        } catch (e: TlsNotNegotiated) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw CancellationException(null, e)
            return Result.failure(InstallException(InstallError.TvUnavailable, e))
        } catch (e: IOException) {
            bestEffortAbandon()
            if (!currentCoroutineContext().isActive) throw CancellationException(null, e)
            return Result.failure(InstallException(InstallError.TvUnavailable, e))
        }

        // Install committed cleanly on TV: start/restart helper and request compile (best effort).
        onPhase(InstallTransferPhase.StartingCompile)
        val visit = try {
            helperKeeper.visit(session, tvSerial)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (pkg.packageName in SideSpeedPackages.ALL) {
            return Result.success(
                InstallOutcome(
                    packageName = pkg.packageName,
                    displayLabel = pkg.displayLabel,
                    compileRequested = false,
                    reqSeq = null,
                    tvNeedsUpdate = false,
                    helperVisit = visit,
                ),
            )
        }

        if (visit == VisitResult.NotInstalled) {
            return Result.success(
                InstallOutcome(
                    packageName = pkg.packageName,
                    displayLabel = pkg.displayLabel,
                    compileRequested = false,
                    reqSeq = null,
                    tvNeedsUpdate = false,
                    helperVisit = visit,
                ),
            )
        }

        val callOutput = try {
            session.run(TvCommands.callCompilePackage(pkg.packageName, keepOptimized))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val callResult = callOutput?.let { TvCommands.parseContentCallResult(it) }
        return when (callResult) {
            is ContentCallResult.Ok -> Result.success(
                InstallOutcome(
                    packageName = pkg.packageName,
                    displayLabel = pkg.displayLabel,
                    compileRequested = true,
                    reqSeq = callResult.reqSeq,
                    tvNeedsUpdate = false,
                    helperVisit = visit,
                ),
            )
            ContentCallResult.Unsupported -> Result.success(
                InstallOutcome(
                    packageName = pkg.packageName,
                    displayLabel = pkg.displayLabel,
                    compileRequested = false,
                    reqSeq = null,
                    tvNeedsUpdate = true,
                    helperVisit = visit,
                ),
            )
            is ContentCallResult.Error, null -> Result.success(
                InstallOutcome(
                    packageName = pkg.packageName,
                    displayLabel = pkg.displayLabel,
                    compileRequested = false,
                    reqSeq = null,
                    tvNeedsUpdate = false,
                    helperVisit = visit,
                ),
            )
        }
    }

    private fun openSplitStream(
        isBundle: Boolean,
        source: InstallSource,
        zipFile: java.util.zip.ZipFile?,
        split: SelectedSplit,
    ): InputStream {
        if (!isBundle) {
            return try {
                source.openStream()
            } catch (e: IOException) {
                throw LocalSourceReadException(e)
            }
        }
        val entryName = split.entry.zipEntryName ?: throw LocalSourceReadException()
        val entry = zipFile?.getEntry(entryName) ?: throw LocalSourceReadException()
        return try {
            zipFile.getInputStream(entry)
        } catch (e: ZipException) {
            throw LocalSourceReadException(e)
        } catch (e: IOException) {
            throw LocalSourceReadException(e)
        }
    }

    private fun transferRate(bytesSent: Long, startedAtMs: Long, nowMs: Long): Long {
        val elapsedMs = nowMs - startedAtMs
        if (bytesSent <= 0L || elapsedMs <= 0L) return 0L
        return (bytesSent * 1000L) / elapsedMs
    }

    private fun readNonBlankResponse(stream: InstallStream): String {
        val text = stream.readAllText()
        if (text.isBlank()) throw AdbConnectionLost()
        return text
    }

    private fun isInstallSuccess(output: String): Boolean =
        output.trimStart().startsWith(Tokens.INSTALL_SUCCESS_PREFIX)

    private fun parseCreatedSessionId(output: String): String? {
        if (!isInstallSuccess(output)) return null
        val match = CREATED_SESSION_PATTERN.find(output) ?: return null
        val id = match.groupValues[1]
        return id.takeIf { SESSION_ID_PATTERN.matches(it) }
    }

    private fun mapInstallFailure(output: String): InstallError.InstallFailed {
        val rawCode = INSTALL_TOKEN_PATTERN.find(output)?.groupValues?.get(1)
            ?: NUMERIC_FAILURE_PATTERN.find(output)?.groupValues?.get(1)
        val code = when (rawCode) {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> InstallFailureCode.UPDATE_INCOMPATIBLE
            "INSTALL_FAILED_VERSION_DOWNGRADE" -> InstallFailureCode.VERSION_DOWNGRADE
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> InstallFailureCode.INSUFFICIENT_STORAGE
            "INSTALL_FAILED_NO_MATCHING_ABIS",
            "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> InstallFailureCode.NO_MATCHING_ABIS
            "INSTALL_FAILED_OLDER_SDK" -> InstallFailureCode.OLDER_SDK
            "INSTALL_FAILED_INVALID_APK",
            "INSTALL_PARSE_FAILED_NOT_APK",
            "INSTALL_PARSE_FAILED_BAD_MANIFEST",
            "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION",
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
            "INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME",
            "INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID",
            "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED",
            "INSTALL_PARSE_FAILED_MANIFEST_EMPTY" -> InstallFailureCode.INVALID_APK
            else -> InstallFailureCode.UNKNOWN
        }
        return InstallError.InstallFailed(code = code, rawCode = rawCode)
    }

    private object Tokens {
        const val PM_PATH_PREFIX = "package:"
        const val CMD_INSTALL_CREATE = "install-create"
        const val CMD_INSTALL_WRITE = "install-write"
        const val CMD_INSTALL_COMMIT = "install-commit"
        const val ARG_REPLACE = "-r"
        const val ARG_SIZE = "-S"
        const val STDIN_TOKEN = "-"
        const val INSTALL_SUCCESS_PREFIX = "Success"
    }

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val PROGRESS_BYTES_STEP = 256L * 1024L
        private val SESSION_ID_PATTERN = Regex("^[0-9]{1,10}$")
        private val SAFE_SPLIT_PATTERN = Regex("^(?:base|split_[1-9][0-9]*)\\.apk$")
        private val CREATED_SESSION_PATTERN = Regex("""\[([0-9]{1,10})\]""")
        private val INSTALL_TOKEN_PATTERN = Regex("""\[(INSTALL_[A-Z0-9_]+)\b""")
        private val NUMERIC_FAILURE_PATTERN = Regex("""Failure\s+\[(-?\d+)\b""")
    }
}
