package io.github.devjardins.sidespeed.status

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.CompileOutcome
import io.github.devjardins.sidespeed.data.LastCompile
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.maintenance.FakeMaintenanceStore
import io.github.devjardins.sidespeed.maintenance.MaintenanceEngine
import io.github.devjardins.sidespeed.testing.EnglishStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class ProviderFakeSource : AppSource {
    val apps = linkedMapOf<String, AppEntry>()
    fun put(pkg: String, updated: Long = 100L, installer: String? = null) {
        apps[pkg] = AppEntry(pkg, pkg.uppercase(), installer, updated)
    }
    override fun list(): List<AppEntry> = apps.values.toList()
    override fun find(pkg: String): AppEntry? = apps[pkg]
}

private class ProviderFakePrefs : Prefs {
    val saved = mutableMapOf<String, LastCompile>()
    override val lanWarningAcked: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setLanWarningAcked(v: Boolean) {}
    override val pairedTls: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setPairedTls(v: Boolean) {}
    override val onBoxAdbRefusedBuild: Flow<String?> = MutableStateFlow(null)
    override suspend fun setOnBoxAdbRefusedBuild(build: String?) {}
    override fun lastCompile(pkg: String): Flow<LastCompile?> = flow { emit(saved[pkg]) }
    override suspend fun setLastCompile(pkg: String, value: LastCompile) { saved[pkg] = value }
    override fun lastCompiles(): Flow<Map<String, LastCompile>> = flow { emit(saved.toMap()) }
    override val selfCompile: Flow<SelfCompile?> = MutableStateFlow(null)
    override suspend fun setSelfCompile(v: SelfCompile) {}
}

private class ProviderCallHarness(
    val ownPackage: String = "io.github.devjardins.sidespeed",
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val source = ProviderFakeSource()
    val store = FakeMaintenanceStore()
    val prefs = ProviderFakePrefs()
    val statusStore = FakeCompileStatusStore()
    val status = CompileStatus(
        store = statusStore,
        version = "0.8.0",
        debuggable = false,
        clock = { 1_000L },
        scope = scope,
    )
    var measuring: String? = null
    val compileGate = CompletableDeferred<Unit>()
    var workerStarts = 0

    val engine = MaintenanceEngine(
        source = source,
        store = store,
        prefs = prefs,
        runCompile = { _, _ ->
            flow {
                compileGate.await()
                emit(CompileEvent.Finished(true, false, CompileOutcome.Success))
            }
        },
        helperUp = { true },
        scope = scope,
        ownPackage = ownPackage,
        strings = EnglishStrings,
        measuring = { measuring },
        status = status,
        clock = { 1_000L },
    )

    val target = object : CompileStatusProvider.CallTarget {
        override val ownPackage: String get() = this@ProviderCallHarness.ownPackage
        override fun isInstalled(pkg: String): Boolean = source.find(pkg) != null
        override fun currentReqSeq(): Long = status.requestSeq()
        override suspend fun requestCompile(pkg: String, keepOptimized: Boolean): Boolean =
            engine.requestCompile(pkg, keepOptimized)
        override suspend fun requestOptimizeNow(): Int = engine.requestOptimizeNow()
        override fun startWorker() {
            workerStarts++
        }
    }

    fun close() {
        compileGate.complete(Unit)
        scope.cancel()
    }
}

class CompileStatusProviderTest {
    @Test fun serves_idle_row_before_first_refresh() {
        val rows = CompileStatusProvider().currentRows().toMap()
        assertEquals("1", rows["v"])
        assertEquals("idle", rows["state"])
        assertEquals("", rows["pkg"])
        assertEquals("0", rows["queue"])
        assertEquals("0", rows["req_seq"])
        assertEquals("1", rows["remote"])
        assertEquals("0", rows["retrying"])
    }

    @Test fun refresh_updates_the_served_snapshot() = runTest {
        val provider = CompileStatusProvider()
        provider.refreshSource = {
            StatusRow(
                phase = CompilePhase.COMPILING,
                activePkg = "com.example.app",
                activeLabel = "Example",
                activeMode = "speed",
                activeStartedMs = 1L,
                queueWaiting = 0,
                last = null,
                self = SelfState.PENDING,
                version = "0.5.1",
            ).toWireMap()
        }
        provider.refreshOnce()
        val rows = provider.currentRows().toMap()
        assertEquals("compiling", rows["state"])
        assertEquals("com.example.app", rows["pkg"])
    }

    @Test fun refreshOnce_never_overwrites_a_newer_req_seq_snapshot() = runTest {
        val provider = CompileStatusProvider()
        var rows = StatusRow(
            phase = CompilePhase.IDLE,
            activePkg = "",
            activeLabel = "",
            activeMode = "",
            activeStartedMs = 0L,
            queueWaiting = 0,
            last = null,
            self = SelfState.PENDING,
            version = "0.5.1",
            reqSeq = 2L,
        ).toWireMap()
        provider.refreshSource = { rows }
        provider.refreshOnce()
        assertEquals("2", provider.currentRows().toMap()["req_seq"])

        // A collector tick that read before the call's req_seq bump must not win.
        rows = StatusRow(
            phase = CompilePhase.IDLE,
            activePkg = "",
            activeLabel = "",
            activeMode = "",
            activeStartedMs = 0L,
            queueWaiting = 0,
            last = null,
            self = SelfState.PENDING,
            version = "0.5.1",
            reqSeq = 1L,
        ).toWireMap()
        provider.refreshOnce()
        assertEquals("2", provider.currentRows().toMap()["req_seq"])
    }

    @Test fun serving_never_waits_for_the_source() = runTest {
        val provider = CompileStatusProvider()
        provider.refreshSource = { awaitCancellation() }
        // The serve path must not touch the (hung) collector source at all.
        val rows = withTimeout(5_000) { provider.currentRows() }
        assertEquals("idle", rows.toMap()["state"])
    }

    @Test fun call_rejects_non_shell_uid_before_dispatch() {
        for (uid in listOf(10042, 0)) {
            var handlerCalls = 0
            var refreshCalls = 0
            val provider = CompileStatusProvider().apply {
                callingUid = { uid }
                callHandler = { _, _, _ ->
                    handlerCalls++
                    RemoteCallResult(ok = true, queued = 1, reqSeq = 1L)
                }
                refreshSource = {
                    refreshCalls++
                    null
                }
            }

            val ex1 = assertThrows(SecurityException::class.java) {
                provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.app", true)
            }
            assertEquals("Shell only", ex1.message)

            val ex2 = assertThrows(SecurityException::class.java) {
                provider.dispatchCall("anything", null, false)
            }
            assertEquals("Shell only", ex2.message)

            val ex3 = assertThrows(SecurityException::class.java) {
                provider.call(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.app", null)
            }
            assertEquals("Shell only", ex3.message)
            assertEquals(0, handlerCalls)
            assertEquals(0, refreshCalls)
        }
    }

    @Test fun call_compile_package_validates_package_and_refreshes_cache() {
        val h = ProviderCallHarness()
        try {
            h.source.put("com.example.app")
            h.source.put("com.example.other")
            val provider = CompileStatusProvider().apply {
                callingUid = { CompileStatusProvider.SHELL_UID }
                callTarget = { h.target }
                refreshSource = { h.status.snapshot().toWireMap() }
            }

            val invalid = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "../bad", true)
            assertFalse(invalid.ok)
            assertEquals("invalid_package", invalid.error)
            assertEquals(0, h.workerStarts)
            assertEquals("0", provider.currentRows().toMap()["req_seq"])

            val missing = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.missing", true)
            assertFalse(missing.ok)
            assertEquals("not_installed", missing.error)
            assertEquals(0, h.workerStarts)
            assertEquals("0", provider.currentRows().toMap()["req_seq"])

            val own = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, h.ownPackage, true)
            assertTrue(own.ok)
            assertEquals(0, own.queued)
            assertEquals(0L, own.reqSeq)
            assertEquals(0, h.workerStarts)

            val oneShot = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.other", false)
            assertEquals(RemoteCallResult(ok = true, queued = 1, reqSeq = 1L, error = null), oneShot)
            assertEquals(1, h.workerStarts)
            assertFalse("com.example.other" in h.store.maintainedFlow.value)
            assertEquals("1", provider.currentRows().toMap()["req_seq"])

            val valid = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.app", true)
            assertEquals(RemoteCallResult(ok = true, queued = 1, reqSeq = 2L, error = null), valid)
            assertEquals(2, h.workerStarts)
            assertTrue("com.example.app" in h.store.maintainedFlow.value)
            // Cached snapshot was synchronously refreshed before dispatchCall returned.
            assertEquals("2", provider.currentRows().toMap()["req_seq"])
        } finally {
            h.close()
        }
    }

    @Test fun call_optimize_now_returns_queued_count_and_refreshes_cache() {
        val h = ProviderCallHarness()
        try {
            h.source.put("com.example.one")
            h.source.put("com.example.two")
            h.source.put("com.example.three")
            // Simulate a prior call so optimize_now bumps reqSeq from 1 -> 2.
            assertEquals(1L, h.status.bumpRequestSeq())

            val provider = CompileStatusProvider().apply {
                callingUid = { CompileStatusProvider.SHELL_UID }
                callTarget = { h.target }
                refreshSource = { h.status.snapshot().toWireMap() }
            }

            val result = provider.dispatchCall(CompileStatusProvider.METHOD_OPTIMIZE_NOW, null, false)
            assertEquals(RemoteCallResult(ok = true, queued = 3, reqSeq = 2L, error = null), result)
            assertEquals(1, h.workerStarts)
            assertEquals("2", provider.currentRows().toMap()["req_seq"])
        } finally {
            h.close()
        }
    }

    @Test fun call_optimize_now_does_not_wake_worker_when_nothing_queued() {
        val h = ProviderCallHarness()
        try {
            val provider = CompileStatusProvider().apply {
                callingUid = { CompileStatusProvider.SHELL_UID }
                callTarget = { h.target }
                refreshSource = { h.status.snapshot().toWireMap() }
            }

            val result = provider.dispatchCall(CompileStatusProvider.METHOD_OPTIMIZE_NOW, null, false)
            assertEquals(RemoteCallResult(ok = true, queued = 0, reqSeq = 1L, error = null), result)
            assertEquals(0, h.workerStarts)
            assertEquals("1", provider.currentRows().toMap()["req_seq"])
        } finally {
            h.close()
        }
    }

    @Test fun call_unknown_method_returns_error_without_touching_state() {
        var targetLookups = 0
        var refreshCalls = 0
        val provider = CompileStatusProvider().apply {
            callingUid = { CompileStatusProvider.SHELL_UID }
            callTarget = {
                targetLookups++
                null
            }
            refreshSource = {
                refreshCalls++
                null
            }
        }

        val before = provider.currentRows()
        val result = provider.dispatchCall("bogus", null, false)
        assertEquals(RemoteCallResult(ok = false, queued = 0, reqSeq = 0L, error = "unknown_method"), result)
        assertNull(result.error?.takeIf { it != "unknown_method" })
        assertEquals(0, targetLookups)
        assertEquals(0, refreshCalls)
        assertEquals(before, provider.currentRows())
    }

    @Test fun call_compile_package_returns_queued_zero_when_measuring_without_waking() {
        val h = ProviderCallHarness()
        try {
            h.source.put("com.example.app")
            h.measuring = "com.example.app"
            val provider = CompileStatusProvider().apply {
                callingUid = { CompileStatusProvider.SHELL_UID }
                callTarget = { h.target }
                refreshSource = { h.status.snapshot().toWireMap() }
            }

            val result = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.app", false)
            assertEquals(RemoteCallResult(ok = true, queued = 0, reqSeq = 0L, error = null), result)
            assertEquals(0, h.workerStarts)
        } finally {
            h.close()
        }
    }

    @Test fun call_timeout_returns_error_without_refreshing_cache() {
        var refreshCalls = 0
        val provider = CompileStatusProvider().apply {
            callingUid = { CompileStatusProvider.SHELL_UID }
            callTimeoutMs = 25L
            callHandler = { _, _, _ ->
                delay(500L)
                RemoteCallResult(ok = true, queued = 1, reqSeq = 1L)
            }
            refreshSource = {
                refreshCalls++
                null
            }
        }

        val result = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.app", false)
        assertEquals(RemoteCallResult(ok = false, queued = 0, reqSeq = 0L, error = "timeout"), result)
        assertEquals(0, refreshCalls)
    }

    @Test fun call_handler_seam_runs_on_shell_uid_and_refreshes_cache() {
        val provider = CompileStatusProvider().apply {
            callingUid = { CompileStatusProvider.SHELL_UID }
            callHandler = { method, arg, keep ->
                assertEquals(CompileStatusProvider.METHOD_COMPILE_PACKAGE, method)
                assertEquals("com.example.app", arg)
                assertTrue(keep)
                RemoteCallResult(ok = true, queued = 1, reqSeq = 7L)
            }
            refreshSource = {
                StatusRow(
                    phase = CompilePhase.IDLE,
                    activePkg = "",
                    activeLabel = "",
                    activeMode = "",
                    activeStartedMs = 0L,
                    queueWaiting = 1,
                    last = null,
                    self = SelfState.PENDING,
                    version = "0.8.0",
                    reqSeq = 7L,
                ).toWireMap()
            }
        }

        val result = provider.dispatchCall(CompileStatusProvider.METHOD_COMPILE_PACKAGE, "com.example.app", true)
        assertEquals(RemoteCallResult(ok = true, queued = 1, reqSeq = 7L), result)
        assertEquals("7", provider.currentRows().toMap()["req_seq"])
    }
}
