package io.github.devjardins.sidespeed.maintenance

import io.github.devjardins.sidespeed.adb.AdbBackend
import io.github.devjardins.sidespeed.adb.AdbTarget
import io.github.devjardins.sidespeed.adb.Readiness
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.helper.TrustedBssids
import io.github.devjardins.sidespeed.ui.setup.SettingsReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Pure boot-time helper recovery and daily wireless-key keepalive logic for Google TV / Android TV 11+.
 * Fails closed before ever touching `adb_wifi_enabled`: never writes unless the feature is enabled,
 * location is on, the BSSID is valid (non-sentinel), and its SHA-256 hash is in the allowed set.
 */
class RebootRecovery(
    private val store: RebootRecoveryStore,
    private val helperUp: suspend () -> Boolean,
    private val bssidReader: BssidReader,
    private val settings: SettingsReader,
    private val secureWriter: SecureSettingsWriter,
    private val ensureReady: suspend () -> Readiness,
    private val onHelperRecovered: () -> Unit,
    private val isFireOs: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun runBootRecovery(): RecoveryOutcome? {
        if (isFireOs || !store.enabled.first()) return null

        if (helperUp()) {
            return recordSuccess()
        }

        val bssid = when (val state = bssidReader.read()) {
            BssidState.LocationOff -> return recordFailure(RecoveryReason.LOCATION_OFF)
            BssidState.NoPermission -> return recordFailure(RecoveryReason.NO_PERMISSION)
            BssidState.NotOnWifi -> return recordFailure(RecoveryReason.NOT_ON_WIFI)
            BssidState.Unknown -> return recordFailure(RecoveryReason.NEW_NETWORK)
            is BssidState.Found -> TrustedBssids.normalizeBssid(state.bssid)
                ?: return recordFailure(RecoveryReason.NEW_NETWORK)
        }

        val allowed = store.allowedBssidHashes.first()
        if (sha256HexLower(bssid) !in allowed) {
            return recordFailure(RecoveryReason.NEW_NETWORK)
        }

        if (settings.getInt(SettingsReader.ADB_WIFI_ENABLED) != 1) {
            if (!secureWriter.enableAdbWifi()) {
                return recordFailure(RecoveryReason.WRITE_REFUSED)
            }
            delayFn(WRITE_VERIFY_DELAY_MS)
            if (settings.getInt(SettingsReader.ADB_WIFI_ENABLED) != 1) {
                return recordFailure(RecoveryReason.PROMPTED)
            }
        }

        return when (val firstAttempt = ensureReady()) {
            Readiness.HelperReady -> recordSuccess()
            Readiness.WaitingForAllowPrompt -> recordFailure(RecoveryReason.PROMPTED)
            is Readiness.NeedsHelperStart -> {
                if (settings.getInt(SettingsReader.ADB_WIFI_ENABLED) != 1) {
                    recordFailure(RecoveryReason.PROMPTED)
                } else if (!firstAttempt.mdnsMiss) {
                    recordFailure(RecoveryReason.ADB_FAILED)
                } else {
                    delayFn(MDNS_RETRY_DELAY_MS)
                    if (!store.enabled.first()) return null
                    if (settings.getInt(SettingsReader.ADB_WIFI_ENABLED) != 1) {
                        return recordFailure(RecoveryReason.PROMPTED)
                    }
                    when (ensureReady()) {
                        Readiness.HelperReady -> recordSuccess()
                        Readiness.WaitingForAllowPrompt -> recordFailure(RecoveryReason.PROMPTED)
                        else -> if (settings.getInt(SettingsReader.ADB_WIFI_ENABLED) != 1) {
                            recordFailure(RecoveryReason.PROMPTED)
                        } else {
                            recordFailure(RecoveryReason.ADB_FAILED)
                        }
                    }
                }
            }
            Readiness.HelperNeedsRestart -> recordFailure(RecoveryReason.ADB_FAILED)
        }
    }

    /**
     * Opens and closes a wireless TLS ADB session once per day so Android's 7-day
     * `adb_allowed_connection_time` expiry never drops SideSpeed's paired key.
     * Never writes `adb_wifi_enabled`.
     */
    suspend fun runDailyKeyRefresh(adb: AdbBackend, adbMutex: Mutex, prefs: Prefs) {
        if (isFireOs || !store.enabled.first() || !prefs.pairedTls.first()) return
        val ok = adbMutex.withLock {
            try {
                withTimeout(DAILY_CONNECT_TIMEOUT_MS) {
                    adb.connect(AdbTarget.WirelessTls).isSuccess
                }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                adb.close()
            }
        }
        store.setDailyRefreshFailed(!ok)
    }

    private suspend fun recordSuccess(): RecoveryOutcome.StartedAgain {
        val outcome = RecoveryOutcome.StartedAgain(nowMs())
        store.setLastRecovery(outcome)
        store.setDailyRefreshFailed(false)
        onHelperRecovered()
        return outcome
    }

    private suspend fun recordFailure(reason: RecoveryReason): RecoveryOutcome.Failed {
        val outcome = RecoveryOutcome.Failed(reason, nowMs())
        store.setLastRecovery(outcome)
        return outcome
    }

    companion object {
        const val WRITE_VERIFY_DELAY_MS = 10_000L
        const val MDNS_RETRY_DELAY_MS = 60_000L
        const val DAILY_CONNECT_TIMEOUT_MS = 15_000L
    }
}
