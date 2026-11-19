package io.github.devjardins.sidespeed.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val MATCH_ONN = DeviceMatch("onn", "onn 4K Pro Streaming Device", "jarvis2")
private val MATCH_OTHER = DeviceMatch("acme", "Acme TV", "acme1")

private fun sampleIndex() = ExtrasIndex(
    upstreamCommit = "abc1234567890",
    lists = listOf(
        ExtrasListMeta("onn-4k-pro-gen2", "default", "onn.json", "onn TV", "Google TV 14", "onn", MATCH_ONN),
        ExtrasListMeta("onn-4k-pro-gen2", "cast", "onn--cast.json", "onn TV", "Google TV 14", "onn", MATCH_ONN),
        ExtrasListMeta("acme-box", "default", "acme.json", "Acme", "ATV 11", "acme", MATCH_OTHER),
        ExtrasListMeta("inert", "default", "inert.json", "Inert", "ATV", "x", null),
    ),
)

private val ONN_ENTRIES = listOf(
    ExtrasEntry("com.example.bloat", "note", home = false, setup = false, optional = false),
    ExtrasEntry("com.example.optional", "", home = false, setup = false, optional = true),
    ExtrasEntry("com.example.launcher", "", home = true, setup = false, optional = false),
    ExtrasEntry("com.google.android.tungsten.setupwraith", "", home = false, setup = true, optional = false),
)

private class FakeExtrasQueries(
    val installed: MutableSet<String> = mutableSetOf(),
    val labels: Map<String, String> = emptyMap(),
    val settings: Set<String> = emptySet(),
    val tvInput: Set<String> = emptySet(),
) : ExtrasQueries {
    override fun labelOf(pkg: String): String? = labels[pkg]
    override fun isInstalled(pkg: String): Boolean = pkg in installed
    override fun settingsHandlerPackages(): Set<String> = settings
    override fun tvInputHardwarePackages(): Set<String> = tvInput
}

private class FakeExtrasCommands : ExtrasDisableCommands {
    val disableCalls = mutableListOf<String>()
    val enableCalls = mutableListOf<String>()
    var disableResult: ToolResult<Unit> = ToolResult.Ok(Unit)
    var enableResult: ToolResult<Unit> = ToolResult.Ok(Unit)
    var disabled: MutableSet<String> = mutableSetOf()

    override suspend fun disablePackage(pkg: String): ToolResult<Unit> {
        disableCalls += pkg
        return disableResult.also { if (it is ToolResult.Ok) disabled += pkg }
    }

    override suspend fun enablePackage(pkg: String): ToolResult<Unit> {
        enableCalls += pkg
        return enableResult.also { if (it is ToolResult.Ok) disabled -= pkg }
    }

    override suspend fun disabledPackages(): ToolResult<Set<String>> = ToolResult.Ok(disabled.toSet())
}

private class FakeExtrasStore : ToolsStore {
    val disabled = MutableStateFlow<List<DisabledRecord>>(emptyList())
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
    override val originalHome: Flow<String?> = MutableStateFlow(null)
    override suspend fun setOriginalHome(v: String?) {}
    override val extrasDisabled: Flow<List<DisabledRecord>> = disabled
    override suspend fun addExtrasDisabled(record: DisabledRecord) {
        disabled.value = disabled.value.filterNot { it.pkg == record.pkg } + record
    }
    override suspend fun removeExtrasDisabled(pkg: String) {
        disabled.value = disabled.value.filterNot { it.pkg == pkg }
    }
}

private fun extras(
    index: ExtrasIndex = sampleIndex(),
    entries: (ExtrasListMeta) -> List<ExtrasEntry> = { ONN_ENTRIES },
    queries: FakeExtrasQueries = FakeExtrasQueries(ONN_ENTRIES.map { it.pkg }.toMutableSet()),
    commands: FakeExtrasCommands = FakeExtrasCommands(),
    store: FakeExtrasStore = FakeExtrasStore(),
    home: HomeComponent? = HomeComponent("com.example.home", "com.example.home.Main"),
    forced: String? = null,
): Extras = Extras(
    index = index,
    loadEntries = entries,
    queries = queries,
    commands = commands,
    store = store,
    homeCurrent = { home },
    ownPackage = "io.github.devjardins.sidespeed",
    forcedFolderId = forced,
    nowMs = { 1000L },
)

class ExtrasTest {
    @Test fun matchExactTriple() {
        val e = extras()
        val r = e.match("onn", "onn 4K Pro Streaming Device", "jarvis2")
        assertTrue(r is ExtrasMatchResult.Matched)
        assertEquals(2, (r as ExtrasMatchResult.Matched).variants.size)
    }

    @Test fun matchOneFieldDifferentIsNoList() {
        val e = extras()
        assertEquals(ExtrasMatchResult.NoList, e.match("onn", "wrong", "jarvis2"))
    }

    @Test fun twoFoldersAmbiguous() {
        val lists = sampleIndex().lists + ExtrasListMeta(
            "dupe", "default", "d.json", "T", "O", "b",
            DeviceMatch("onn", "onn 4K Pro Streaming Device", "jarvis2"),
        )
        val e = extras(index = ExtrasIndex("x", lists))
        assertEquals(ExtrasMatchResult.Ambiguous, e.match("onn", "onn 4K Pro Streaming Device", "jarvis2"))
    }

    @Test fun forcedListInDebug() {
        val e = extras(forced = "inert")
        val r = e.match("any", "thing", "here")
        assertTrue(r is ExtrasMatchResult.Matched)
        assertEquals("inert", (r as ExtrasMatchResult.Matched).folderId)
    }

    @Test fun rowStatesAndDefaults() = runTest {
        val meta = sampleIndex().lists.first()
        val e = extras()
        val rows = e.rows(meta, disabled = setOf("com.example.bloat"))
        val bloat = rows.first { it.entry.pkg == "com.example.bloat" }
        assertEquals(ExtrasRowKind.Off, bloat.kind)
        val on = rows.first { it.entry.pkg == "com.example.optional" }
        assertFalse(on.checked)
        val normal = rows.first { it.entry.pkg == "com.example.bloat" }
        assertEquals(ExtrasRowKind.Off, normal.kind)
        val fresh = e.rows(meta, emptySet()).first { it.entry.pkg == "com.example.bloat" }
        assertTrue(fresh.checked)
    }

    @Test fun protectedReasons() = runTest {
        val q = FakeExtrasQueries(
            installed = mutableSetOf("com.android.settings", "com.tv.input", "com.example.bloat"),
            settings = setOf("com.android.settings"),
            tvInput = setOf("com.tv.input"),
        )
        val e = extras(queries = q)
        val meta = sampleIndex().lists.first()
        val rows = e.rows(meta, emptySet())
        assertEquals(ExtrasRowKind.Protected, rows.first { it.entry.pkg == "com.google.android.tungsten.setupwraith" }.kind)
        assertTrue(e.isProtected("com.tv.input", setupFlag = false, homePkg = null))
    }

    @Test fun homeRowSelectableRules() = runTest {
        val meta = sampleIndex().lists.first()
        val e = extras(home = HomeComponent("com.example.home", "com.example.home.Main"))
        val rows = e.rows(meta, emptySet())
        val homeRow = rows.first { it.entry.home }
        assertTrue(homeRow.selectable)
        val blocked = extras(
            home = HomeComponent("com.google.android.apps.tv.launcherx", "x"),
            entries = { ONN_ENTRIES + ExtrasEntry("com.google.android.apps.tv.launcherx", "", home = true, setup = false, optional = false) },
        )
        val blockedRow = blocked.rows(meta, emptySet()).first { it.entry.pkg == "com.example.launcher" }
        assertFalse(blockedRow.selectable)
    }

    @Test fun runRecordsAfterEachSuccess() = runTest {
        val commands = FakeExtrasCommands()
        val store = FakeExtrasStore()
        val e = extras(commands = commands, store = store)
        val meta = sampleIndex().lists.first()
        val selected = setOf("com.example.bloat")
        val (outcome, stop) = e.turnOffSelected(selected, ONN_ENTRIES, emptySet(), homePkgHomeInList = false)
        assertEquals(null, stop)
        assertEquals(1, outcome.turnedOff)
        assertEquals(listOf("com.example.bloat"), commands.disableCalls)
        assertEquals(listOf("com.example.bloat"), store.disabled.first().map { it.pkg })
    }

    @Test fun helperDownStopsRun() = runTest {
        val commands = FakeExtrasCommands().apply { disableResult = ToolResult.HelperDown }
        val e = extras(commands = commands)
        val (_, stop) = e.turnOffSelected(setOf("com.example.bloat"), ONN_ENTRIES, emptySet(), false)
        assertEquals(ExtrasRunStop.HelperDown, stop)
    }

    @Test fun restoreNewestFirst() = runTest {
        val commands = FakeExtrasCommands()
        val store = FakeExtrasStore()
        store.disabled.value = listOf(
            DisabledRecord("com.a", 1),
            DisabledRecord("com.b", 2),
        )
        val e = extras(commands = commands, store = store, entries = { emptyList() })
        e.restoreEverything()
        assertEquals(listOf("com.b", "com.a"), commands.enableCalls)
        assertTrue(store.disabled.first().isEmpty())
    }

    @Test fun homeCouldntChangeMessage() {
        assertTrue(homeCouldntChangeMessage("com.google.android.apps.tv.launcherx").contains("Turn off extras"))
        assertEquals("Couldn't change Home", homeCouldntChangeMessage("com.example.other"))
    }
}