package io.github.devjardins.sidespeed.status

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class CompileStatusStoreTest {
    private var now = 1000L

    private fun glue(
        store: CompileStatusStore,
        selfOptimized: Boolean = false,
        debuggable: Boolean = false,
        scope: CoroutineScope,
    ) = CompileStatus(
        store = store,
        version = "0.2.1",
        debuggable = debuggable,
        loadSelfOptimized = { selfOptimized },
        clock = { now },
        scope = scope,
    )

    @Test fun mutationsPersistThroughTheStore() = runTest {
        val store = FakeCompileStatusStore()
        val s = glue(store, scope = backgroundScope)
        runCurrent()

        s.onStart("a.pkg", "A", "speed")
        runCurrent()
        assertEquals("a.pkg", store.saved?.current?.pkg)
        assertEquals("A", store.saved?.current?.label)
        assertEquals(1000L, store.saved?.current?.startedMs)

        now = 2000L
        s.onFinish("a.pkg", "A", true)
        runCurrent()
        assertNull(store.saved?.current)
        assertEquals(CompileLast("a.pkg", "A", true, 2000L), store.saved?.last)
        assertEquals(CompilePhase.IDLE, s.snapshot().phase)
    }

    @Test fun freshInstanceRestoresInProgressAsInterrupted() = runTest {
        val store = FakeCompileStatusStore()
        val s = glue(store, scope = backgroundScope)
        runCurrent()
        s.onStart("a.pkg", "A", "speed")
        runCurrent()

        val rebooted = glue(store, scope = backgroundScope)
        runCurrent()
        val row = rebooted.snapshot()
        assertEquals(CompilePhase.INTERRUPTED, row.phase)
        assertEquals("a.pkg", row.activePkg)
    }

    @Test fun selfOptimizedLoadsOnInit() = runTest {
        val plain = glue(FakeCompileStatusStore(), scope = backgroundScope)
        runCurrent()
        assertEquals(SelfState.PENDING, plain.snapshot().self)

        val optimized = glue(FakeCompileStatusStore(), selfOptimized = true, scope = backgroundScope)
        runCurrent()
        assertEquals(SelfState.OPTIMIZED, optimized.snapshot().self)

        val debug = glue(FakeCompileStatusStore(), selfOptimized = true, debuggable = true, scope = backgroundScope)
        runCurrent()
        assertEquals(SelfState.DEBUG, debug.snapshot().self)
    }

    @Test fun dataStoreRoundTrip() = runTest {
        val dir = Files.createTempDirectory("compile-status-test")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { dir.resolve("status.preferences_pb").toFile() },
        )
        val store = DataStoreCompileStatusStore(dataStore)
        try {
            assertNull(store.load())
            val value = PersistedCompileStatus(
                CompileCurrent("a.pkg", "A, B = C", "speed-profile", 1234L),
                CompileLast("b.pkg", "B", false, 5678L),
            )
            store.save(value)
            assertEquals(value, store.load())
            store.save(null)
            assertNull(store.load())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
