package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.maintenance.BssidReader
import io.github.devjardins.sidespeed.maintenance.BssidState
import io.github.devjardins.sidespeed.maintenance.RebootRecoveryStore
import io.github.devjardins.sidespeed.maintenance.RecoveryOutcome
import io.github.devjardins.sidespeed.maintenance.SecureSettingsWriter
import io.github.devjardins.sidespeed.maintenance.sha256HexLower
import io.github.devjardins.sidespeed.ui.setup.SettingsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebootRecoveryControllerTest {
    private class FakeStore(
        initialEnabled: Boolean = false,
        initialHashes: Set<String> = emptySet(),
        initialPendingRevokes: Boolean = false,
    ) : RebootRecoveryStore {
        val enabledFlow = MutableStateFlow(initialEnabled)
        val hashesFlow = MutableStateFlow(initialHashes)
        val pendingFlow = MutableStateFlow(initialPendingRevokes)
        val lastFlow = MutableStateFlow<RecoveryOutcome?>(null)
        val dailyFailedFlow = MutableStateFlow(false)

        override val enabled: Flow<Boolean> = enabledFlow
        override suspend fun setEnabled(v: Boolean) { enabledFlow.value = v }
        override val allowedBssidHashes: Flow<Set<String>> = hashesFlow
        override suspend fun addAllowedBssidHashes(hashes: Set<String>) { hashesFlow.value += hashes }
        override suspend fun clearAllowedBssidHashes() { hashesFlow.value = emptySet() }
        override val pendingRevokes: Flow<Boolean> = pendingFlow
        override suspend fun setPendingRevokes(v: Boolean) { pendingFlow.value = v }
        override val lastRecovery: Flow<RecoveryOutcome?> = lastFlow
        override suspend fun setLastRecovery(v: RecoveryOutcome?) { lastFlow.value = v }
        override val dailyRefreshFailed: Flow<Boolean> = dailyFailedFlow
        override suspend fun setDailyRefreshFailed(v: Boolean) { dailyFailedFlow.value = v }
    }

    private class FakePrefs(tls: Boolean = true) : Prefs {
        val tlsState = MutableStateFlow(tls)
        override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setLanWarningAcked(v: Boolean) {}
        override val pairedTls: Flow<Boolean> = tlsState
        override suspend fun setPairedTls(v: Boolean) { tlsState.value = v }
        override fun lastCompile(pkg: String): Flow<LastCompile?> = MutableStateFlow(null)
        override suspend fun setLastCompile(pkg: String, value: LastCompile) {}
        override fun lastCompiles(): Flow<Map<String, LastCompile>> = MutableStateFlow(emptyMap())
        override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
        override suspend fun setSelfCompile(v: SelfCompile) {}
    }

    private class FakePermissions(
        var secureSettings: Boolean = false,
        var location: Boolean = false,
    ) : RecoveryPermissions {
        var revokeOnKillCalls = 0
        override fun hasAll(): Boolean = secureSettings && location
        override fun hasAny(): Boolean = secureSettings || location
        override fun hasSecureSettings(): Boolean = secureSettings
        override fun revokeLocationOnKill() { revokeOnKillCalls++ }
    }

    private class FakeJobs : RecoveryJobScheduler {
        var scheduleDailyCalls = 0
        var cancelAllCalls = 0
        override fun scheduleDaily() { scheduleDailyCalls++ }
        override fun cancelAll() { cancelAllCalls++ }
    }

    private class FakeCommands(
        private val perms: FakePermissions,
        var grantResult: ToolResult<Unit> = ToolResult.Ok(Unit),
        var revokeSecureResult: ToolResult<Unit> = ToolResult.Ok(Unit),
        var revokeAllResult: ToolResult<Unit> = ToolResult.Ok(Unit),
        var trustedResult: ToolResult<List<String>> = ToolResult.Ok(listOf(BSSID_1)),
        var onRevokeCall: (() -> Unit)? = null,
    ) : RebootRecoveryCommands {
        val calls = mutableListOf<String>()

        override suspend fun grantRebootRecovery(): ToolResult<Unit> {
            calls += "grant"
            if (grantResult is ToolResult.Ok) {
                perms.secureSettings = true
                perms.location = true
            }
            return grantResult
        }

        override suspend fun revokeSecureSettings(): ToolResult<Unit> {
            calls += "revoke-secure"
            onRevokeCall?.invoke()
            if (revokeSecureResult is ToolResult.Ok) {
                perms.secureSettings = false
            }
            return revokeSecureResult
        }

        override suspend fun revokeRebootRecovery(): ToolResult<Unit> {
            calls += "revoke-all"
            onRevokeCall?.invoke()
            if (revokeAllResult is ToolResult.Ok) {
                perms.secureSettings = false
                perms.location = false
            }
            return revokeAllResult
        }

        override suspend fun adbTrustedNetworks(): ToolResult<List<String>> {
            calls += "trusted"
            return trustedResult
        }
    }

    @Test fun enableRefusesWithoutPairing() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms)
        val store = FakeStore()
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = false),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(EnableResult.NeedsPairing, ctrl.enable())
        assertTrue(commands.calls.isEmpty())
        assertFalse(store.enabled.first())
    }

    @Test fun enableRefusesOnEthernetOrAndroid10() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms)
        val ethCtrl = RebootRecoveryController(
            commands = commands,
            store = FakeStore(),
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.NotOnWifi },
            settings = SettingsReader { 0 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 34,
            isWifiConnected = { false },
        )
        assertFalse(ethCtrl.canEnable())
        assertEquals(EnableResult.NoWifiOrOldAndroid, ethCtrl.enable())

        val api29Ctrl = RebootRecoveryController(
            commands = commands,
            store = FakeStore(),
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 0 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 29,
            isWifiConnected = { true },
        )
        assertFalse(api29Ctrl.canEnable())
        assertEquals(EnableResult.NoWifiOrOldAndroid, api29Ctrl.enable())
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun disableWorksWhileOnEthernetWithSwitchOn() = runTest {
        val perms = FakePermissions(secureSettings = true, location = true)
        val commands = FakeCommands(perms)
        val store = FakeStore(initialEnabled = true, initialHashes = setOf(sha256HexLower(BSSID_1)))
        val jobs = FakeJobs()
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.NotOnWifi },
            settings = SettingsReader { 0 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = jobs,
            isFireOs = false,
            sdkInt = 34,
            isWifiConnected = { false },
        )
        val res = ctrl.disable()
        assertEquals(DisableResult.Deferred, res)
        assertFalse(store.enabled.first())
        assertEquals(emptySet<String>(), store.allowedBssidHashes.first())
        assertEquals(1, jobs.cancelAllCalls)
        assertFalse(perms.secureSettings)
        assertEquals(1, perms.revokeOnKillCalls)
        assertTrue(store.pendingRevokes.first())
    }

    @Test fun grantFailureRollsBackAndLeavesSwitchOff() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms, grantResult = ToolResult.Failed("denied"))
        val store = FakeStore()
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(EnableResult.GrantFailed, ctrl.enable())
        assertFalse(store.enabled.first())
        assertFalse(store.pendingRevokes.first())
    }

    @Test fun grantsSucceedPersistsEnabledEvenWhenAlwaysAllowPending() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms, trustedResult = ToolResult.Ok(emptyList()))
        val store = FakeStore()
        val jobs = FakeJobs()
        var writes = 0
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 0 },
            secureWriter = SecureSettingsWriter { writes++; true },
            permissions = perms,
            jobs = jobs,
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(EnableResult.NeedsAlwaysAllowPrompt, ctrl.enable())
        assertTrue(store.enabled.first())
        assertFalse(store.pendingRevokes.first())
        assertEquals(1, jobs.scheduleDailyCalls)
        assertEquals(1, writes)
    }

    @Test fun notTrustedWithWirelessOnNeverTogglesOffAndReturnsToggleNotice() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms, trustedResult = ToolResult.Ok(emptyList()))
        val store = FakeStore()
        var writes = 0
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { writes++; true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(EnableResult.NeedsAlwaysAllowToggle, ctrl.enable())
        assertTrue(store.enabled.first())
        assertEquals(0, writes)
    }

    @Test fun notTrustedWithWirelessOffWritesOnceAndReturnsPromptNotice() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms, trustedResult = ToolResult.Ok(emptyList()))
        val store = FakeStore()
        var writes = 0
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 0 },
            secureWriter = SecureSettingsWriter { writes++; true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(EnableResult.NeedsAlwaysAllowPrompt, ctrl.enable())
        assertEquals(1, writes)
    }

    @Test fun locationOffStaysEnabledAndReturnsLocationNotice() = runTest {
        val perms = FakePermissions()
        val commands = FakeCommands(perms)
        val store = FakeStore()
        var writes = 0
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.LocationOff },
            settings = SettingsReader { 0 },
            secureWriter = SecureSettingsWriter { writes++; true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(EnableResult.NeedsLocationSetting, ctrl.enable())
        assertTrue(store.enabled.first())
        assertEquals(0, writes)
    }

    @Test fun disablePersistsStateAndCancelsJobsBeforeHelperCall() = runTest {
        val perms = FakePermissions(secureSettings = true, location = true)
        val store = FakeStore(initialEnabled = true, initialHashes = setOf(sha256HexLower(BSSID_1)))
        val jobs = FakeJobs()
        var enabledAtHelperCall: Boolean? = null
        var pendingAtHelperCall: Boolean? = null
        var hashesAtHelperCall: Set<String>? = null
        var cancelsAtHelperCall: Int? = null
        val commands = FakeCommands(
            perms,
            onRevokeCall = {
                enabledAtHelperCall = store.enabledFlow.value
                pendingAtHelperCall = store.pendingFlow.value
                hashesAtHelperCall = store.hashesFlow.value
                cancelsAtHelperCall = jobs.cancelAllCalls
            },
        )
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = jobs,
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        ctrl.disable()
        assertEquals(false, enabledAtHelperCall)
        assertEquals(true, pendingAtHelperCall)
        assertEquals(emptySet<String>(), hashesAtHelperCall)
        assertEquals(1, cancelsAtHelperCall)
    }

    @Test fun disableWithHelperDownDefersRevokes() = runTest {
        val perms = FakePermissions(secureSettings = true, location = true)
        val commands = FakeCommands(
            perms,
            revokeSecureResult = ToolResult.HelperDown,
            revokeAllResult = ToolResult.HelperDown,
        )
        val store = FakeStore(initialEnabled = true, initialHashes = setOf(sha256HexLower(BSSID_1)))
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertEquals(DisableResult.Deferred, ctrl.disable())
        assertFalse(store.enabled.first())
        assertTrue(store.pendingRevokes.first())
        assertTrue(perms.hasAny())
    }

    @Test fun runPendingRevokesRunsOnHelperUpAndClearsOnlyWhenDenied() = runTest {
        val perms = FakePermissions(secureSettings = true, location = true)
        val commands = FakeCommands(perms)
        val store = FakeStore(initialEnabled = false, initialPendingRevokes = true)
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertTrue(ctrl.runPendingRevokesIfNeeded(allowKill = true))
        assertEquals(listOf("revoke-all"), commands.calls)
        assertFalse(perms.hasAny())
        assertFalse(store.pendingRevokes.first())
    }

    @Test fun runPendingRevokesRetainsFlagWhenHelperDownOrStillGranted() = runTest {
        val perms = FakePermissions(secureSettings = true, location = true)
        val commands = FakeCommands(perms, revokeAllResult = ToolResult.HelperDown)
        val store = FakeStore(initialEnabled = false, initialPendingRevokes = true)
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { BssidState.Found(BSSID_1) },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )
        assertFalse(ctrl.runPendingRevokesIfNeeded(allowKill = true))
        assertTrue(store.pendingRevokes.first())
    }

    @Test fun syncTrustedBssidsAddsNewMeshNodesAndClearsNoticeOnReturn() = runTest {
        val perms = FakePermissions(secureSettings = true, location = true)
        val commands = FakeCommands(perms, trustedResult = ToolResult.Ok(listOf(BSSID_1)))
        val store = FakeStore(initialEnabled = true, initialHashes = setOf(sha256HexLower(BSSID_1)))
        var currentBssid: BssidState = BssidState.Found(BSSID_2)
        val ctrl = RebootRecoveryController(
            commands = commands,
            store = store,
            prefs = FakePrefs(tls = true),
            bssidReader = BssidReader { currentBssid },
            settings = SettingsReader { 1 },
            secureWriter = SecureSettingsWriter { true },
            permissions = perms,
            jobs = FakeJobs(),
            isFireOs = false,
            sdkInt = 35,
            isWifiConnected = { true },
        )

        // New mesh node not yet in dumpsys adb -> notice asks user to toggle Always allow
        assertEquals(RecoveryNotice.NEEDS_ALWAYS_ALLOW_TOGGLE, ctrl.syncTrustedBssidsIfEnabled())

        // User ticks Always allow on the new mesh node and returns -> sync adds hash & clears notice
        commands.trustedResult = ToolResult.Ok(listOf(BSSID_1, BSSID_2))
        assertNull(ctrl.syncTrustedBssidsIfEnabled())
        assertTrue(sha256HexLower(BSSID_2) in store.allowedBssidHashes.first())
    }

    private companion object {
        const val BSSID_1 = "02:15:b2:00:00:01"
        const val BSSID_2 = "aa:bb:cc:dd:ee:02"
    }
}
