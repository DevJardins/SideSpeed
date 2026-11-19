package io.github.devjardins.sidespeed.ui

import io.github.devjardins.sidespeed.apps.AppEntry
import io.github.devjardins.sidespeed.apps.AppSource
import io.github.devjardins.sidespeed.data.SelfCompile
import io.github.devjardins.sidespeed.ui.apps.AppsViewModel
import io.github.devjardins.sidespeed.ui.apps.headerCaption
import io.github.devjardins.sidespeed.ui.apps.selfOptimizedPill
import io.github.devjardins.sidespeed.status.StatusBadge
import io.github.devjardins.sidespeed.status.BadgeSource
import io.github.devjardins.sidespeed.ui.apps.Pill
import io.github.devjardins.sidespeed.ui.apps.PillTone
import io.github.devjardins.sidespeed.ui.apps.pillFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class AppsViewModelTest {
    private val nuvio = AppEntry("com.nuvio.tv", "Nuvio", null, 1L)
    private val store = AppEntry("com.store.app", "Store", "com.android.vending", 2L)

    private class Source(val apps: List<AppEntry>) : AppSource {
        var listThread: String? = null
        override fun list(): List<AppEntry> { listThread = Thread.currentThread().name; return apps }
        override fun find(pkg: String): AppEntry? = apps.firstOrNull { it.packageName == pkg }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Badges(
        val result: () -> Map<String, StatusBadge>,
        val gate: CompletableDeferred<Unit>? = null,
    ) : BadgeSource {
        val calls = mutableListOf<Collection<String>>()
        override suspend fun badges(pkgs: Collection<String>): Map<String, StatusBadge> {
            calls += pkgs.toList()
            gate?.await()
            return result()
        }
    }

    private fun vm(src: AppSource, badges: BadgeSource = Badges({ emptyMap() })) =
        AppsViewModel(src, badges, Dispatchers.Unconfined)

    @Test fun oneStatusCallFillsAllPills() {
        val gate = CompletableDeferred<Unit>()
        val badges = Badges(
            { mapOf("com.nuvio.tv" to StatusBadge.SPEED_OK, "com.store.app" to StatusBadge.NEEDS_RECOMPILE) },
            gate,
        )
        val v = vm(Source(listOf(nuvio, store)), badges)
        v.load()
        // "Checking…" until the single call returns.
        assertTrue(v.state.value.statusLoading)
        assertEquals(Pill("Checking…", PillTone.NEUTRAL), v.pill("com.nuvio.tv"))
        assertEquals(Pill("Checking…", PillTone.NEUTRAL), v.pill("com.store.app"))
        gate.complete(Unit)
        assertEquals(1, badges.calls.size)
        assertEquals(setOf("com.nuvio.tv", "com.store.app"), badges.calls.single().toSet())
        assertEquals(Pill("speed ✓", PillTone.OK), v.pill("com.nuvio.tv"))
        assertEquals(Pill("Not compiled", PillTone.WARN), v.pill("com.store.app"))
    }

    @Test fun statusFailureMeansNoPills() {
        val v = vm(Source(listOf(nuvio, store)), Badges({ throw java.io.IOException("helper gone") }))
        v.load()
        assertFalse(v.state.value.statusLoading)
        assertNull(v.pill("com.nuvio.tv"))
        assertNull(v.pill("com.store.app"))
        assertFalse(v.state.value.loading)
    }

    @Test fun pillLabels() {
        assertEquals(Pill("speed ✓", PillTone.OK), pillFor(false, StatusBadge.SPEED_OK))
        assertEquals(Pill("speed-profile", PillTone.WARN), pillFor(false, StatusBadge.SPEED_PROFILE))
        assertEquals(Pill("Not compiled", PillTone.WARN), pillFor(false, StatusBadge.NEEDS_RECOMPILE))
        assertNull(pillFor(false, StatusBadge.UNKNOWN))
        assertNull(pillFor(false, null))
        assertEquals(Pill("Checking…", PillTone.NEUTRAL), pillFor(true, null))
        // A reload keeps the known badge instead of flashing "Checking…".
        assertEquals(Pill("speed ✓", PillTone.OK), pillFor(true, StatusBadge.SPEED_OK))
    }

    @Test fun selfOptimizedPillStates() {
        // Matching record: SideSpeed itself is optimized.
        assertEquals(
            Pill("SideSpeed optimized", PillTone.OK),
            selfOptimizedPill(SelfCompile(42L, 100L), 42L, 100L, false),
        )
        // No record yet: optimizes itself when left.
        assertEquals(
            Pill("SideSpeed optimizes itself when you leave", PillTone.NEUTRAL),
            selfOptimizedPill(null, 42L, 100L, false),
        )
        // Stale record from an older version.
        assertEquals(
            Pill("SideSpeed optimizes itself when you leave", PillTone.NEUTRAL),
            selfOptimizedPill(SelfCompile(41L, 100L), 42L, 100L, false),
        )
        // Stale record from an older update of the same version.
        assertEquals(
            Pill("SideSpeed optimizes itself when you leave", PillTone.NEUTRAL),
            selfOptimizedPill(SelfCompile(42L, 99L), 42L, 100L, false),
        )
        // Debuggable builds show nothing: ART won't compile them.
        assertNull(selfOptimizedPill(SelfCompile(42L, 100L), 42L, 100L, true))
        assertNull(selfOptimizedPill(null, 42L, 100L, true))
    }

    @Test fun headerCaptionCombinations() {
        assertEquals(
            "This TV: 192.168.5.140 · 100.79.202.46 · Maintenance: 11 waiting",
            headerCaption("This TV: 192.168.5.140 · 100.79.202.46", "Maintenance: 11 waiting"),
        )
        assertEquals(
            "This TV: 192.168.5.140",
            headerCaption("This TV: 192.168.5.140", null),
        )
        assertEquals(
            "Maintenance: 11 waiting",
            headerCaption(null, "Maintenance: 11 waiting"),
        )
        assertNull(headerCaption(null, null))
    }

    @Test fun findAppValidation() {
        val v = vm(Source(listOf(nuvio)))
        assertNull(v.findApp("com.foo; reboot"))
        assertNull(v.findApp("com.not.here"))
        assertEquals(nuvio, v.findApp(" com.nuvio.tv "))
    }

    @Test fun listRunsOnInjectedIoDispatcherNotMain() {
        val exec = Executors.newSingleThreadExecutor { Thread(it, "io-test") }
        try {
            val src = Source(listOf(store, nuvio))
            val v = AppsViewModel(src, Badges({ emptyMap() }), exec.asCoroutineDispatcher())
            v.load()
            runBlocking { withTimeout(10_000) { v.state.first { !it.loading } } }
            assertTrue(src.listThread!!.startsWith("io-test"))
            assertEquals(listOf(nuvio, store), v.state.value.apps)
            assertTrue(v.state.value.apps.first().isSideloaded)
        } finally {
            exec.shutdownNow()
        }
    }
}
