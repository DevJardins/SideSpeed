package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val STOCK = HomeComponent("com.example.stock", "com.example.stock.MainActivity")
private val OTHER = HomeComponent("com.example.other", "com.example.other.HomeActivity")
private val THIRD = HomeComponent("com.example.third", "com.example.third.HomeActivity")

private class FakeHomeQueries(
    var current: HomeComponent?,
    var all: List<Launcher>,
    val disabled: MutableSet<String> = mutableSetOf(),
) : HomeQueries {
    override fun currentHome(): HomeComponent? = current
    override fun homeLaunchers(): List<Launcher> = all
    override fun isPackageDisabled(pkg: String): Boolean = pkg in disabled
}

private class FakeHomeCommands(private val queries: FakeHomeQueries) : HomeCommands {
    val calls = mutableListOf<String>()
    var setHomeResult: ToolResult<Unit> = ToolResult.Ok(Unit)
    var enableResult: ToolResult<Unit> = ToolResult.Ok(Unit)
    var flipOnSetHome: Boolean = true

    override suspend fun setHome(pkg: String, cls: String): ToolResult<Unit> {
        calls += "set-home $pkg $cls"
        if (setHomeResult is ToolResult.Ok && flipOnSetHome) queries.current = HomeComponent(pkg, cls)
        return setHomeResult
    }

    override suspend fun enablePackage(pkg: String): ToolResult<Unit> {
        calls += "pm enable $pkg"
        if (enableResult is ToolResult.Ok) queries.disabled -= pkg
        return enableResult
    }
}

private class FakeHomeStore : ToolsStore {
    val original = MutableStateFlow<String?>(null)
    override val cleanupThreshold: Flow<CleanupThreshold> = MutableStateFlow(CleanupThreshold.OFF)
    override suspend fun setCleanupThreshold(t: CleanupThreshold) {}
    override val lastCleanup: Flow<CleanupRecord?> = MutableStateFlow(null)
    override suspend fun setLastCleanup(r: CleanupRecord) {}
    override fun launchResult(pkg: String): Flow<LaunchResult?> = MutableStateFlow(null)
    override fun launchResults(): Flow<Map<String, LaunchResult>> = MutableStateFlow(emptyMap())
    override suspend fun setLaunchResult(r: LaunchResult) {}
    override val animationOriginals: Flow<AnimationOriginals?> = MutableStateFlow(null)
    override suspend fun setAnimationOriginals(o: AnimationOriginals?) {}
    override val restorePending: Flow<RestorePending?> = MutableStateFlow(null)
    override suspend fun setRestorePending(r: RestorePending?) {}
    override val updateDismissedVersion: Flow<String?> = MutableStateFlow(null)
    override suspend fun setUpdateDismissedVersion(v: String) {}
    override suspend fun clearUpdateDismissedVersion() {}
    override val updateLastCheckMs: Flow<Long> = MutableStateFlow(0L)
    override val updateLatestVersion: Flow<String?> = MutableStateFlow(null)
    override suspend fun setUpdateCheck(atMs: Long, latest: String?) {}
    override val originalHome: Flow<String?> = original
    override suspend fun setOriginalHome(v: String?) { original.value = v }
}

private fun launchers(vararg c: HomeComponent): List<Launcher> =
    c.map { Launcher(it, it.pkg) }

private fun switcher(
    queries: FakeHomeQueries,
    commands: FakeHomeCommands,
    store: FakeHomeStore,
    isFireOs: Boolean = false,
    sideSpeedDisabled: Set<String> = emptySet(),
) = HomeSwitcher(
    queries = queries,
    commands = commands,
    store = store,
    ownPackage = "io.github.devjardins.sidespeed",
    isFireOs = isFireOs,
    sideSpeedDisabled = { sideSpeedDisabled },
)

class HomeScreenTest {
    @Test fun homeComponentRoundTrip() {
        assertEquals(STOCK, parseHomeComponent(encodeHomeComponent(STOCK)))
        assertEquals("com.example.stock/com.example.stock.MainActivity", encodeHomeComponent(STOCK))
        assertNull(parseHomeComponent(null))
        assertNull(parseHomeComponent(""))
        assertNull(parseHomeComponent("nodots"))
        assertNull(parseHomeComponent("com.example.stock/"))
        assertNull(parseHomeComponent("/com.example.stock.MainActivity"))
        assertNull(parseHomeComponent("com.example\$stock/com.example.stock.MainActivity"))
        assertNull(parseHomeComponent("com.example.stock/com.example.stock.MainActivity/extra"))
    }

    @Test fun recordsOriginalOnce() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER, THIRD))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.Ok(OTHER), s.makeHome(OTHER))
        assertEquals(encodeHomeComponent(STOCK), store.originalHome.first())
        assertEquals(HomeResult.Ok(THIRD), s.makeHome(THIRD))
        assertEquals(encodeHomeComponent(STOCK), store.originalHome.first())
    }

    @Test fun successOnlyWhenReResolveMatches() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries).apply { flipOnSetHome = false }
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.CouldntChange, s.makeHome(OTHER))
        assertNull(store.originalHome.first())
    }

    @Test fun helperFailureRecordsNothing() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries).apply { setHomeResult = ToolResult.Failed("boom") }
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertTrue(s.makeHome(OTHER) is HomeResult.Failed)
        assertNull(store.originalHome.first())
    }

    @Test fun helperDownAndRefusedMapThrough() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries).apply { setHomeResult = ToolResult.HelperDown }
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.HelperDown, s.makeHome(OTHER))
        commands.setHomeResult = ToolResult.Refused
        assertEquals(HomeResult.Refused, s.makeHome(OTHER))
        assertNull(store.originalHome.first())
    }

    @Test fun alreadyHomeRecordsNothingAndCallsNothing() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.Ok(STOCK), s.makeHome(STOCK))
        assertTrue(commands.calls.isEmpty())
        assertNull(store.originalHome.first())
    }

    @Test fun targetNotOfferedIsRefusedLocally() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertTrue(s.makeHome(OTHER) is HomeResult.Failed)
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun backToStockSetsOriginalAndClears() = runTest {
        val queries = FakeHomeQueries(OTHER, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore().apply { setOriginalHome(encodeHomeComponent(STOCK)) }
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.Ok(STOCK), s.backToStock())
        assertEquals(listOf("set-home ${STOCK.pkg} ${STOCK.cls}"), commands.calls)
        assertNull(store.originalHome.first())
    }

    @Test fun backToStockWithoutRecord() = runTest {
        val queries = FakeHomeQueries(OTHER, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries)
        val s = switcher(queries, commands, FakeHomeStore())
        assertEquals(HomeResult.NoOriginal, s.backToStock())
        assertTrue(commands.calls.isEmpty())
    }

    @Test fun backToStockWhenAlreadyStockClearsRecord() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore().apply { setOriginalHome(encodeHomeComponent(STOCK)) }
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.Ok(STOCK), s.backToStock())
        assertTrue(commands.calls.isEmpty())
        assertNull(store.originalHome.first())
    }

    @Test fun stockDisabledBySideSpeedEnablesThenSets() = runTest {
        val queries = FakeHomeQueries(OTHER, launchers(STOCK, OTHER), mutableSetOf(STOCK.pkg))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore().apply { setOriginalHome(encodeHomeComponent(STOCK)) }
        val s = switcher(queries, commands, store, sideSpeedDisabled = setOf(STOCK.pkg))
        assertEquals(HomeResult.Ok(STOCK), s.backToStock())
        assertEquals(
            listOf("pm enable ${STOCK.pkg}", "set-home ${STOCK.pkg} ${STOCK.cls}"),
            commands.calls,
        )
        assertNull(store.originalHome.first())
    }

    @Test fun stockDisabledElsewhereStops() = runTest {
        val queries = FakeHomeQueries(OTHER, launchers(STOCK, OTHER), mutableSetOf(STOCK.pkg))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore().apply { setOriginalHome(encodeHomeComponent(STOCK)) }
        val s = switcher(queries, commands, store, sideSpeedDisabled = emptySet())
        assertEquals(HomeResult.StockDisabledElsewhere, s.backToStock())
        assertTrue(commands.calls.isEmpty())
        assertEquals(encodeHomeComponent(STOCK), store.originalHome.first())
    }

    @Test fun fireOsBlocksSwitching() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store, isFireOs = true)
        assertEquals(HomeResult.FireOs, s.makeHome(OTHER))
        assertEquals(HomeResult.FireOs, s.backToStock())
        assertTrue(commands.calls.isEmpty())
        assertEquals(HomeCardState.FireOs, s.cardState())
    }

    @Test fun launchersExcludeSideSpeedSetupAndFallback() {
        val own = HomeComponent("io.github.devjardins.sidespeed", "io.github.devjardins.sidespeed.MainActivity")
        val recovery = HomeComponent(
            "com.google.android.tungsten.setupwraith",
            "com.google.android.tungsten.setupwraith.RecoveryActivity",
        )
        val fallback = HomeComponent("com.android.settings", "com.android.settings.FallbackHome")
        val tvFallback = HomeComponent("com.android.tv.settings", "com.android.tv.settings.system.FallbackHome")
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER, own, recovery, fallback, tvFallback))
        val commands = FakeHomeCommands(queries)
        val s = switcher(queries, commands, FakeHomeStore())
        // Sorted by label (the package name in these fakes).
        assertEquals(listOf(OTHER, STOCK), s.launchers().map { it.component })
    }

    @Test fun noOtherLauncherState() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK))
        val commands = FakeHomeCommands(queries)
        val s = switcher(queries, commands, FakeHomeStore())
        assertEquals(HomeCardState.NoOtherLauncher(STOCK), s.cardState())
    }

    @Test fun driftDetectedWhenHomeReturnsToOriginal() = runTest {
        val queries = FakeHomeQueries(STOCK, launchers(STOCK, OTHER))
        val commands = FakeHomeCommands(queries)
        val store = FakeHomeStore()
        val s = switcher(queries, commands, store)
        assertEquals(HomeResult.Ok(OTHER), s.makeHome(OTHER))
        assertEquals(false, s.hasDrifted())
        queries.current = STOCK
        assertEquals(true, s.hasDrifted())
        val state = s.cardState()
        assertTrue(state is HomeCardState.Ready && state.drifted)
    }
}
