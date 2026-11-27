package io.github.devjardins.sidespeed.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.TrustedBssids
import io.github.devjardins.sidespeed.maintenance.BssidReader
import io.github.devjardins.sidespeed.maintenance.BssidState
import io.github.devjardins.sidespeed.maintenance.RebootRecoveryJob
import io.github.devjardins.sidespeed.maintenance.RebootRecoveryStore
import io.github.devjardins.sidespeed.maintenance.SecureSettingsWriter
import io.github.devjardins.sidespeed.maintenance.sha256HexLower
import io.github.devjardins.sidespeed.ui.setup.SettingsReader
import kotlinx.coroutines.flow.first

interface RebootRecoveryCommands {
    suspend fun grantRebootRecovery(): ToolResult<Unit>
    suspend fun revokeSecureSettings(): ToolResult<Unit>
    suspend fun revokeRebootRecovery(): ToolResult<Unit>
    suspend fun adbTrustedNetworks(): ToolResult<List<String>>
}

interface RecoveryPermissions {
    fun hasAll(): Boolean
    fun hasAny(): Boolean
    fun hasSecureSettings(): Boolean
    fun revokeLocationOnKill()
}

class AndroidRecoveryPermissions(private val context: Context) : RecoveryPermissions {
    override fun hasAll(): Boolean = ALL_PERMISSIONS.all { isGranted(it) }

    override fun hasAny(): Boolean = ALL_PERMISSIONS.any { isGranted(it) }

    override fun hasSecureSettings(): Boolean = isGranted(Manifest.permission.WRITE_SECURE_SETTINGS)

    override fun revokeLocationOnKill() {
        // Intentionally a no-op: verified on Android 15 (sidespeed-pixel35) that
        // Context.revokeSelfPermissionsOnKill sets FLAG_PERMISSION_ONE_TIME on the location
        // permissions, and neither `pm revoke` nor `pm grant` clears ONE_TIME afterward—which
        // permanently breaks re-enabling "After a restart" across future reboots.
        // Location permissions are revoked cleanly via the helper's `revoke-reboot-recovery`
        // on ON_STOP / MaintenanceService / onHelperUp without setting ONE_TIME.
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        val ALL_PERMISSIONS = LOCATION_PERMISSIONS + Manifest.permission.WRITE_SECURE_SETTINGS
    }
}

interface RecoveryJobScheduler {
    fun scheduleDaily()
    fun cancelAll()
}

class AndroidRecoveryJobScheduler(private val context: Context) : RecoveryJobScheduler {
    override fun scheduleDaily() = RebootRecoveryJob.scheduleDaily(context)
    override fun cancelAll() = RebootRecoveryJob.cancelAll(context)
}

sealed interface EnableResult {
    data object Enabled : EnableResult
    data object NeedsPairing : EnableResult
    data object NeedsLocationSetting : EnableResult
    data object NeedsAlwaysAllowPrompt : EnableResult
    data object NeedsAlwaysAllowToggle : EnableResult
    data object NoWifiOrOldAndroid : EnableResult
    data object HelperDown : EnableResult
    data object GrantFailed : EnableResult
}

sealed interface DisableResult {
    data object Disabled : DisableResult
    data object Deferred : DisableResult
}

enum class RecoveryNotice {
    NEEDS_WIFI_OR_ANDROID_11,
    NEEDS_LOCATION_SETTING,
    NEEDS_ALWAYS_ALLOW_PROMPT,
    NEEDS_ALWAYS_ALLOW_TOGGLE,
    PENDING_REVOKES,
    GRANT_FAILED,
    HELPER_DOWN,
}

/**
 * Coordinates turning "After a restart" on and off on Google TV / Android TV 11+.
 * Maintains the security invariant: `enabled == false && !pendingRevokes` implies zero
 * reboot-recovery permissions remain granted.
 */
class RebootRecoveryController(
    private val commands: RebootRecoveryCommands,
    private val store: RebootRecoveryStore,
    private val prefs: Prefs,
    private val bssidReader: BssidReader,
    private val settings: SettingsReader,
    private val secureWriter: SecureSettingsWriter,
    private val permissions: RecoveryPermissions,
    private val jobs: RecoveryJobScheduler,
    val isFireOs: Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val isWifiConnected: () -> Boolean,
) {
    fun canEnable(): Boolean = !isFireOs && sdkInt >= 30 && isWifiConnected()

    suspend fun needsPairing(): Boolean = !prefs.pairedTls.first()

    suspend fun enable(): EnableResult {
        if (!canEnable()) return EnableResult.NoWifiOrOldAndroid
        if (!prefs.pairedTls.first()) return EnableResult.NeedsPairing

        when (commands.grantRebootRecovery()) {
            is ToolResult.HelperDown -> return EnableResult.HelperDown
            is ToolResult.Ok -> Unit
            else -> {
                rollbackFailedGrant()
                return EnableResult.GrantFailed
            }
        }
        if (!permissions.hasAll()) {
            rollbackFailedGrant()
            return EnableResult.GrantFailed
        }

        // Persist enabled immediately after grants succeed so the invariant holds even if Step 5 is abandoned.
        store.setEnabled(true)
        store.setPendingRevokes(false)
        store.setDailyRefreshFailed(false)
        jobs.scheduleDaily()

        return evaluateAndSyncNetwork(mayTriggerPrompt = true)
    }

    suspend fun disable(): DisableResult {
        // Persist OFF, flag pending revokes, clear hashes, and cancel jobs BEFORE calling the helper.
        store.setEnabled(false)
        store.setPendingRevokes(true)
        store.clearAllowedBssidHashes()
        jobs.cancelAll()

        runPendingRevokesIfNeeded(allowKill = false)
        return if (store.pendingRevokes.first()) DisableResult.Deferred else DisableResult.Disabled
    }

    /**
     * Completes deferred permission revokes whenever the feature is disabled.
     * Returns true once all four recovery permissions are confirmed denied.
     */
    suspend fun runPendingRevokesIfNeeded(allowKill: Boolean): Boolean {
        if (store.enabled.first()) return true
        if (!permissions.hasAny()) {
            store.setPendingRevokes(false)
            return true
        }
        if (!store.pendingRevokes.first()) {
            store.setPendingRevokes(true)
        }
        if (allowKill) {
            commands.revokeRebootRecovery()
        } else {
            if (permissions.hasSecureSettings()) {
                commands.revokeSecureSettings()
            }
            permissions.revokeLocationOnKill()
        }
        val cleared = !permissions.hasAny()
        if (cleared) store.setPendingRevokes(false)
        return cleared
    }

    /**
     * Refreshes allowed BSSID hashes from `adb-trusted-networks` while the feature is enabled,
     * so mesh nodes join automatically as they are marked "Always allow", and returns any active
     * setup notice for the Tools card.
     */
    suspend fun syncTrustedBssidsIfEnabled(): RecoveryNotice? {
        if (isFireOs || !store.enabled.first()) return null
        syncTrustedFromHelper()
        return when (val state = bssidReader.read()) {
            BssidState.LocationOff -> RecoveryNotice.NEEDS_LOCATION_SETTING
            BssidState.NotOnWifi -> null
            is BssidState.Found -> {
                val norm = TrustedBssids.normalizeBssid(state.bssid)
                val allowed = store.allowedBssidHashes.first()
                if (norm != null && sha256HexLower(norm) in allowed) {
                    null
                } else {
                    RecoveryNotice.NEEDS_ALWAYS_ALLOW_TOGGLE
                }
            }
            BssidState.NoPermission, BssidState.Unknown -> RecoveryNotice.NEEDS_ALWAYS_ALLOW_TOGGLE
        }
    }

    private suspend fun rollbackFailedGrant() {
        store.setEnabled(false)
        store.setPendingRevokes(true)
        store.clearAllowedBssidHashes()
        jobs.cancelAll()
        runPendingRevokesIfNeeded(allowKill = false)
    }

    private suspend fun evaluateAndSyncNetwork(mayTriggerPrompt: Boolean): EnableResult {
        syncTrustedFromHelper()
        return when (val state = bssidReader.read()) {
            BssidState.LocationOff -> EnableResult.NeedsLocationSetting
            is BssidState.Found -> {
                val norm = TrustedBssids.normalizeBssid(state.bssid)
                val allowed = store.allowedBssidHashes.first()
                if (norm != null && sha256HexLower(norm) in allowed) {
                    EnableResult.Enabled
                } else {
                    handleUntrustedNetwork(mayTriggerPrompt)
                }
            }
            BssidState.NotOnWifi -> EnableResult.NoWifiOrOldAndroid
            BssidState.NoPermission, BssidState.Unknown -> handleUntrustedNetwork(mayTriggerPrompt)
        }
    }

    private fun handleUntrustedNetwork(mayTriggerPrompt: Boolean): EnableResult {
        val wirelessOn = settings.getInt(SettingsReader.ADB_WIFI_ENABLED) == 1
        return if (!wirelessOn && mayTriggerPrompt) {
            secureWriter.enableAdbWifi()
            EnableResult.NeedsAlwaysAllowPrompt
        } else {
            EnableResult.NeedsAlwaysAllowToggle
        }
    }

    private suspend fun syncTrustedFromHelper() {
        val res = commands.adbTrustedNetworks()
        if (res is ToolResult.Ok) {
            val hashes = res.value.mapNotNull { mac ->
                TrustedBssids.normalizeBssid(mac)?.let { sha256HexLower(it) }
            }.toSet()
            if (hashes.isNotEmpty()) {
                store.addAllowedBssidHashes(hashes)
            }
        }
    }
}
