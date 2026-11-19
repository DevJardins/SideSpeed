package io.github.devjardins.sidespeed.tools

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val MATCH_ONN = DeviceMatch("onn", "onn 4K Pro Streaming Device", "jarvis2")
private val MATCH_OTHER = DeviceMatch("acme", "Acme TV", "acme1")

private val NO_PROTECTION = ProtectionFacts(emptySet(), emptySet())
private val TEST_IO = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

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
    var settingsFails: Boolean = false,
    var tvInputFails: Boolean = false,
) : ExtrasQueries {
    var settingsCallCount = 0
    var tvInputCallCount = 0

    override fun labelOf(pkg: String): String? = labels[pkg]
    override fun isInstalled(pkg: String): Boolean = pkg in installed
    override fun settingsHandlerPackages(): Set<String>? {
        settingsCallCount++
        return if (settingsFails) null else settings
    }
    override fun tvInputHardwarePackages(): Set<String>? {
        tvInputCallCount++
        return if (tvInputFails) null else tvInput
    }
}

private class FakeExtrasCommands : ExtrasDisableCommands {
    val disableCalls = mutableListOf<String>()
    val enableCalls = mutableListOf<String>()
    var disableResult: ToolResult<Unit> = ToolResult.Ok(Unit)
    var enableResult: ToolResult<Unit> = ToolResult.Ok(Unit)
    var disabledResult: ToolResult<Set<String>> = ToolResult.Ok(emptySet())
    var disabled: MutableSet<String> = mutableSetOf()

    override suspend fun disablePackage(pkg: String): ToolResult<Unit> {
        disableCalls += pkg
        return disableResult.also { if (it is ToolResult.Ok) disabled += pkg }
    }

    override suspend fun enablePackage(pkg: String): ToolResult<Unit> {
        enableCalls += pkg
        return enableResult.also { if (it is ToolResult.Ok) disabled -= pkg }
    }

    override suspend fun disabledPackages(): ToolResult<Set<String>> =
        when (val r = disabledResult) {
            is ToolResult.Ok -> ToolResult.Ok(disabled.toSet())
            else -> r
        }
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
    ioDispatcher = TEST_IO,
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
        val rows = e.rows(meta, disabled = setOf("com.example.bloat"), NO_PROTECTION)
        val bloat = rows.first { it.entry.pkg == "com.example.bloat" }
        assertEquals(ExtrasRowKind.Off, bloat.kind)
        val on = rows.first { it.entry.pkg == "com.example.optional" }
        assertFalse(on.checked)
        val fresh = e.rows(meta, emptySet(), NO_PROTECTION).first { it.entry.pkg == "com.example.bloat" }
        assertTrue(fresh.checked)
    }

    @Test fun protectedReasons() = runTest {
        val q = FakeExtrasQueries(
            installed = mutableSetOf("com.android.settings", "com.tv.input", "com.example.bloat"),
            settings = setOf("com.android.settings"),
            tvInput = setOf("com.tv.input"),
        )
        val facts = ProtectionFacts(q.settings, q.tvInput)
        val e = extras(queries = q)
        val meta = sampleIndex().lists.first()
        val rows = e.rows(meta, emptySet(), facts)
        assertEquals(ExtrasRowKind.Protected, rows.first { it.entry.pkg == "com.google.android.tungsten.setupwraith" }.kind)
        assertTrue(e.isProtected("com.tv.input", setupFlag = false, homePkg = null, facts = facts))
    }

    @Test fun homeRowSelectableRules() = runTest {
        val meta = sampleIndex().lists.first()
        val e = extras(home = HomeComponent("com.example.home", "com.example.home.Main"))
        val rows = e.rows(meta, emptySet(), NO_PROTECTION)
        val homeRow = rows.first { it.entry.home }
        assertTrue(homeRow.selectable)
        val blocked = extras(
            home = HomeComponent("com.google.android.apps.tv.launcherx", "x"),
            entries = { ONN_ENTRIES + ExtrasEntry("com.google.android.apps.tv.launcherx", "", home = true, setup = false, optional = false) },
        )
        val blockedRow = blocked.rows(meta, emptySet(), NO_PROTECTION).first { it.entry.pkg == "com.example.launcher" }
        assertFalse(blockedRow.selectable)
    }

    @Test fun protectionQueriesCalledOncePerRefresh() = runTest {
        val many = (1..100).map { ExtrasEntry("com.example.p$it", "", home = false, setup = false, optional = false) }
        val q = FakeExtrasQueries(installed = many.map { it.pkg }.toMutableSet())
        val e = extras(entries = { many }, queries = q)
        val meta = sampleIndex().lists.first()
        val facts = e.loadProtectionFacts()
        assertEquals(1, q.settingsCallCount)
        assertEquals(1, q.tvInputCallCount)
        e.rows(meta, emptySet(), facts!!)
        assertEquals(1, q.settingsCallCount)
        assertEquals(1, q.tvInputCallCount)
    }

    @Test fun protectionQueryFailureMakesRowsNonSelectable() = runTest {
        val q = FakeExtrasQueries(
            installed = mutableSetOf("com.example.bloat"),
            settingsFails = true,
        )
        val e = extras(queries = q)
        val meta = sampleIndex().lists.first()
        val result = e.refresh(meta)
        assertTrue(result.protectionCheckFailed)
        assertNull(result.helperMessage)
        val onRow = result.rows.first { it.entry.pkg == "com.example.bloat" }
        assertFalse(onRow.selectable)
    }

    @Test fun refreshHelperDownShowsMessageNotRows() = runTest {
        val commands = FakeExtrasCommands().apply { disabledResult = ToolResult.HelperDown }
        val e = extras(commands = commands)
        val meta = sampleIndex().lists.first()
        val result = e.refresh(meta)
        assertEquals(Extras.HELPER_DOWN_MSG, result.helperMessage)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun refreshHelperOutdatedShowsMessage() = runTest {
        val commands = FakeExtrasCommands().apply { disabledResult = ToolResult.HelperOutdated }
        val e = extras(commands = commands)
        val result = e.refresh(sampleIndex().lists.first())
        assertEquals(Extras.HELPER_OUTDATED_MSG, result.helperMessage)
    }

    @Test fun runRecordsAfterEachSuccess() = runTest {
        val commands = FakeExtrasCommands()
        val store = FakeExtrasStore()
        val e = extras(commands = commands, store = store)
        val selected = setOf("com.example.bloat")
        val (outcome, stop) = e.turnOffSelected(selected, ONN_ENTRIES, emptySet(), homePkgHomeInList = false, NO_PROTECTION)
        assertEquals(null, stop)
        assertEquals(1, outcome.turnedOff)
        assertEquals(listOf("com.example.bloat"), commands.disableCalls)
        assertEquals(listOf("com.example.bloat"), store.disabled.first().map { it.pkg })
    }

    @Test fun runFailedRowReturnedInOutcome() = runTest {
        val commands = FakeExtrasCommands().apply {
            disableResult = ToolResult.Failed("Cannot disable a protected package\nmore detail")
        }
        val e = extras(commands = commands)
        val (outcome, _) = e.turnOffSelected(setOf("com.example.bloat"), ONN_ENTRIES, emptySet(), false, NO_PROTECTION)
        assertEquals(1, outcome.failed)
        assertEquals("Cannot disable a protected package", outcome.failedByPkg["com.example.bloat"])
    }

    @Test fun failedRowsShownUntilNextRun() = runTest {
        val meta = sampleIndex().lists.first()
        val e = extras()
        val failures = mapOf("com.example.bloat" to "oops")
        val rows = e.rows(meta, emptySet(), NO_PROTECTION, failures)
        assertEquals("oops", rows.first { it.entry.pkg == "com.example.bloat" }.failedLine)
    }

    @Test fun helperDownStopsRun() = runTest {
        val commands = FakeExtrasCommands().apply { disableResult = ToolResult.HelperDown }
        val e = extras(commands = commands)
        val (_, stop) = e.turnOffSelected(setOf("com.example.bloat"), ONN_ENTRIES, emptySet(), false, NO_PROTECTION)
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
        val (outcome, stop) = e.restoreEverything()
        assertEquals(null, stop)
        assertEquals(2, outcome.restored)
        assertEquals(listOf("com.b", "com.a"), commands.enableCalls)
        assertTrue(store.disabled.first().isEmpty())
    }

    @Test fun restoreReportsFailures() = runTest {
        val commands = FakeExtrasCommands().apply {
            enableResult = ToolResult.Failed("package missing")
        }
        val store = FakeExtrasStore()
        store.disabled.value = listOf(DisabledRecord("com.a", 1))
        val e = extras(commands = commands, store = store, entries = { emptyList() })
        val (outcome, _) = e.restoreEverything()
        assertEquals(0, outcome.restored)
        assertEquals(1, outcome.failed)
        assertEquals("package missing", outcome.failedByPkg["com.a"])
        assertEquals("Turned back on 0. Failed 1.", formatRestoreOutcome(outcome))
        assertEquals(listOf("com.a"), store.disabled.first().map { it.pkg })
    }

    @Test fun runOutcomeOmitsAlreadyOff() = runTest {
        val commands = FakeExtrasCommands()
        val e = extras(commands = commands)
        val (outcome, _) = e.turnOffSelected(
            setOf("com.example.bloat"),
            ONN_ENTRIES,
            disabledBefore = setOf("com.example.bloat"),
            homePkgHomeInList = false,
            facts = NO_PROTECTION,
        )
        assertEquals(0, outcome.turnedOff)
        assertTrue(commands.disableCalls.isEmpty())
        assertEquals("Turned off 0.", formatRunOutcome(outcome))
    }

    @Test fun homeCouldntChangeMessage() {
        assertTrue(homeCouldntChangeMessage("com.google.android.apps.tv.launcherx").contains("Turn off extras"))
        assertEquals("Couldn't change Home", homeCouldntChangeMessage("com.example.other"))
    }

    @Test fun displayLabelFallsBackToPackage() {
        assertEquals("a.b", displayLabel(null, "a.b"))
        assertEquals("a.b", displayLabel("  ", "a.b"))
        assertEquals(
            "com.android.ondevicepersonalization.services",
            displayLabel(
                "com.android.ondevicepersonalization.services.OnDevicePersonalizationApplication",
                "com.android.ondevicepersonalization.services",
            ),
        )
    }

    @Test fun displayLabelKeepsHumanReadableNames() {
        assertEquals("Print Spooler", displayLabel("Print Spooler", "com.android.printspooler"))
        assertEquals("YouTube", displayLabel("YouTube", "com.google.android.youtube.tv"))
        assertEquals("Ver. 2 Agent", displayLabel("Ver. 2 Agent", "x.y"))
    }

    @Test fun rowLabelUsesDisplayLabelForClassName() = runTest {
        val pkg = "com.android.ondevicepersonalization.services"
        val classLabel = "$pkg.OnDevicePersonalizationApplication"
        val q = FakeExtrasQueries(
            installed = mutableSetOf(pkg),
            labels = mapOf(pkg to classLabel),
        )
        val entries = listOf(ExtrasEntry(pkg, "", home = false, setup = false, optional = true))
        val e = extras(queries = q, entries = { entries })
        val row = e.rows(sampleIndex().lists.first(), emptySet(), NO_PROTECTION).single()
        assertEquals(pkg, row.label)
    }
}