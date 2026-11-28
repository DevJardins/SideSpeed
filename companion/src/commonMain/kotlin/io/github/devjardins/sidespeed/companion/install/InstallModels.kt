package io.github.devjardins.sidespeed.companion.install

import java.io.InputStream
import java.util.zip.ZipFile

/** Abstraction over a user-picked APK or split bundle across desktop files and Android SAF URIs. */
interface InstallSource : AutoCloseable {
    val displayName: String
    val sizeBytes: Long?
    fun openStream(): InputStream

    /** Seekable [ZipFile] for local/staged split bundles, or null for streamed single APKs. */
    fun asZipFile(): ZipFile?
}

enum class InstallFailureCode {
    UPDATE_INCOMPATIBLE,
    VERSION_DOWNGRADE,
    INSUFFICIENT_STORAGE,
    NO_MATCHING_ABIS,
    OLDER_SDK,
    INVALID_APK,
    UNKNOWN,
}

sealed interface InstallError {
    data object NotAnApk : InstallError
    data object CorruptArchive : InstallError
    data object ObbNotSupported : InstallError
    data object TooLarge : InstallError
    data object TooManyEntries : InstallError
    data object MixedBundle : InstallError
    data object MissingBaseApk : InstallError
    data object IncompatibleAbi : InstallError
    data object OlderSdk : InstallError
    data class DowngradeNotAllowed(val installedVersion: String?) : InstallError
    data object UnsupportedStreamTransport : InstallError
    data object UntrustedNetwork : InstallError
    data object TvUnavailable : InstallError
    data class InstallFailed(val code: InstallFailureCode, val rawCode: String?) : InstallError
    data object Cancelled : InstallError
}

class InstallException(
    val error: InstallError,
    cause: Throwable? = null,
) : Exception(cause)

fun InstallError.asFailure(): Result<Nothing> = Result.failure(InstallException(this))

fun Throwable.installErrorOrNull(): InstallError? = (this as? InstallException)?.error

fun <T> Result<T>.installErrorOrNull(): InstallError? = exceptionOrNull()?.installErrorOrNull()

data class ApkManifestInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val splitName: String?,
    val configForSplit: String?,
    val isFeatureSplit: Boolean,
    val literalLabel: String?,
    val minSdkVersion: Int? = null,
    val labelResId: Int? = null,
)

data class ApkEntryMeta(
    val zipEntryName: String?,
    val sizeBytes: Long,
    val manifest: ApkManifestInfo,
    val resourceLabel: String? = null,
)

/** One SDK variant inside a multi-variant bundletool `.apks`: disjoint base plus splits. */
data class InspectedVariant(
    val minSdk: Int,
    val base: ApkEntryMeta,
    val splits: List<ApkEntryMeta>,
)

data class InspectedPackage(
    val packageName: String,
    val displayLabel: String,
    val versionCode: Long,
    val versionName: String?,
    val isBundle: Boolean,
    val base: ApkEntryMeta,
    val splits: List<ApkEntryMeta>,
    /**
     * Set when the archive holds several SDK variants. [base] and [splits] then describe only
     * the first variant for review display; callers must resolve one variant with
     * [ApkInspector.selectVariant] before selecting splits or installing.
     */
    val variants: List<InspectedVariant>? = null,
)

data class DeviceProfile(
    val abiList: List<String>,
    val densityDpi: Int,
    val localeTag: String,
)

data class SelectedSplit(
    val entry: ApkEntryMeta,
    val safeName: String,
    val summaryTag: String?,
)

data class SplitSelection(
    val splits: List<SelectedSplit>,
    val totalBytes: Long,
    val summaryTags: List<String>,
)
