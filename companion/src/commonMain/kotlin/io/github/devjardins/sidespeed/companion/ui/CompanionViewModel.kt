package io.github.devjardins.sidespeed.companion.ui

import io.github.devjardins.sidespeed.adb.AdbConnectionLost
import io.github.devjardins.sidespeed.companion.discovery.Diagnosis
import io.github.devjardins.sidespeed.companion.discovery.Discovery
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryEvent
import io.github.devjardins.sidespeed.companion.discovery.DiscoveryPlan
import io.github.devjardins.sidespeed.companion.discovery.DiscoverySummary
import io.github.devjardins.sidespeed.companion.discovery.ConnectPortFinder
import io.github.devjardins.sidespeed.companion.discovery.ProbeResult
import io.github.devjardins.sidespeed.companion.discovery.RangeChoice
import io.github.devjardins.sidespeed.companion.discovery.diagnose
import io.github.devjardins.sidespeed.companion.discovery.scanRangeFor
import io.github.devjardins.sidespeed.companion.discovery.sweepScope
import io.github.devjardins.sidespeed.companion.install.AdbPackageInstaller
import io.github.devjardins.sidespeed.companion.install.ApkInspector
import io.github.devjardins.sidespeed.companion.install.InspectedPackage
import io.github.devjardins.sidespeed.companion.install.InstallError
import io.github.devjardins.sidespeed.companion.install.InstallFailureCode
import io.github.devjardins.sidespeed.companion.install.InstallOutcome
import io.github.devjardins.sidespeed.companion.install.InstallSource
import io.github.devjardins.sidespeed.companion.install.InstallTransferPhase
import io.github.devjardins.sidespeed.companion.install.PreflightResult
import io.github.devjardins.sidespeed.companion.install.installErrorOrNull
import io.github.devjardins.sidespeed.companion.net.AddressPolicy
import io.github.devjardins.sidespeed.companion.net.Cidr
import io.github.devjardins.sidespeed.companion.net.FingerprintSource
import io.github.devjardins.sidespeed.companion.net.Ipv4
import io.github.devjardins.sidespeed.companion.net.NetworkFingerprint
import io.github.devjardins.sidespeed.companion.net.OverlaySnapshot
import io.github.devjardins.sidespeed.companion.net.OverlayTrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustMatcher
import io.github.devjardins.sidespeed.companion.net.TrustedNetwork
import io.github.devjardins.sidespeed.companion.net.TrustedOverlay
import io.github.devjardins.sidespeed.companion.resources.Res
import io.github.devjardins.sidespeed.companion.resources.add_allow_step
import io.github.devjardins.sidespeed.companion.resources.add_bad_address
import io.github.devjardins.sidespeed.companion.resources.add_bad_range
import io.github.devjardins.sidespeed.companion.resources.add_cant_scan
import io.github.devjardins.sidespeed.companion.resources.add_default_tv_name
import io.github.devjardins.sidespeed.companion.resources.add_no_serial
import io.github.devjardins.sidespeed.companion.resources.add_no_tvs_found
import io.github.devjardins.sidespeed.companion.resources.add_not_trusted_add
import io.github.devjardins.sidespeed.companion.resources.add_not_trusted_error
import io.github.devjardins.sidespeed.companion.resources.add_outside_network
import io.github.devjardins.sidespeed.companion.resources.add_scan_progress
import io.github.devjardins.sidespeed.companion.resources.add_shared_peer
import io.github.devjardins.sidespeed.companion.resources.add_tv_check_not_saved
import io.github.devjardins.sidespeed.companion.resources.add_unreachable
import io.github.devjardins.sidespeed.companion.resources.common_try_again
import io.github.devjardins.sidespeed.companion.resources.home_checked_min_ago
import io.github.devjardins.sidespeed.companion.resources.home_compile_waiting_now
import io.github.devjardins.sidespeed.companion.resources.home_installed_app
import io.github.devjardins.sidespeed.companion.resources.home_installed_update_tv
import io.github.devjardins.sidespeed.companion.resources.home_optimize_caught_up
import io.github.devjardins.sidespeed.companion.resources.home_optimize_failed
import io.github.devjardins.sidespeed.companion.resources.home_optimize_started
import io.github.devjardins.sidespeed.companion.resources.install_err_downgrade
import io.github.devjardins.sidespeed.companion.resources.install_err_failed
import io.github.devjardins.sidespeed.companion.resources.install_err_failed_code
import io.github.devjardins.sidespeed.companion.resources.install_err_incompatible_abi
import io.github.devjardins.sidespeed.companion.resources.install_err_no_space
import io.github.devjardins.sidespeed.companion.resources.install_err_not_apk
import io.github.devjardins.sidespeed.companion.resources.install_err_obb
import io.github.devjardins.sidespeed.companion.resources.install_err_older_android
import io.github.devjardins.sidespeed.companion.resources.install_err_too_large
import io.github.devjardins.sidespeed.companion.resources.install_err_wrong_key
import io.github.devjardins.sidespeed.companion.resources.home_confirm_button
import io.github.devjardins.sidespeed.companion.resources.home_confirm_tv
import io.github.devjardins.sidespeed.companion.resources.home_different_device
import io.github.devjardins.sidespeed.companion.resources.home_kept_alive_while_on
import io.github.devjardins.sidespeed.companion.resources.home_last_seen
import io.github.devjardins.sidespeed.companion.resources.home_not_checked_here
import io.github.devjardins.sidespeed.companion.resources.home_not_contacted
import io.github.devjardins.sidespeed.companion.resources.home_not_fire_tv_note
import io.github.devjardins.sidespeed.companion.resources.home_older_copy
import io.github.devjardins.sidespeed.companion.resources.home_pair_offer
import io.github.devjardins.sidespeed.companion.resources.home_status_check_failed
import io.github.devjardins.sidespeed.companion.resources.home_status_needs_attention
import io.github.devjardins.sidespeed.companion.resources.home_status_not_installed
import io.github.devjardins.sidespeed.companion.resources.home_status_not_running
import io.github.devjardins.sidespeed.companion.resources.home_status_offline
import io.github.devjardins.sidespeed.companion.resources.home_status_running
import io.github.devjardins.sidespeed.companion.resources.home_status_start_failed
import io.github.devjardins.sidespeed.companion.resources.home_status_unknown
import io.github.devjardins.sidespeed.companion.resources.home_status_waiting_for_allow
import io.github.devjardins.sidespeed.companion.resources.home_status_wrong_device
import io.github.devjardins.sidespeed.companion.resources.home_tv_unproven
import io.github.devjardins.sidespeed.companion.resources.home_type_the_port
import io.github.devjardins.sidespeed.companion.resources.home_waiting_for_you
import io.github.devjardins.sidespeed.companion.resources.home_wireless_off
import io.github.devjardins.sidespeed.companion.resources.network_default_name
import io.github.devjardins.sidespeed.companion.resources.pair_bad_connect_port
import io.github.devjardins.sidespeed.companion.resources.pair_button
import io.github.devjardins.sidespeed.companion.resources.pair_classic_ack
import io.github.devjardins.sidespeed.companion.resources.pair_classic_ack_check
import io.github.devjardins.sidespeed.companion.resources.pair_code_label
import io.github.devjardins.sidespeed.companion.resources.pair_connect_port_hint
import io.github.devjardins.sidespeed.companion.resources.pair_port_label
import io.github.devjardins.sidespeed.companion.resources.pair_steps
import io.github.devjardins.sidespeed.companion.resources.settings_no_network_to_trust
import io.github.devjardins.sidespeed.companion.resources.settings_no_overlay
import io.github.devjardins.sidespeed.companion.resources.tailnet_default_name
import io.github.devjardins.sidespeed.companion.store.CompanionSettings
import io.github.devjardins.sidespeed.companion.store.CompanionStore
import io.github.devjardins.sidespeed.companion.store.Reach
import io.github.devjardins.sidespeed.companion.store.SavedTv
import io.github.devjardins.sidespeed.companion.store.TvLink
import io.github.devjardins.sidespeed.companion.store.TvStatus
import io.github.devjardins.sidespeed.companion.tv.ClassicNotAcknowledged
import io.github.devjardins.sidespeed.companion.tv.ContentCallResult
import io.github.devjardins.sidespeed.companion.tv.ActivityPhase
import io.github.devjardins.sidespeed.companion.tv.NeedsConfirmation
import io.github.devjardins.sidespeed.companion.tv.EncryptedAvailable
import io.github.devjardins.sidespeed.companion.tv.EncryptedPortUnknown
import io.github.devjardins.sidespeed.companion.tv.TlsNotNegotiated
import io.github.devjardins.sidespeed.companion.tv.LinkOffer
import io.github.devjardins.sidespeed.companion.tv.PairCopy
import io.github.devjardins.sidespeed.companion.tv.PairResult
import io.github.devjardins.sidespeed.companion.tv.Pairer
import io.github.devjardins.sidespeed.companion.tv.TvActivity
import io.github.devjardins.sidespeed.companion.tv.TvAuthPending
import io.github.devjardins.sidespeed.companion.tv.TvCheckNotSaved
import io.github.devjardins.sidespeed.companion.tv.TvCommands
import io.github.devjardins.sidespeed.companion.tv.TvConnector
import io.github.devjardins.sidespeed.companion.tv.TvIdentity
import io.github.devjardins.sidespeed.companion.tv.TvSession
import io.github.devjardins.sidespeed.companion.tv.TvSessions
import io.github.devjardins.sidespeed.companion.tv.TvTarget
import io.github.devjardins.sidespeed.companion.tv.TvUnproven
import io.github.devjardins.sidespeed.companion.tv.TvUnreachable
import io.github.devjardins.sidespeed.companion.tv.TvWrongDevice
import io.github.devjardins.sidespeed.companion.tv.parseActivityRow
import io.github.devjardins.sidespeed.companion.ui.text.UiText
import io.github.devjardins.sidespeed.companion.ui.text.resolveSuspend
import io.github.devjardins.sidespeed.companion.upkeep.OverlayAccess
import io.github.devjardins.sidespeed.helper.SideSpeedPackages
import io.github.devjardins.sidespeed.companion.upkeep.encryptedMoveAllowed
import io.github.devjardins.sidespeed.companion.upkeep.OverlayGate
import io.github.devjardins.sidespeed.companion.upkeep.OverlayVerdict
import io.github.devjardins.sidespeed.companion.upkeep.Trigger
import io.github.devjardins.sidespeed.companion.upkeep.Upkeep
import io.github.devjardins.sidespeed.companion.upkeep.hardened
import io.github.devjardins.sidespeed.companion.upkeep.UpkeepReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

object Copy {
    /** Shown under a TV; the screen re-evaluates it on a timer so it never freezes. */
    fun checkedAgo(diffMs: Long): UiText {
        val mins = (diffMs / 60_000).coerceAtLeast(0).toInt()
        return UiText.Plural(Res.plurals.home_checked_min_ago, mins, listOf(mins))
    }

    val NO_TVS_FOUND: UiText = UiText.Res(Res.string.add_no_tvs_found)
    val NOT_TRUSTED_ERROR: UiText = UiText.Res(Res.string.add_not_trusted_error)
    val NOT_TRUSTED_ADD: UiText = UiText.Res(Res.string.add_not_trusted_add)
    val NOT_FIRE_TV_NOTE: UiText = UiText.Res(Res.string.home_not_fire_tv_note)
    val ALLOW_STEP: UiText = UiText.Res(Res.string.add_allow_step)
    val BAD_ADDRESS: UiText = UiText.Res(Res.string.add_bad_address, listOf(UiText.Ltr("192.168.1.50")))
    val NO_SERIAL: UiText = UiText.Res(Res.string.add_no_serial)
    val DIFFERENT_DEVICE: UiText = UiText.Res(Res.string.home_different_device)
    val NO_OVERLAY: UiText = UiText.Res(Res.string.settings_no_overlay)
    val SHARED_PEER: UiText = UiText.Res(Res.string.add_shared_peer)
    val CANT_SCAN: UiText = UiText.Res(Res.string.add_cant_scan)
    val OUTSIDE_NETWORK: UiText = UiText.Res(Res.string.add_outside_network)
    val UNREACHABLE: UiText = UiText.Res(Res.string.add_unreachable)
    val TV_CHECK_NOT_SAVED: UiText = UiText.Res(Res.string.add_tv_check_not_saved)
    val TV_UNPROVEN: UiText = UiText.Res(Res.string.home_tv_unproven)
    val BAD_RANGE: UiText = UiText.Res(Res.string.add_bad_range, listOf(UiText.Ltr("192.168.1.0/24")))
    val NOT_CHECKED_HERE: UiText = UiText.Res(Res.string.home_not_checked_here)
    val OLDER_COPY: UiText = UiText.Res(Res.string.home_older_copy)
    val PAIR_STEPS: UiText = UiText.Res(Res.string.pair_steps)
    val PAIR_CODE: UiText = UiText.Res(Res.string.pair_code_label)
    val PAIR_PORT: UiText = UiText.Res(Res.string.pair_port_label)
    val PAIR: UiText = UiText.Res(Res.string.pair_button)
    val CONNECT_PORT_HINT: UiText = UiText.Res(Res.string.pair_connect_port_hint)
    val CLASSIC_ACK: UiText = UiText.Res(Res.string.pair_classic_ack)
    val CLASSIC_ACK_CHECK: UiText = UiText.Res(Res.string.pair_classic_ack_check)
    val PAIR_OFFER: UiText = UiText.Res(Res.string.home_pair_offer)
    val NOT_CONTACTED: UiText = UiText.Res(Res.string.home_not_contacted)
    val CONFIRM_TV: UiText = UiText.Res(Res.string.home_confirm_tv)
    val CONFIRM: UiText = UiText.Res(Res.string.home_confirm_button)
    val WAITING_FOR_YOU: UiText = UiText.Res(Res.string.home_waiting_for_you)
    val WIRELESS_OFF: UiText = UiText.Res(Res.string.home_wireless_off)
    val TRY_AGAIN: UiText = UiText.Res(Res.string.common_try_again)
    val TYPE_THE_PORT: UiText = UiText.Res(Res.string.home_type_the_port)
    val BAD_CONNECT_PORT: UiText = UiText.Res(Res.string.pair_bad_connect_port)
    val NO_NETWORK_TO_TRUST: UiText = UiText.Res(Res.string.settings_no_network_to_trust)

    val OPTIMIZE_STARTED: UiText = UiText.Res(Res.string.home_optimize_started)
    val OPTIMIZE_CAUGHT_UP: UiText = UiText.Res(Res.string.home_optimize_caught_up)
    val OPTIMIZE_FAILED: UiText = UiText.Res(Res.string.home_optimize_failed)
    val INSTALL_FAILED: UiText = UiText.Res(Res.string.install_err_failed)

    /** What the card says after an install finished, from what the TV did next. [appName] is the app's label. */
    fun installed(outcome: InstallOutcome): UiText {
        val name = UiText.Fsi(outcome.displayLabel)
        return when {
            outcome.compileRequested -> UiText.Res(Res.string.home_installed_app, listOf(name))
            outcome.tvNeedsUpdate -> UiText.Res(Res.string.home_installed_update_tv, listOf(name))
            else -> UiText.Res(Res.string.home_compile_waiting_now, listOf(name))
        }
    }

    /** The plain-English reason an install stopped. Package-manager codes never reach the screen except the unknown one. */
    fun installError(error: InstallError): UiText = when (error) {
        InstallError.NotAnApk, InstallError.CorruptArchive, InstallError.TooManyEntries,
        InstallError.MixedBundle, InstallError.MissingBaseApk -> UiText.Res(Res.string.install_err_not_apk)
        InstallError.ObbNotSupported -> UiText.Res(Res.string.install_err_obb)
        InstallError.TooLarge -> UiText.Res(Res.string.install_err_too_large)
        InstallError.IncompatibleAbi -> UiText.Res(Res.string.install_err_incompatible_abi)
        is InstallError.DowngradeNotAllowed -> UiText.Res(Res.string.install_err_downgrade)
        InstallError.UntrustedNetwork -> NOT_TRUSTED_ERROR
        InstallError.UnsupportedStreamTransport, InstallError.TvUnavailable, InstallError.Cancelled -> INSTALL_FAILED
        is InstallError.InstallFailed -> when (error.code) {
            InstallFailureCode.UPDATE_INCOMPATIBLE -> UiText.Res(Res.string.install_err_wrong_key)
            InstallFailureCode.VERSION_DOWNGRADE -> UiText.Res(Res.string.install_err_downgrade)
            InstallFailureCode.INSUFFICIENT_STORAGE -> UiText.Res(Res.string.install_err_no_space)
            InstallFailureCode.NO_MATCHING_ABIS -> UiText.Res(Res.string.install_err_incompatible_abi)
            InstallFailureCode.OLDER_SDK -> UiText.Res(Res.string.install_err_older_android)
            InstallFailureCode.INVALID_APK -> UiText.Res(Res.string.install_err_not_apk)
            InstallFailureCode.UNKNOWN ->
                error.rawCode?.let { UiText.Res(Res.string.install_err_failed_code, listOf(UiText.Ltr(it))) } ?: INSTALL_FAILED
        }
    }

    fun scanProgress(scanned: Int, total: Int): UiText =
        UiText.Plural(Res.plurals.add_scan_progress, total, listOf(scanned, total))

    fun keptAliveWhileOn(network: String): UiText =
        UiText.Res(Res.string.home_kept_alive_while_on, listOf(UiText.Fsi(network)))

    /** A saved status shown where the TV can't be checked from here: it may be stale. */
    fun lastSeen(label: UiText): UiText = UiText.Res(Res.string.home_last_seen, listOf(label))

    fun defaultNetworkName(index: Int): UiText = UiText.Res(Res.string.network_default_name, listOf(index))

    fun defaultTailnetName(index: Int): UiText = UiText.Res(Res.string.tailnet_default_name, listOf(index))

    fun defaultTvName(address: String): UiText = UiText.Res(Res.string.add_default_tv_name, listOf(UiText.Ltr(address)))

    fun statusLabel(s: TvStatus): UiText = when (s) {
        TvStatus.RUNNING -> UiText.Res(Res.string.home_status_running)
        TvStatus.NOT_RUNNING -> UiText.Res(Res.string.home_status_not_running)
        TvStatus.OFFLINE -> UiText.Res(Res.string.home_status_offline)
        TvStatus.WAITING_FOR_ALLOW -> UiText.Res(Res.string.home_status_waiting_for_allow)
        TvStatus.NOT_INSTALLED -> UiText.Res(Res.string.home_status_not_installed)
        TvStatus.WRONG_DEVICE -> UiText.Res(Res.string.home_status_wrong_device)
        TvStatus.START_FAILED -> UiText.Res(Res.string.home_status_start_failed)
        TvStatus.NEEDS_ATTENTION -> UiText.Res(Res.string.home_status_needs_attention)
        TvStatus.CHECK_FAILED -> UiText.Res(Res.string.home_status_check_failed)
        TvStatus.UNKNOWN -> UiText.Res(Res.string.home_status_unknown)
    }
}

/** How a Restart-now press ended for one TV: reached while running, reached while not running, or never contacted. */
enum class RestartOutcome { RESTARTED, CHECKED, NOT_CHECKED }

/** The outcome of the latest Restart-now press for [tvId]; cleared when the screen has shown it. */
data class RestartResult(val tvId: String, val outcome: RestartOutcome)

/** Why a saved TV is waiting on the user instead of being checked. */
enum class TvAttention { NONE, LEGACY_ACK, LEGACY_PAIR, WIRELESS_OFF, CONFIRM }

/** What a Home card offers. A parked TV offers only [REMOVE]. */
enum class TvCardAction { RESTART, INSTALL, OPTIMIZE, REMOVE }

/** Statuses where the TV answered the last check, so an install has something to talk to. */
private val INSTALLABLE_STATUSES = setOf(TvStatus.RUNNING, TvStatus.NOT_RUNNING, TvStatus.NOT_INSTALLED, TvStatus.START_FAILED)

/**
 * A parked TV can only be removed. Every other card offers restart. Install needs a reachable TV on a Trusted
 * Network with nothing waiting on the user. Optimize now also needs a running helper that supports remote
 * requests and no compile in progress.
 */
fun cardActions(card: TvCardState): List<TvCardAction> {
    if (card.status == TvStatus.CHECK_FAILED) return listOf(TvCardAction.REMOVE)
    val ready = card.checkableHere && card.attention == TvAttention.NONE
    val actions = mutableListOf(TvCardAction.RESTART)
    if (ready && card.status in INSTALLABLE_STATUSES) actions += TvCardAction.INSTALL
    val activity = card.activity
    if (ready && card.status == TvStatus.RUNNING && activity != null &&
        activity.remoteSupported && activity.phase != ActivityPhase.COMPILING
    ) {
        actions += TvCardAction.OPTIMIZE
    }
    actions += TvCardAction.REMOVE
    return actions
}

/** Receives install progress so a platform can keep a notification or foreground service alive. All calls default to nothing. */
interface InstallTransferHost {
    fun onStart(tvName: String, appName: String) {}
    fun onProgress(bytesSent: Long, totalBytes: Long, committing: Boolean) {}
    fun onEnd() {}
}

object NoOpTransferHost : InstallTransferHost

/** The store an installed app came from, when that store can replace an update later. */
enum class InstallStoreSource { GOOGLE_PLAY, AMAZON_APPSTORE }

/** What the install review shows before anything is sent. */
data class InstallReview(
    val appName: String,
    val packageName: String,
    val newVersion: String,
    /** Null when the TV does not have the app yet. */
    val installedVersion: String?,
    val summaryTags: List<String>,
    val totalBytes: Long,
    val store: InstallStoreSource?,
    val isSystemApp: Boolean,
)

sealed interface InstallPhase {
    /** Cancel is refused once the TV is committing the install, because the install can no longer be rolled back. */
    val canCancel: Boolean

    data object Inspecting : InstallPhase { override val canCancel = true }
    data class Review(val review: InstallReview) : InstallPhase { override val canCancel = true }
    data class Transferring(val bytesSent: Long, val totalBytes: Long, val bytesPerSec: Long) : InstallPhase {
        override val canCancel = true
    }
    data object Committing : InstallPhase { override val canCancel = false }
    data object StartingCompile : InstallPhase { override val canCancel = false }
    data class Failed(val message: UiText, val canRetry: Boolean) : InstallPhase { override val canCancel = true }
}

/** The one install in progress. [isTailnet] is true when the TV is reached over Tailscale. */
data class InstallSessionUi(
    val tvId: String,
    val tvName: String,
    val isTailnet: Boolean,
    val phase: InstallPhase,
    val keepOptimized: Boolean,
    val showKeepToggle: Boolean,
)

/** Pairing a TV that speaks wireless debugging. [tvId] is set when this replaces a saved TV's link. */
data class PairingForm(
    val address: String,
    val tvId: String? = null,
    val error: UiText? = null,
    val askConnectPort: Boolean = false,
)

/** The cleartext acknowledgment, shown before a classic TV is added. */
data class ClassicAck(val address: String, val checked: Boolean = false)

data class TvCardState(
    val id: String,
    val name: String,
    val address: String,
    val status: TvStatus,
    val statusLabel: UiText,
    /** Whether upkeep could contact this TV right now; when false the card shows the saved status as last-seen. */
    val checkableHere: Boolean,
    val checkedAtEpochMs: Long?,
    val note: UiText?,
    val scopeNote: UiText? = null,
    /** How the last check reached this TV; null when never checked, or when it can't be checked from here. */
    val reach: Reach? = null,
    /** The latest live compile status, while the Home screen polls for it; null when unknown. */
    val activity: TvActivity? = null,
    /** What the Home card asks the user to do before this TV is checked again. */
    val attention: TvAttention = TvAttention.NONE,
)

/** What Tailscale reports on this computer: its tailnet, whether it is trusted, and whether it can reach peers. */
data class TailnetState(val name: String, val trusted: Boolean, val ready: Boolean)

data class CompanionUiState(
    val loaded: Boolean = false,
    /**
     * The store file was saved by a newer copy of the companion: the store is read-only and Home shows
     * a persistent banner ([Copy.OLDER_COPY]) instead of the edits it drops.
     */
    val savedByNewerVersion: Boolean = false,
    /** No Trusted Network chosen yet: the first-run screen shows and nothing touches the network. */
    val firstRun: Boolean = true,
    val currentNetwork: NetworkFingerprint? = null,
    val trustedNetworkName: String? = null,
    val trustedNetworks: List<TrustedNetwork> = emptyList(),
    val trustedOverlays: List<TrustedOverlay> = emptyList(),
    /** Null when there is no Tailscale to ask (Android, not installed, logged out). */
    val tailnet: TailnetState? = null,
    val tvs: List<TvCardState> = emptyList(),
    val settings: CompanionSettings = CompanionSettings(),
    val busy: Boolean = false,
    val lastRestart: RestartResult? = null,
    val error: UiText? = null,
    /** Addresses of TVs found by "Find TVs" that aren't saved yet. */
    val found: List<String> = emptyList(),
    /** Addresses that answered with wireless debugging. Picking one opens pairing. */
    val tlsHosts: List<String> = emptyList(),
    /** Pairing in progress, or null when the form is closed. */
    val pairing: PairingForm? = null,
    /** A classic add waiting for the acknowledgment checkbox. */
    val classicAck: ClassicAck? = null,
    /** Addresses whose add is in progress: their rows show "Adding…" and ignore presses. */
    val adding: Set<String> = emptyList<String>().toSet(),
    /** Per-address add failures, shown on the row that was pressed. */
    val addErrors: Map<String, UiText> = emptyMap(),
    /** The address of the TV just added; the Add screen returns Home on it (see [CompanionViewModel.acknowledgeAdded]). */
    val addedAddress: String? = null,
    /** A Find TVs scan is running: the button shows a spinner and ignores presses. */
    val scanning: Boolean = false,
    /** The last scan finished with no new TVs. */
    val noneFound: Boolean = false,
    /** "Scanned N of M" for the running or latest scan; null before the first one. */
    val scanProgress: UiText? = null,
    /** The latest scan finished but the probe cap cut it short: the Add screen offers "Keep scanning". */
    val scanTruncated: Boolean = false,
    /** The latest scan was stopped before it finished: the Add screen offers "Keep scanning". */
    val scanStopped: Boolean = false,
    /** What the latest finished or stopped scan concluded; null before the first one. */
    val diagnosis: Diagnosis? = null,
    /** The install in progress, or null. Only one runs at a time. */
    val install: InstallSessionUi? = null,
    /** The last install result per TV id, shown on its card until dismissed. */
    val installNotice: Map<String, UiText> = emptyMap(),
    /** TV ids with an Optimize now request in flight. */
    val optimizingTvIds: Set<String> = emptySet(),
    /** The last Optimize now result per TV id. */
    val optimizeNotice: Map<String, UiText> = emptyMap(),
)

class CompanionViewModel(
    private val store: CompanionStore,
    private val fingerprints: FingerprintSource,
    private val upkeep: Upkeep,
    private val connector: TvConnector,
    private val discovery: Discovery,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { "tv-" + clock() + "-" + (counter++) },
    private val overlay: OverlayAccess? = null,
    private val pairer: Pairer = Pairer { _, _, _ -> PairResult.Failed(PairCopy.FAILED) },
    private val ports: ConnectPortFinder = ConnectPortFinder { null },
    private val linkOffer: LinkOffer = LinkOffer { false },
    private val identity: TvIdentity? = null,
    private val formatText: suspend (UiText) -> String = { it.resolveSuspend(rtl = false) },
    private val installConnector: TvConnector = connector,
    private val transferHost: InstallTransferHost = NoOpTransferHost,
    /** Runs every blocking ADB call of an install or optimize request. Never Main: a cancelled call closes its link on the caller's thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val overlayGate = overlay?.let(::OverlayGate)
    private val sessions = TvSessions(
        connector,
        store,
        classicTls = { address -> linkOffer.encryptedAvailable(address) },
        ports = ports,
        clock = clock,
        relocateAllowed = { encryptedMoveAllowed(fingerprints, store, it.id) },
        identity = identity,
        installConnector = installConnector,
    )
    private val installer = AdbPackageInstaller(ioDispatcher = ioDispatcher)
    /** Whether a saved classic TV offered TLS the last time Home asked. Avoids asking on every refresh. */
    private val tlsOffer = mutableMapOf<String, Boolean>()
    /** Guards [tlsOffer] and [offersInFlight]. Lookups run off the refresh path, so they share this lock. */
    private val offerMutex = Mutex()
    /** Addresses whose encrypted-link lookup is already running. A refresh does not start a second one. */
    private val offersInFlight = mutableSetOf<String>()

    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()
    private var homeVisible = false
    private var foreground = true
    private var pollJob: Job? = null
    private val activities = mutableMapOf<String, TvActivity>()
    private val pollSessions = mutableMapOf<String, TvSession>()
    private val pollFailures = mutableMapOf<String, Int>()
    private val parkedTvIds = mutableSetOf<String>()
    private val work = Mutex()
    private var findJob: Job? = null
    private var lastSummary = DiscoverySummary()
    /** The range the current sweep is limited to; blank is the automatic one. Keep scanning reuses it. */
    private var findRange = ""
    /** Every host probed across the runs since the last fresh Find TVs, so Keep scanning resumes past them. */
    private val probedSinceFind = HashSet<Ipv4>()

    /** Hosts that answered without speaking ADB since the last fresh Find TVs, kept across a stop and resume. */
    private val liveNonAdbSinceFind = HashSet<Ipv4>()

    init {
        scope.launch { refresh() }
    }

    fun onOpen() {
        locked {
            val anyScope = currentTrusted() != null || (overlay != null && store.trustedOverlays().isNotEmpty())
            if (anyScope) runUpkeep(Trigger.APP_OPEN) else refresh()
        }
    }

    fun trustCurrentNetwork(name: String) {
        locked {
            val fp = fingerprints.current()
            val clean = name.trim()
            if (fp == null) return@locked refresh(error = Copy.NO_NETWORK_TO_TRUST)
            val existing = store.trustedNetworks()
            // Already trusted: keep that entry untouched (name and fingerprint) rather than rewriting it.
            if (TrustMatcher.currentTrusted(fp, existing) == null) {
                val id = "net-" + clock() + "-" + existing.size
                val label = clean.ifEmpty { formatText(Copy.defaultNetworkName(existing.size + 1)) }
                store.saveTrustedNetwork(TrustedNetwork(id, label, fp))
            }
            refresh()
        }
    }

    /** Trusts the tailnet this computer is on now, so TVs bound to it are kept alive wherever Tailscale is up. */
    fun trustCurrentOverlay(name: String) {
        locked {
            val snapshot = overlay?.source?.snapshot()
            if (snapshot == null || !snapshot.running || !snapshot.tun) return@locked refresh(error = Copy.NO_OVERLAY)
            trustSnapshot(snapshot, name)
            refresh()
        }
    }

    /** Saves the tailnet [snapshot] reports as trusted, unless it already is. The add path uses this too: the
     * tailnet the computer is signed into is trusted automatically when a TV is added on it. */
    private suspend fun trustSnapshot(snapshot: OverlaySnapshot, name: String) {
        val existing = store.trustedOverlays()
        if (OverlayTrustMatcher.currentTrusted(snapshot, existing) == null) {
            val id = "overlay-" + clock() + "-" + existing.size
            val label = name.trim().ifEmpty { formatText(Copy.defaultTailnetName(existing.size + 1)) }
            store.saveTrustedOverlay(TrustedOverlay(id, label, snapshot.tailnet, snapshot.selfId))
        }
    }

    /** Removes the tailnet and the TVs bound to it: upkeep would never check them again. */
    fun removeTrustedOverlay(id: String) {
        locked {
            store.tvs().filter { it.trustedNetworkId == id }.forEach { store.removeTv(it.id) }
            store.removeTrustedOverlay(id)
            refresh()
        }
    }

    /** Sets the user's label for network [id]; a blank label clears it (the identifier is shown instead). */
    fun renameTrustedNetwork(id: String, label: String) {
        locked {
            store.trustedNetworks().firstOrNull { it.id == id }?.let { store.saveTrustedNetwork(it.copy(name = label.trim())) }
            refresh()
        }
    }

    /** Removes the network and the TVs saved under it: upkeep would never check them again. */
    fun removeTrustedNetwork(id: String) {
        locked {
            store.tvs().filter { it.trustedNetworkId == id }.forEach { store.removeTv(it.id) }
            store.removeTrustedNetwork(id)
            refresh()
        }
    }

    /**
     * Adds the TV at [address]. A press while that address is being added, or right after it was added, does
     * nothing. Success sets [CompanionUiState.addedAddress]; failure sets [CompanionUiState.addErrors] for it.
     */
    /**
     * Starts adding the TV at [address]. A classic TV is not contacted until [acknowledged] is true.
     * A TV that offers wireless debugging opens the pairing form instead.
     */
    fun addTvByAddress(address: String, acknowledged: Boolean = false) {
        val a = address.trim()
        var accepted = false
        _state.update {
            accepted = a !in it.adding && it.addedAddress != a
            if (accepted) it.copy(adding = it.adding + a, addErrors = it.addErrors - a) else it
        }
        if (!accepted) return
        locked {
            try {
                addTv(a, acknowledged)
                val failure = _state.value.error
                val waiting = failure == null &&
                    (_state.value.classicAck?.address == a || _state.value.pairing?.address == a)
                _state.update {
                    when {
                        failure != null -> it.copy(addErrors = it.addErrors + (a to failure))
                        waiting -> it
                        else -> it.copy(addedAddress = a)
                    }
                }
            } finally {
                _state.update { it.copy(adding = it.adding - a) }
            }
        }
    }

    /** The acknowledgment checkbox on the Add screen. */
    fun setClassicChecked(checked: Boolean) {
        _state.update { s -> s.classicAck?.let { s.copy(classicAck = it.copy(checked = checked)) } ?: s }
    }

    /** The Home card's Pair button: pair the saved TV at its current address. */
    fun pairSaved(tvId: String) {
        val tv = _state.value.tvs.firstOrNull { it.id == tvId } ?: return
        startPairing(tv.address, tvId)
    }

    /** Opens the pairing form for [address]. [tvId] is set when pairing replaces a saved TV's link. */
    fun startPairing(address: String, tvId: String? = null) {
        _state.update { it.copy(pairing = PairingForm(address.trim(), tvId), classicAck = null, error = null) }
    }

    fun cancelPairing() {
        _state.update { it.copy(pairing = null) }
    }

    /** Sends the 6-digit code and the pairing port, then looks up the connect port. */
    fun submitPair(code: String, portText: String) {
        val form = _state.value.pairing ?: return
        locked {
            val existing = savedFor(form)
            // Pairing takes time. Refuse before it starts, and again before any session opens.
            val refused = contactRefusal(form.address, existing)
            if (refused != null) return@locked rejectPairing(form, refused)
            val port = portText.trim().toIntOrNull()
            val result = if (port == null) PairResult.Failed(PairCopy.BAD_PORT) else pairer.pair(form.address, port, code.trim())
            if (result !is PairResult.Paired) {
                _state.update { it.copy(pairing = form.copy(error = result.message())) }
                return@locked
            }
            val again = contactRefusal(form.address, existing)
            if (again != null) return@locked rejectPairing(form, again)
            val connect = ports.find(form.address)?.takeIf { it != TvConnector.DEFAULT_PORT }
            if (connect == null) {
                _state.update { it.copy(pairing = form.copy(error = null, askConnectPort = true)) }
                return@locked
            }
            saveEncrypted(form, connect)
        }
    }

    /** The connect port typed from the TV's wireless debugging screen, after mDNS found none. */
    fun submitConnectPort(portText: String) {
        val form = _state.value.pairing ?: return
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535 || port == TvConnector.DEFAULT_PORT) {
            _state.update { it.copy(pairing = form.copy(error = Copy.BAD_CONNECT_PORT)) }
            return
        }
        locked { saveEncrypted(form, port) }
    }

    /** The one-time acknowledgment for a saved classic TV. Then it can be checked. */
    fun acknowledgeClassic(tvId: String) {
        locked {
            val tv = store.tvs().firstOrNull { it.id == tvId } ?: return@locked
            val link = tv.link
            if (link !is TvLink.Classic || link.ackAtEpochMs != null) return@locked
            store.updateTv(tvId) { it.copy(link = TvLink.Classic(clock())) }
            offerMutex.withLock { tlsOffer.remove(tv.address) }
            runUpkeep(Trigger.APP_OPEN, tvId, mayEnroll = true)
        }
    }

    /** The person confirmed a TV that was saved before it had a secret. Then it can be checked. */
    fun confirmTv(tvId: String) {
        locked {
            val tv = store.tvs().firstOrNull { it.id == tvId } ?: return@locked
            if (tv.unproven || tv.identitySecret != null) return@locked
            runUpkeep(Trigger.APP_OPEN, tvId, mayEnroll = true)
        }
    }

    /** Stores a connect port the user typed for an encrypted TV, then checks it. */
    fun useTypedPort(tvId: String, portText: String) {
        val port = portText.trim().toIntOrNull()
        if (port == null || port !in 1..65535 || port == TvConnector.DEFAULT_PORT) {
            _state.update { it.copy(error = Copy.BAD_CONNECT_PORT) }
            return
        }
        locked {
            store.updateTv(tvId) { current ->
                if (current.link is TvLink.Encrypted) current.copy(link = TvLink.Encrypted(port)) else current
            }
            runUpkeep(Trigger.APP_OPEN, tvId)
        }
    }

    /** The Add screen has shown the result of [CompanionUiState.addedAddress]. */
    fun acknowledgeAdded() {
        _state.update { it.copy(addedAddress = null) }
    }

    /**
     * Sweeps the Trusted Network for TVs, streaming results as they arrive. Runs in its own job outside the
     * [work] mutex, so adding a TV or running upkeep never waits for the sweep. A press while scanning, or a
     * second [keepScanning], does nothing. Clears the previous results first; [keepScanning] keeps them.
     */
    fun findTvs() = findTvsIn("")

    /** Sweeps the Trusted Network, limited to [range] when it names a block on it; blank is automatic. */
    fun findTvsIn(range: String) {
        findRange = range
        startFind(clearFound = true)
    }

    /** Re-sweeps without clearing what the stopped scan already found. */
    fun keepScanning() = startFind(clearFound = false)

    /** Stops the running sweep, keeping whatever it streamed so far. */
    fun cancelFind() {
        findJob?.cancel()
    }

    /** The Add screen went away: a running sweep stops with it. */
    fun onDispose() {
        findJob?.cancel()
    }

    private fun startFind(clearFound: Boolean) {
        var accepted = false
        _state.update {
            accepted = !it.scanning
            if (!accepted) it else it.copy(
                scanning = true,
                noneFound = false,
                found = if (clearFound) emptyList() else it.found,
                tlsHosts = if (clearFound) emptyList() else it.tlsHosts,
                scanProgress = null,
                scanStopped = false,
                scanTruncated = false,
                diagnosis = null,
            )
        }
        if (!accepted) return
        findJob?.cancel()
        // A run that finished has stopped touching these sets before it cleared `scanning`.
        val resumeSkip = if (clearFound) emptySet() else probedSinceFind.toSet()
        if (clearFound) {
            probedSinceFind.clear()
            liveNonAdbSinceFind.clear()
        }
        lastSummary = DiscoverySummary()
        findJob = scope.launch {
            var cancelled = false
            var swept = false
            try {
                swept = collectFind(resumeSkip)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                _state.update {
                    it.copy(
                        scanning = false,
                        scanStopped = cancelled,
                        scanTruncated = !cancelled && lastSummary.truncated,
                        // A run that never swept (no network, plan refused) has nothing to diagnose.
                        diagnosis = if (!swept && !cancelled) null else diagnose(
                            lastSummary.copy(liveNonAdb = liveNonAdbSinceFind.size),
                            found = it.found.isNotEmpty() || it.tlsHosts.isNotEmpty(),
                            complete = !cancelled,
                        ),
                    )
                }
            }
        }
    }

    /** Returns whether a sweep ran to its end. Its refreshes leave `busy` alone: an add or upkeep may still be running. */
    private suspend fun collectFind(resumeSkip: Set<Ipv4>): Boolean {
        val trusted = currentTrusted()
        val subnet = trusted?.let { Cidr.parse(it.fingerprint.subnet) }
        if (trusted == null || subnet == null) {
            refresh(error = Copy.NOT_TRUSTED_ERROR, settleBusy = false)
            return false
        }
        val saved = store.tvs().map { it.address }.toSet()
        // The live address, not the saved one: DHCP moves it, and networks saved before ownIp existed have none.
        // This second read can be the first to see the gateway MAC; store it when the record lacks one.
        val live = fingerprints.current()
        hardened(store, live)
        val own = live?.ownIp?.let(Ipv4::parse) ?: trusted.fingerprint.ownIp?.let(Ipv4::parse)
        val gateway = Ipv4.parse(trusted.fingerprint.gatewayIp)
        val scope = when (val c = scanRangeFor(findRange, trusted)) {
            RangeChoice.Invalid -> {
                refresh(error = Copy.BAD_RANGE, settleBusy = false)
                return false
            }
            is RangeChoice.Custom -> c.cidr
            RangeChoice.Default -> sweepScope(subnet, own, gateway)
        }
        var swept = false
        val trustedIdAtStart = trusted.id
        var lastTrustCheckMs = clock()
        try {
        discovery.run(DiscoveryPlan(scope, own = own, gateway = gateway, skip = resumeSkip)).collect { event ->
            when (event) {
                is DiscoveryEvent.Progress -> {
                    val now = clock()
                    if (now - lastTrustCheckMs >= 1_000) {
                        lastTrustCheckMs = now
                        val live = currentTrusted()
                        if (live == null || live.id != trustedIdAtStart) throw FindTrustLost()
                    }
                    _state.update { it.copy(scanProgress = Copy.scanProgress(event.scanned, event.total)) }
                }
                is DiscoveryEvent.Found -> {
                    val host = event.address.toString()
                    _state.update { s ->
                        if (host in saved || host in s.found || host in s.tlsHosts) s
                        else if (event.result == ProbeResult.AdbTls) s.copy(tlsHosts = s.tlsHosts + host)
                        else s.copy(found = s.found + host)
                    }
                }
                is DiscoveryEvent.Probed -> {
                    probedSinceFind += event.address
                    if (!event.result.isAdb) liveNonAdbSinceFind += event.address
                }
                is DiscoveryEvent.Done -> {
                    lastSummary = event.summary
                    swept = true
                }
            }
        }
        } catch (_: FindTrustLost) {
            val keptFound = _state.value.found
            val keptTls = _state.value.tlsHosts
            refresh(
                error = Copy.NOT_TRUSTED_ERROR,
                settleBusy = false,
                retainFound = keptFound,
                retainTlsHosts = keptTls,
            )
            return false
        }
        refresh(error = if (swept) null else Copy.CANT_SCAN, settleBusy = false)
        if (swept) _state.update { it.copy(noneFound = it.found.isEmpty() && it.tlsHosts.isEmpty()) }
        return swept
    }

    /** Checks only [tvId] (or every TV on this Trusted Network when null) and clears its "waiting for Allow" skip. */
    fun restartNow(tvId: String? = null) {
        locked {
            if (tvId != null) store.updateTv(tvId) { it.copy(skipBackgroundUntilUserAction = false) }
            runUpkeep(Trigger.RESTART_NOW, tvId)
        }
    }

    /** The restart message has been shown (or there is nothing to show): clear it. */
    fun acknowledgeRestart() {
        _state.update { it.copy(lastRestart = null) }
    }

    fun removeTv(id: String) {
        scope.launch {
            val tv = work.withLock {
                val found = store.tvs().firstOrNull { it.id == id }
                store.removeTv(id)
                refresh()
                found
            }
            if (tv != null) forgetQuietly(tv)
        }
    }

    /**
     * Best effort, after the record is already gone, and outside [work] so a powered-off TV cannot hold the UI.
     * Gives up after [FORGET_TIMEOUT_MS]. Nothing is sent unless this network could check the TV, and the
     * saved secret proves first. A failed proof sends no forget.
     */
    private suspend fun forgetQuietly(tv: SavedTv) {
        val checker = identity ?: return
        if (tv.identitySecret == null) return
        if (!checkableHere(tv, currentTrusted(), store.trustedOverlays())) return
        try {
            withTimeout(FORGET_TIMEOUT_MS) {
                sessions.open(tv).use { checker.forget(it) }
            }
        } catch (_: TimeoutCancellationException) {
            // The TV did not answer in time. The record is already gone.
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Removal does not wait on the TV. A failed proof is one of these: the session is already closed.
        }
    }

    /**
     * The Home screen appeared (true) or went away (false). Polling runs only while it is visible
     * and the app is in the foreground (see [setForeground]): one pass over the checkable, running
     * TVs every [ACTIVITY_POLL_MS], reusing one ADB session per TV. Three consecutive failures park
     * a TV until the next visit; unparsable output keeps the previous state. A mismatched serial at the
     * gate is one of those failures: that pass sends nothing else. This never runs from
     * background upkeep: upkeep never calls it.
     */
    fun setHomeVisible(visible: Boolean) {
        if (visible) parkedTvIds.clear()
        if (homeVisible == visible) return
        homeVisible = visible
        updatePolling()
    }

    /** The app moved to the foreground (true) or background (false). */
    fun setForeground(inForeground: Boolean) {
        if (foreground == inForeground) return
        foreground = inForeground
        updatePolling()
    }

    private fun updatePolling() {
        pollJob?.cancel()
        pollJob = null
        pollSessions.values.forEach { runCatching { it.close() } }
        pollSessions.clear()
        if (!homeVisible || !foreground) return
        pollJob = scope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val trusted = currentTrusted()
            val overlays = store.trustedOverlays()
            val cards = _state.value.tvs.associateBy { it.id }
            val eligible = store.tvs().mapNotNull { saved ->
                if (!checkableHere(saved, trusted, overlays)) return@mapNotNull null
                val card = cards[saved.id] ?: return@mapNotNull null
                if (saved.unproven || card.status != TvStatus.RUNNING || saved.id in parkedTvIds) return@mapNotNull null
                if (identity != null && saved.identitySecret == null) return@mapNotNull null
                card
            }
            val ids = eligible.map { it.id }.toSet()
            (pollSessions.keys - ids).forEach { id -> runCatching { pollSessions.remove(id)?.close() } }
            for (tv in eligible) {
                currentCoroutineContext().ensureActive()
                // The network can change between TVs in one pass. Read it again, as upkeep does before each call.
                val savedNow = store.tvs().firstOrNull { it.id == tv.id }
                if (savedNow == null || !checkableHere(savedNow, currentTrusted(), store.trustedOverlays())) {
                    runCatching { pollSessions.remove(tv.id)?.close() }
                    continue
                }
                pollOneTv(tv)
            }
            delay(ACTIVITY_POLL_MS)
        }
    }

    private suspend fun pollOneTv(tv: TvCardState) {
        try {
            val saved = store.tvs().firstOrNull { it.id == tv.id } ?: return
            if (identity != null && saved.identitySecret == null) return
            val newSession = tv.id !in pollSessions
            val session = pollSessions.getOrPut(tv.id) { sessions.open(saved) }
            if (newSession) {
                val serial = session.run(TvCommands.SERIAL).trim()
                if (serial != saved.serial) {
                    runCatching { pollSessions.remove(tv.id)?.close() }
                    pollFailures.remove(tv.id)
                    parkedTvIds += tv.id
                    return
                }
            }
            val parsed = parseActivityRow(session.run(TvCommands.STATUS_QUERY))
            pollFailures.remove(tv.id)
            if (parsed != null) setActivity(tv.id, parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NeedsConfirmation) {
            runCatching { pollSessions.remove(tv.id)?.close() }
        } catch (_: TvUnproven) {
            runCatching { pollSessions.remove(tv.id)?.close() }
            store.updateTv(tv.id) { it.copy(unproven = true, status = TvStatus.CHECK_FAILED, reach = Reach.UNREACHABLE) }
            parkedTvIds += tv.id
            refresh()
        } catch (_: TvWrongDevice) {
            // The gate already closed the session. This pass sends nothing and does not change the saved TV.
            recordPollFailure(tv.id)
        } catch (_: Exception) {
            recordPollFailure(tv.id)
        }
    }

    /** One failed activity check. Three in a row park the TV until Home is opened again. */
    private fun recordPollFailure(id: String) {
        runCatching { pollSessions.remove(id)?.close() }
        val count = (pollFailures[id] ?: 0) + 1
        if (count >= MAX_ACTIVITY_FAILURES) {
            pollFailures.remove(id)
            parkedTvIds += id
        } else {
            pollFailures[id] = count
        }
    }

    private fun setActivity(id: String, activity: TvActivity) {
        activities[id] = activity
        _state.update { s -> s.copy(tvs = s.tvs.map { if (it.id == id) it.copy(activity = activity) else it }) }
    }

    /** One install, from file pick to result. [ended] flips once, when the run is over or cancelled. */
    private class InstallRun(val tvId: String, val source: InstallSource) {
        val ended = AtomicBoolean(false)
        private val sourceClosed = AtomicBoolean(false)
        @Volatile var job: Job? = null
        @Volatile var pkg: InspectedPackage? = null
        @Volatile var preflight: PreflightResult? = null

        fun closeSource() {
            if (sourceClosed.compareAndSet(false, true)) runCatching { source.close() }
        }
    }

    private val run = MutableStateFlow<InstallRun?>(null)

    /**
     * Starts installing [source] on the TV [tvId]. One install runs at a time; a second is closed and ignored.
     * The Trusted Network gate runs before the file is read and again before anything is sent.
     * This takes ownership of [source] and always closes it.
     */
    fun beginInstall(tvId: String, source: InstallSource) {
        val r = InstallRun(tvId, source)
        if (!run.compareAndSet(null, r)) {
            runCatching { source.close() }
            return
        }
        startStage(r) { inspectStage(r) }
    }

    fun setInstallKeepOptimized(keep: Boolean) {
        _state.update { s ->
            val cur = s.install
            if (cur == null || cur.phase !is InstallPhase.Review) s else s.copy(install = cur.copy(keepOptimized = keep))
        }
    }

    /** Sends the reviewed app to the TV. Only a review can be confirmed, and only once. */
    fun confirmInstall() {
        val r = run.value ?: return
        var accepted = false
        _state.update { s ->
            val cur = s.install
            val review = (cur?.phase as? InstallPhase.Review)?.review
            if (cur == null || review == null || run.value !== r) {
                accepted = false
                s
            } else {
                accepted = true
                s.copy(install = cur.copy(phase = InstallPhase.Transferring(0L, review.totalBytes, 0L)))
            }
        }
        if (accepted) startStage(r) { transferStage(r) }
    }

    /** Stops the install and drops the file. Refused once the TV is committing, when it can no longer be undone. */
    fun cancelInstall() {
        val r = run.value ?: return
        var accepted = false
        _state.update { s ->
            val cur = s.install
            if (cur == null || run.value !== r || !cur.phase.canCancel) {
                accepted = false
                s
            } else {
                accepted = true
                s.copy(install = null)
            }
        }
        if (!accepted) return
        r.ended.set(true)
        run.compareAndSet(r, null)
        val job = r.job
        job?.cancel()
        // The upload may still be unwinding and abandoning its session. The file closes only after it stops.
        if (job == null) r.closeSource() else job.invokeOnCompletion { r.closeSource() }
    }

    /** Re-checks the TV and file after a failure that can be tried again, then shows the review. */
    fun retryInstall() {
        val r = run.value ?: return
        var accepted = false
        _state.update { s ->
            val cur = s.install
            val failed = cur?.phase as? InstallPhase.Failed
            if (cur == null || failed == null || !failed.canRetry || run.value !== r) {
                accepted = false
                s
            } else {
                accepted = true
                s.copy(install = cur.copy(phase = InstallPhase.Inspecting))
            }
        }
        if (accepted) startStage(r) { inspectStage(r) }
    }

    fun dismissInstallNotice(tvId: String) {
        _state.update { it.copy(installNotice = it.installNotice - tvId) }
    }

    /**
     * Stops any install for good, even one that is committing, because the app is closing.
     * Waits briefly for the upload to abandon its session on the TV, then closes the file.
     */
    suspend fun cancelInstallForShutdown() {
        val r = run.value ?: return
        r.ended.set(true)
        run.compareAndSet(r, null)
        _state.update { it.copy(install = null) }
        val job = r.job
        job?.cancel()
        withContext(NonCancellable) {
            withTimeoutOrNull(SHUTDOWN_WAIT_MS) { job?.join() }
            r.closeSource()
        }
    }

    private fun startStage(r: InstallRun, body: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { body() }
        r.job = job
        job.start()
    }

    /** Applies [change] to the install of [r] unless it was cancelled or replaced. */
    private fun updateInstall(r: InstallRun, change: (InstallSessionUi) -> InstallSessionUi) {
        _state.update { s ->
            val cur = s.install
            if (cur == null || r.ended.get() || run.value !== r) s else s.copy(install = change(cur))
        }
    }

    /** The run is over: clear the install, leave [notice] on the TV's card, and close the file. */
    private fun finishRun(r: InstallRun, notice: UiText?) {
        r.ended.set(true)
        if (run.compareAndSet(r, null)) {
            _state.update { s ->
                s.copy(
                    install = null,
                    installNotice = if (notice != null) s.installNotice + (r.tvId to notice) else s.installNotice,
                )
            }
        }
        r.closeSource()
    }

    private fun failRun(r: InstallRun, message: UiText, canRetry: Boolean) {
        updateInstall(r) { it.copy(phase = InstallPhase.Failed(message, canRetry)) }
    }

    /** The saved TV [tvId], only while this computer may contact it right now. */
    private suspend fun trustedTv(tvId: String): SavedTv? {
        val saved = store.tvs().firstOrNull { it.id == tvId } ?: return null
        if (saved.unproven) return null
        return saved.takeIf { checkableHere(it, currentTrusted(), store.trustedOverlays()) }
    }

    /** Opens [tv] and refuses the session unless its serial is the saved one. Runs on the caller's dispatcher. */
    private suspend fun openChecked(tv: SavedTv, forInstall: Boolean): TvSession {
        val session = if (forInstall) sessions.openForInstall(tv) else sessions.open(tv)
        try {
            if (session.run(TvCommands.SERIAL).trim() != tv.serial) throw TvWrongDevice()
        } catch (e: Throwable) {
            runCatching { session.close() }
            throw e
        }
        return session
    }

    private fun openFailure(e: Throwable): UiText = when (e) {
        is TvWrongDevice -> Copy.DIFFERENT_DEVICE
        is TvUnproven -> Copy.TV_UNPROVEN
        is NeedsConfirmation -> Copy.CONFIRM_TV
        else -> Copy.INSTALL_FAILED
    }

    private fun canRetry(e: Throwable): Boolean = e !is TvWrongDevice && e !is TvUnproven && e !is NeedsConfirmation

    private fun canRetry(error: InstallError?): Boolean = when (error) {
        null, InstallError.TvUnavailable, InstallError.UntrustedNetwork -> true
        is InstallError.InstallFailed ->
            error.code == InstallFailureCode.UNKNOWN || error.code == InstallFailureCode.INSUFFICIENT_STORAGE
        else -> false
    }

    /** Checks the Trusted Network, reads the file, then asks the TV what it needs. Ends at the review. */
    private suspend fun inspectStage(r: InstallRun) {
        try {
            val saved = trustedTv(r.tvId)
            if (saved == null) {
                finishRun(r, Copy.NOT_TRUSTED_ERROR)
                return
            }
            updateOrPublish(r, saved)
            val pkg = r.pkg ?: withContext(ioDispatcher) { ApkInspector.inspect(r.source) }.getOrElse {
                return failRun(r, Copy.installError(it.installErrorOrNull() ?: InstallError.NotAnApk), canRetry = false)
            }.also { r.pkg = it }
            val pre = withContext(ioDispatcher) {
                openChecked(saved, forInstall = true).use { installer.preflight(it, pkg) }
            }.getOrElse {
                val error = it.installErrorOrNull()
                return failRun(r, error?.let(Copy::installError) ?: openFailure(it), canRetry(error ?: InstallError.TvUnavailable))
            }
            r.preflight = pre
            val review = InstallReview(
                appName = pkg.displayLabel,
                packageName = pkg.packageName,
                newVersion = pkg.versionName ?: pkg.versionCode.toString(),
                installedVersion = pre.installedVersionName ?: pre.installed?.versionCode?.toString(),
                summaryTags = pre.selection.summaryTags,
                totalBytes = pre.selection.totalBytes,
                store = when (pre.installerPackage) {
                    "com.android.vending" -> InstallStoreSource.GOOGLE_PLAY
                    "com.amazon.venezia" -> InstallStoreSource.AMAZON_APPSTORE
                    else -> null
                },
                isSystemApp = pre.isSystemApp,
            )
            val remote = activities[saved.id]?.remoteSupported == true
            updateInstall(r) {
                it.copy(
                    phase = InstallPhase.Review(review),
                    showKeepToggle = remote && pkg.packageName !in SideSpeedPackages.ALL,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failRun(r, openFailure(e), canRetry(e))
        }
    }

    /** Shows the install screen for [saved] on the first pass; a retry only moves the phase back to checking. */
    private fun updateOrPublish(r: InstallRun, saved: SavedTv) {
        _state.update { s ->
            if (r.ended.get() || run.value !== r) return@update s
            val cur = s.install
            s.copy(
                install = cur?.copy(phase = InstallPhase.Inspecting) ?: InstallSessionUi(
                    tvId = saved.id,
                    tvName = saved.name,
                    isTailnet = saved.overlayPeerId != null,
                    phase = InstallPhase.Inspecting,
                    keepOptimized = true,
                    showKeepToggle = false,
                ),
            )
        }
    }

    /** Checks the Trusted Network again, then streams the chosen files to the TV and hands the result to the card. */
    private suspend fun transferStage(r: InstallRun) {
        var hostStarted = false
        try {
            val pkg = r.pkg
            val pre = r.preflight
            val ui = _state.value.install
            val saved = trustedTv(r.tvId)
            if (pkg == null || pre == null || ui == null) return finishRun(r, null)
            if (saved == null) return failRun(r, Copy.NOT_TRUSTED_ERROR, canRetry = true)
            transferHost.onStart(saved.name, pkg.displayLabel)
            hostStarted = true
            val result = withContext(ioDispatcher) {
                openChecked(saved, forInstall = true).use { session ->
                    installer.install(
                        session = session,
                        tvSerial = saved.serial,
                        pkg = pkg,
                        selection = pre.selection,
                        source = r.source,
                        keepOptimized = ui.keepOptimized,
                        onPhase = { onInstallPhase(r, it) },
                        abandonOnFreshSession = { id -> abandonSession(saved, id) },
                    )
                }
            }
            val outcome = result.getOrNull()
            if (outcome != null) return finishRun(r, Copy.installed(outcome))
            val error = result.installErrorOrNull()
            failRun(r, error?.let(Copy::installError) ?: Copy.INSTALL_FAILED, canRetry(error))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failRun(r, openFailure(e), canRetry(e))
        } finally {
            if (hostStarted) transferHost.onEnd()
        }
    }

    private fun onInstallPhase(r: InstallRun, phase: InstallTransferPhase) {
        when (phase) {
            is InstallTransferPhase.Transferring -> {
                updateInstall(r) {
                    it.copy(phase = InstallPhase.Transferring(phase.bytesSent, phase.totalBytes, phase.bytesPerSec))
                }
                transferHost.onProgress(phase.bytesSent, phase.totalBytes, committing = false)
            }
            InstallTransferPhase.Committing -> {
                updateInstall(r) { it.copy(phase = InstallPhase.Committing) }
                val total = r.preflight?.selection?.totalBytes ?: 0L
                transferHost.onProgress(total, total, committing = true)
            }
            InstallTransferPhase.StartingCompile -> {
                updateInstall(r) { it.copy(phase = InstallPhase.StartingCompile) }
                val total = r.preflight?.selection?.totalBytes ?: 0L
                transferHost.onProgress(total, total, committing = true)
            }
        }
    }

    /** Drops a half-written install session from a fresh connection, because the first one is already closed. */
    private suspend fun abandonSession(tv: SavedTv, sessionId: String) {
        withContext(ioDispatcher) {
            openChecked(tv, forInstall = true).use { session ->
                session.openPackageCmd(*TvCommands.installAbandonArgs(sessionId)).use { it.readAllText() }
            }
        }
    }

    /**
     * Asks SideSpeed on the TV to optimize its queued apps now. A second press while one is running does nothing.
     * The result lands in [CompanionUiState.optimizeNotice].
     */
    fun optimizeNow(tvId: String) {
        var accepted = false
        _state.update { s ->
            if (tvId in s.optimizingTvIds) {
                accepted = false
                s
            } else {
                accepted = true
                s.copy(optimizingTvIds = s.optimizingTvIds + tvId, optimizeNotice = s.optimizeNotice - tvId)
            }
        }
        if (!accepted) return
        scope.launch {
            var notice: UiText = Copy.OPTIMIZE_FAILED
            try {
                notice = requestOptimize(tvId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The notice stays "couldn't start".
            } finally {
                _state.update { s ->
                    s.copy(optimizingTvIds = s.optimizingTvIds - tvId, optimizeNotice = s.optimizeNotice + (tvId to notice))
                }
            }
        }
    }

    private suspend fun requestOptimize(tvId: String): UiText {
        val saved = trustedTv(tvId) ?: return Copy.NOT_TRUSTED_ERROR
        val output = withContext(ioDispatcher) {
            openChecked(saved, forInstall = false).use { it.run(TvCommands.CALL_OPTIMIZE_NOW) }
        }
        return when (val result = TvCommands.parseContentCallResult(output)) {
            is ContentCallResult.Ok -> if (result.queued > 0) Copy.OPTIMIZE_STARTED else Copy.OPTIMIZE_CAUGHT_UP
            else -> Copy.OPTIMIZE_FAILED
        }
    }

    fun updateSettings(s: CompanionSettings) {
        locked {
            store.saveSettings(s)
            refresh()
        }
    }

    /** Re-reads the store and network into [state], for callers that ran upkeep themselves (the tray timers). */
    fun refreshNow() {
        locked { refresh() }
    }

    /** Runs [block] one at a time, so busy and error state can't be clobbered by overlapping actions. */
    private fun locked(block: suspend () -> Unit) {
        scope.launch { work.withLock { block() } }
    }

    private suspend fun runUpkeep(trigger: Trigger, onlyTvId: String? = null, mayEnroll: Boolean = false) {
        _state.update { it.copy(busy = true) }
        var report: UpkeepReport? = null
        try {
            report = upkeep.runOnce(trigger, onlyTvId, mayEnroll)
        } finally {
            val finished = report
            if (onlyTvId == null || finished == null) {
                // A throw still ends busy, but without a report there is no restart outcome to record.
                refresh()
            } else {
                // The outcome lands in the same state update that ends busy,
                // so the screen never sees one without the other.
                val status = finished.visited[onlyTvId]
                val outcome = when {
                    status == TvStatus.RUNNING -> RestartOutcome.RESTARTED
                    status != null -> RestartOutcome.CHECKED
                    else -> RestartOutcome.NOT_CHECKED
                }
                refresh(lastRestart = RestartResult(onlyTvId, outcome), updateLastRestart = true)
            }
        }
    }

    private suspend fun currentTrusted(): TrustedNetwork? = hardened(store, fingerprints.current())

    /**
     * Whether upkeep could contact [tv] right now: a LAN TV when the current Trusted Network is the one it is
     * saved under; a tailnet TV when the overlay gate upkeep checks before a visit would let it through.
     */
    private suspend fun checkableHere(tv: SavedTv, trusted: TrustedNetwork?, overlays: List<TrustedOverlay>): Boolean {
        if (tv.overlayPeerId == null) return trusted?.id == tv.trustedNetworkId
        val gate = overlayGate ?: return false
        val bound = overlays.firstOrNull { it.id == tv.trustedNetworkId } ?: return false
        return gate.verdict(bound, tv) is OverlayVerdict.Open
    }

    private suspend fun addTv(address: String, acknowledged: Boolean) {
        if (!isIpv4(address)) return refresh(error = Copy.BAD_ADDRESS)
        val ip = Ipv4.parse(address)
        // A tailnet peer's address binds the TV to that peer on the trusted tailnet, which also works away from home.
        var binding = ip?.let { overlayGate?.bindingFor(it, store.trustedOverlays()) }
        // A node shared in from another tailnet is never trusted, not even through the home network (design section 6).
        if (binding == null && ip != null && AddressPolicy.isOverlay(ip) &&
            overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
        ) return refresh(error = Copy.SHARED_PEER)
        if (binding == null && ip != null && overlayGate != null) {
            // Our own tailnet is trusted automatically when a TV is added on it.
            val current = overlayGate.peerOnCurrentTailnet(ip)
            if (current != null) {
                trustSnapshot(current.snapshot, "")
                binding = overlayGate.bindingFor(ip, store.trustedOverlays())
            }
        }
        val trusted = if (binding == null) currentTrusted() ?: return refresh(error = Copy.NOT_TRUSTED_ADD) else null
        // While on a Trusted Network any private (RFC 1918) or overlay (100.64.0.0/10) address may be tried; public addresses never are.
        if (ip == null || !AddressPolicy.contactable(ip)) return refresh(error = Copy.OUTSIDE_NETWORK)
        val scopeId = binding?.overlay?.id ?: trusted!!.id
        if (address in _state.value.tlsHosts || linkOffer.encryptedAvailable(address) || ports.find(address) != null) {
            _state.update { it.copy(pairing = PairingForm(address), classicAck = null, error = null) }
            return
        }
        if (!acknowledged) {
            _state.update { it.copy(classicAck = ClassicAck(address), pairing = null, error = null) }
            return
        }
        _state.update { it.copy(busy = true, error = null, classicAck = null) }
        val found = readIdentity(TvTarget("pending", address, TvLink.Classic(clock()))) ?: return
        val serial = found.serial
        val model = found.model
        val maker = found.maker
        // The network may have changed during the connect: don't record a TV for a network we've left.
        val stillHere = if (binding != null) {
            overlayGate?.bindingFor(ip, store.trustedOverlays())
                ?.let { it.overlay.id == binding.overlay.id && it.peer.id == binding.peer.id } == true
        } else {
            currentTrusted()?.id == scopeId
        }
        if (!stillHere) return refresh(error = Copy.NOT_TRUSTED_ADD)
        if (serial.isBlank()) return refresh(error = Copy.NO_SERIAL)
        // Same TV: same scope, or any LAN entry when this add is a LAN one (a TV moved between Trusted Networks).
        val existing = store.tvs().firstOrNull {
            it.serial == serial && (it.trustedNetworkId == scopeId || (binding == null && it.overlayPeerId == null))
        }
        val tvName = model.ifBlank { formatText(Copy.defaultTvName(address)) }
        store.saveTv(
            SavedTv(
                id = existing?.id ?: idGenerator(),
                name = tvName,
                address = address,
                serial = serial,
                trustedNetworkId = scopeId,
                overlayPeerId = binding?.peer?.id,
                isFireTv = maker.equals("Amazon", ignoreCase = true),
                link = TvLink.Classic(clock()),
                identitySecret = found.secret,
            ),
        )
        _state.update { it.copy(classicAck = null, pairing = null) }
        runUpkeep(Trigger.APP_OPEN)
    }

    private suspend fun saveEncrypted(form: PairingForm, port: Int) {
        val existing = savedFor(form)
        // Again, immediately before the session opens: the network may have changed since pairing.
        val refused = contactRefusal(form.address, existing)
        if (refused != null) return rejectPairing(form, refused)
        _state.update { it.copy(busy = true, error = null) }
        var different = false
        val target = TvTarget(existing?.id ?: "pending", form.address, TvLink.Encrypted(port))
        // A saved TV opens through its record, so the gate proves a secret before any command.
        val saved = existing?.copy(address = form.address, link = TvLink.Encrypted(port))
        val found = readIdentity(target, saved) { serial ->
            if (existing != null && serial.isNotBlank() && existing.serial != serial) {
                different = true
                false
            } else {
                true
            }
        } ?: run {
            if (existing != null && store.tvs().firstOrNull { it.id == existing.id }?.unproven == true) return
            if (different) {
                _state.update { it.copy(pairing = form.copy(error = Copy.DIFFERENT_DEVICE), error = Copy.DIFFERENT_DEVICE, busy = false) }
            } else {
                val message = _state.value.error
                _state.update { it.copy(pairing = form.copy(error = message)) }
            }
            return
        }
        val serial = found.serial
        val model = found.model
        val maker = found.maker
        if (serial.isBlank()) {
            _state.update { it.copy(pairing = form.copy(error = Copy.NO_SERIAL), error = Copy.NO_SERIAL, busy = false) }
            return
        }
        if (existing != null) {
            store.updateTv(existing.id) {
                it.copy(
                    address = form.address,
                    link = TvLink.Encrypted(port),
                    status = TvStatus.UNKNOWN,
                    // A secret already on the TV stays. A legacy TV keeps the one the gate just stored.
                    identitySecret = existing.identitySecret ?: it.identitySecret,
                    unproven = false,
                )
            }
            offerMutex.withLock { tlsOffer.remove(form.address) }
            _state.update { it.copy(pairing = null, addedAddress = form.address) }
            runUpkeep(Trigger.APP_OPEN, existing.id)
            return
        }
        val ip = Ipv4.parse(form.address) ?: return refresh(error = Copy.BAD_ADDRESS)
        val binding = overlayGate?.bindingFor(ip, store.trustedOverlays())
        if (binding == null && AddressPolicy.isOverlay(ip) &&
            overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
        ) return refresh(error = Copy.SHARED_PEER)
        val trusted = if (binding == null) currentTrusted() ?: return refresh(error = Copy.NOT_TRUSTED_ADD) else null
        val scopeId = binding?.overlay?.id ?: trusted!!.id
        val prior = store.tvs().firstOrNull {
            it.serial == serial && (it.trustedNetworkId == scopeId || (binding == null && it.overlayPeerId == null))
        }
        val tvName = model.ifBlank { formatText(Copy.defaultTvName(form.address)) }
        store.saveTv(
            SavedTv(
                id = prior?.id ?: idGenerator(),
                name = tvName,
                address = form.address,
                serial = serial,
                trustedNetworkId = scopeId,
                overlayPeerId = binding?.peer?.id,
                isFireTv = maker.equals("Amazon", ignoreCase = true),
                link = TvLink.Encrypted(port),
                identitySecret = found.secret,
            ),
        )
        _state.update { it.copy(pairing = null, classicAck = null, addedAddress = form.address) }
        runUpkeep(Trigger.APP_OPEN)
    }

    private data class Identified(val serial: String, val model: String, val maker: String, val secret: String?)

    /**
     * Opens [target], or [saved] when this is a TV we already have, so its proof runs before any command.
     * A saved TV is not enrolled here: the gate writes a secret only when the record had none.
     * [accept] can refuse a serial before anything is written. On failure, records the error and returns null.
     */
    private suspend fun readIdentity(
        target: TvTarget,
        saved: SavedTv? = null,
        accept: (String) -> Boolean = { true },
    ): Identified? = try {
        val opened = if (saved != null) sessions.open(saved, mayEnroll = true) else sessions.open(target)
        opened.use { session ->
            val serial = session.run(TvCommands.SERIAL).trim()
            val model = session.run(TvCommands.MODEL).trim()
            val maker = session.run(TvCommands.MANUFACTURER).trim()
            if (!accept(serial)) return@use null
            val checker = identity
            val secret = if (saved == null && checker != null && serial.isNotBlank()) {
                checker.enroll(session) ?: throw TvCheckNotSaved()
            } else {
                null
            }
            Identified(serial, model, maker, secret)
        }
    } catch (e: TvCheckNotSaved) {
        refresh(error = Copy.TV_CHECK_NOT_SAVED)
        null
    } catch (e: TvAuthPending) {
        refresh(error = Copy.ALLOW_STEP)
        null
    } catch (e: EncryptedAvailable) {
        _state.update { it.copy(pairing = PairingForm(target.address), classicAck = null, error = null, busy = false) }
        null
    } catch (e: ClassicNotAcknowledged) {
        _state.update { it.copy(classicAck = ClassicAck(target.address), pairing = null, error = null, busy = false) }
        null
    } catch (e: TvUnreachable) {
        refresh(error = Copy.UNREACHABLE)
        null
    } catch (e: EncryptedPortUnknown) {
        refresh(error = Copy.UNREACHABLE)
        null
    } catch (e: TlsNotNegotiated) {
        refresh(error = Copy.UNREACHABLE)
        null
    } catch (e: AdbConnectionLost) {
        refresh(error = Copy.UNREACHABLE)
        null
    } catch (_: TvWrongDevice) {
        // The first serial did not match. Nothing else was sent, and nothing is saved.
        refresh(error = Copy.DIFFERENT_DEVICE)
        null
    } catch (_: TvUnproven) {
        // Same outcome as a failed check elsewhere: park the TV, save nothing about this pairing, and do not retry.
        if (saved != null && store.tvs().any { it.id == saved.id }) {
            store.updateTv(saved.id) {
                it.copy(unproven = true, status = TvStatus.CHECK_FAILED, reach = Reach.UNREACHABLE)
            }
            _state.update { it.copy(pairing = null) }
        }
        refresh()
        null
    }

    private suspend fun savedFor(form: PairingForm): SavedTv? =
        form.tvId?.let { id -> store.tvs().firstOrNull { it.id == id } }

    /**
     * Why [address] must not be contacted from here, or null when it may.
     * A private or overlay address, and either this tailnet or a Trusted Network.
     * A saved TV must also be on its own Trusted Network or overlay. A shared-in peer is refused.
     */
    private suspend fun contactRefusal(address: String, existing: SavedTv?): UiText? {
        if (!isIpv4(address)) return Copy.BAD_ADDRESS
        val ip = Ipv4.parse(address) ?: return Copy.BAD_ADDRESS
        if (!AddressPolicy.contactable(ip)) return Copy.OUTSIDE_NETWORK
        val overlays = store.trustedOverlays()
        val binding = overlayGate?.bindingFor(ip, overlays)
        if (binding == null && AddressPolicy.isOverlay(ip) &&
            overlay?.source?.snapshot()?.peers?.any { it.shared && ip in it.ips } == true
        ) return Copy.SHARED_PEER
        if (existing != null) {
            if (!checkableHere(existing, currentTrusted(), overlays)) return Copy.NOT_TRUSTED_ADD
            return null
        }
        if (binding == null && currentTrusted() == null) return Copy.NOT_TRUSTED_ADD
        return null
    }

    private suspend fun rejectPairing(form: PairingForm, message: UiText) {
        _state.update { it.copy(pairing = form.copy(error = message), busy = false) }
        refresh(error = message)
    }

    private suspend fun refresh(
        error: UiText? = null,
        settleBusy: Boolean = true,
        lastRestart: RestartResult? = null,
        updateLastRestart: Boolean = false,
        retainFound: List<String>? = null,
        retainTlsHosts: List<String>? = null,
    ) {
        val fp = fingerprints.current()
        val networks = store.trustedNetworks()
        // A match on a record made before the gateway MAC was read stores that MAC.
        val trusted = hardened(store, fp)
        val networksNow = if (trusted != null && networks.firstOrNull { it.id == trusted.id }?.fingerprint != trusted.fingerprint)
            store.trustedNetworks() else networks
        val overlays = store.trustedOverlays()
        val tailnet = overlay?.source?.snapshot()?.let { snap ->
            TailnetState(snap.tailnet, overlays.any { o -> OverlayTrustMatcher.matches(snap, o) }, snap.running && snap.tun)
        }
        for (tv in store.tvs()) {
            if (!tv.unproven || tv.status == TvStatus.CHECK_FAILED) continue
            store.updateTv(tv.id) { current ->
                if (!current.unproven || current.status == TvStatus.CHECK_FAILED) current
                else current.copy(status = TvStatus.CHECK_FAILED, reach = Reach.UNREACHABLE)
            }
        }
        val tvs = store.tvs()
        activities.keys.retainAll(tvs.map { it.id }.toSet())
        val saved = tvs.map { it.address }.toSet()
        val cards = ArrayList<TvCardState>(tvs.size)
        val addresses = tvs.map { it.address }.toSet()
        offerMutex.withLock { tlsOffer.keys.retainAll(addresses) }
        for (tv in tvs) {
            val parked = tv.unproven
            val here = checkableHere(tv, trusted, overlays)
            val status = if (parked) TvStatus.CHECK_FAILED else tv.status
            val attention = if (parked) TvAttention.NONE else attentionFor(tv)
            cards += TvCardState(
                id = tv.id,
                name = tv.name,
                address = tv.address,
                status = status,
                statusLabel = Copy.statusLabel(status),
                checkableHere = here,
                activity = activities[tv.id],
                reach = when {
                    !here -> null
                    parked -> Reach.UNREACHABLE
                    else -> tv.reach
                },
                checkedAtEpochMs = tv.lastCheckedEpochMs,
                note = when {
                    parked -> Copy.TV_UNPROVEN
                    tv.isFireTv -> null
                    else -> Copy.NOT_FIRE_TV_NOTE
                },
                scopeNote = if (tv.overlayPeerId == null && Ipv4.parse(tv.address)?.let(AddressPolicy::isOverlay) == true)
                    networks.firstOrNull { it.id == tv.trustedNetworkId }?.displayName?.let(Copy::keptAliveWhileOn)
                else null,
                attention = attention,
            )
        }
        _state.update {
            it.copy(
                loaded = true,
                savedByNewerVersion = store.savedByNewerVersion,
                firstRun = networks.isEmpty() && overlays.isEmpty(),
                currentNetwork = fp,
                trustedNetworkName = trusted?.displayName,
                trustedNetworks = networksNow,
                trustedOverlays = overlays,
                tailnet = tailnet,
                tvs = cards,
                settings = store.settings(),
                busy = if (settleBusy) false else it.busy,
                lastRestart = if (updateLastRestart) lastRestart else it.lastRestart,
                error = error,
                found = retainFound ?: if (trusted == null) emptyList() else it.found.filter { h -> h !in saved },
                tlsHosts = retainTlsHosts ?: if (trusted == null) emptyList() else it.tlsHosts.filter { h -> h !in saved },
            )
        }
        // The cards are already published. Asking whether a legacy TV can pair runs beside this, not inside it.
        scheduleOfferLookups(tvs)
    }

    /**
     * A legacy TV with no acknowledgment shows [TvAttention.LEGACY_ACK] until a lookup says it can pair.
     * The lookup is not done here: [refresh] must finish from the store alone.
     */
    private suspend fun attentionFor(tv: SavedTv): TvAttention {
        val link = tv.link
        return when {
            link is TvLink.Classic && link.ackAtEpochMs == null ->
                if (cachedOffer(tv.address) == true) TvAttention.LEGACY_PAIR else TvAttention.LEGACY_ACK
            link is TvLink.Encrypted && link.lastPort == null -> TvAttention.WIRELESS_OFF
            identity != null && tv.identitySecret == null -> TvAttention.CONFIRM
            link is TvLink.Encrypted && tv.status == TvStatus.NEEDS_ATTENTION -> TvAttention.WIRELESS_OFF
            link is TvLink.Classic && tv.status == TvStatus.NEEDS_ATTENTION -> TvAttention.LEGACY_PAIR
            else -> TvAttention.NONE
        }
    }

    private suspend fun cachedOffer(address: String): Boolean? = offerMutex.withLock { tlsOffer[address] }

    /**
     * Asks [linkOffer] and [ports] for each legacy TV that has not been asked yet and that
     * [checkableHere] would let a poll contact. A saved TV on another network is not probed.
     * Several run at once, up to [OFFER_LOOKUP_LIMIT]. Returns without waiting: the home screen is already up,
     * and a TV that can pair changes from [TvAttention.LEGACY_ACK] to [TvAttention.LEGACY_PAIR] when the answer arrives.
     */
    private suspend fun scheduleOfferLookups(tvs: List<SavedTv>) {
        val trusted = currentTrusted()
        val overlays = store.trustedOverlays()
        val pending = offerMutex.withLock {
            val claimed = ArrayList<String>()
            for (tv in tvs) {
                val link = tv.link
                if (tv.unproven || link !is TvLink.Classic || link.ackAtEpochMs != null) continue
                if (!checkableHere(tv, trusted, overlays)) continue
                val address = tv.address
                if (address in tlsOffer || address in offersInFlight) continue
                offersInFlight += address
                claimed += address
            }
            claimed
        }
        if (pending.isEmpty()) return
        scope.launch {
            val gate = Semaphore(OFFER_LOOKUP_LIMIT)
            try {
                coroutineScope {
                    pending.forEach { address ->
                        launch {
                            gate.withPermit {
                                try {
                                    val offered = linkOffer.encryptedAvailable(address) || ports.find(address) != null
                                    offerMutex.withLock {
                                        tlsOffer[address] = offered
                                        offersInFlight.remove(address)
                                    }
                                    if (offered) promoteToPair(address)
                                } catch (e: CancellationException) {
                                    offerMutex.withLock { offersInFlight.remove(address) }
                                    throw e
                                } catch (_: Exception) {
                                    offerMutex.withLock {
                                        tlsOffer[address] = false
                                        offersInFlight.remove(address)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                offerMutex.withLock { pending.forEach { offersInFlight.remove(it) } }
                throw e
            }
        }
    }

    private fun promoteToPair(address: String) {
        _state.update { s ->
            s.copy(
                tvs = s.tvs.map { card ->
                    if (card.address == address && card.attention == TvAttention.LEGACY_ACK) {
                        card.copy(attention = TvAttention.LEGACY_PAIR)
                    } else {
                        card
                    }
                },
            )
        }
    }

    private fun isIpv4(s: String): Boolean {
        val p = s.split('.')
        return p.size == 4 && p.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } && o.toInt() <= 255 }
    }

    private class FindTrustLost : Exception()

    private companion object {
        var counter = 0
        /** Home-screen activity polls run this often, one TV at a time, reusing each TV's session. */
        const val ACTIVITY_POLL_MS = 3_000L
        /** Consecutive poll failures that park a TV until the next screen visit. */
        const val MAX_ACTIVITY_FAILURES = 3
        /** How long a remove waits, in the background, for the TV to forget this companion. */
        const val FORGET_TIMEOUT_MS = 5_000L

        /** How long closing the app waits for an upload to abandon its session on the TV. */
        const val SHUTDOWN_WAIT_MS = 5_000L
        /** Legacy TVs asked at once whether they can use an encrypted link. The screen does not wait for them. */
        const val OFFER_LOOKUP_LIMIT = 4
    }
}
