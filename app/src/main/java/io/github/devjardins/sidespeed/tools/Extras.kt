package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.compile.PackageName
import kotlinx.coroutines.flow.first

/** Google TV stock launchers that block the Home switcher while still enabled. */
val GoogleTvStockLauncherPkgs = setOf(
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
)

fun homeCouldntChangeMessage(currentHomePkg: String?): String = when {
    currentHomePkg != null && currentHomePkg in GoogleTvStockLauncherPkgs ->
        "Google TV keeps its own home screen while it's turned on. Turn it off in Turn off extras first."
    else -> "Couldn't change Home"
}

sealed interface ExtrasMatchResult {
    data object NoList : ExtrasMatchResult
    data object Ambiguous : ExtrasMatchResult
    data class Matched(val folderId: String, val variants: List<ExtrasListMeta>) : ExtrasMatchResult
}

enum class ExtrasRowKind { On, Off, NotInstalled, Protected }

data class ExtrasRow(
    val entry: ExtrasEntry,
    val label: String,
    val kind: ExtrasRowKind,
    val selectable: Boolean,
    val checked: Boolean,
    val caption: String?,
    val failedLine: String? = null,
)

data class ExtrasRunOutcome(val turnedOff: Int, val alreadyOff: Int, val failed: Int)

fun formatRunOutcome(o: ExtrasRunOutcome): String = buildString {
    append("Turned off ${o.turnedOff}.")
    if (o.alreadyOff > 0) append(" Already off ${o.alreadyOff}.")
    if (o.failed > 0) append(" Failed ${o.failed}.")
}

/** PackageManager facts the extras screen needs; all testable without a device. */
interface ExtrasQueries {
    fun labelOf(pkg: String): String?
    fun isInstalled(pkg: String): Boolean
    fun settingsHandlerPackages(): Set<String>
    fun tvInputHardwarePackages(): Set<String>
}

interface ExtrasDisableCommands {
    suspend fun disablePackage(pkg: String): ToolResult<Unit>
    suspend fun enablePackage(pkg: String): ToolResult<Unit>
    suspend fun disabledPackages(): ToolResult<Set<String>>
}

class Extras(
    private val index: ExtrasIndex,
    private val loadEntries: (ExtrasListMeta) -> List<ExtrasEntry>,
    private val queries: ExtrasQueries,
    private val commands: ExtrasDisableCommands,
    private val store: ToolsStore,
    private val homeCurrent: suspend () -> HomeComponent?,
    private val ownPackage: String,
    private val forcedFolderId: String? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun entriesFor(meta: ExtrasListMeta): List<ExtrasEntry> = loadEntries(meta)

    fun homePkgHomeInList(meta: ExtrasListMeta, homePkg: String?): Boolean =
        homePkg != null && loadEntries(meta).any { it.pkg == homePkg && it.home }

    fun match(manufacturer: String, model: String, device: String): ExtrasMatchResult {
        val forceId = forcedFolderId
        if (forceId != null) {
            val variants = index.lists.filter { it.id == forceId }.sortedBy { it.variant }
            return if (variants.isEmpty()) ExtrasMatchResult.NoList
            else ExtrasMatchResult.Matched(forceId, variants)
        }
        val trim = { s: String -> s.trim() }
        val matchedFolders = index.lists
            .mapNotNull { meta ->
                val m = meta.match ?: return@mapNotNull null
                if (trim(manufacturer) == trim(m.manufacturer) &&
                    trim(model) == trim(m.model) &&
                    trim(device) == trim(m.device)
                ) {
                    meta.id
                } else null
            }
            .distinct()
        return when {
            matchedFolders.isEmpty() -> ExtrasMatchResult.NoList
            matchedFolders.size > 1 -> ExtrasMatchResult.Ambiguous
            else -> {
                val id = matchedFolders.single()
                ExtrasMatchResult.Matched(id, index.lists.filter { it.id == id }.sortedBy { it.variant })
            }
        }
    }

  suspend fun rows(meta: ExtrasListMeta, disabled: Set<String>): List<ExtrasRow> {
        val home = homeCurrent()
        val homePkg = home?.pkg
        val homePkgHomeInList = meta.let { loadEntries(it).any { e -> e.pkg == homePkg && e.home } }
        val entries = loadEntries(meta)
        val failedByPkg = mutableMapOf<String, String>()
        return entries.map { entry ->
            rowFor(entry, disabled, homePkg, homePkgHomeInList, failedByPkg)
        }
    }

    private fun rowFor(
        entry: ExtrasEntry,
        disabled: Set<String>,
        homePkg: String?,
        homePkgHomeInList: Boolean,
        failedByPkg: Map<String, String>,
    ): ExtrasRow {
        val label = queries.labelOf(entry.pkg) ?: entry.pkg
        val failedLine = failedByPkg[entry.pkg]
        if (isProtected(entry.pkg, entry.setup, homePkg)) {
            return ExtrasRow(entry, label, ExtrasRowKind.Protected, selectable = false, checked = false, caption = null, failedLine)
        }
        if (!queries.isInstalled(entry.pkg)) {
            return ExtrasRow(entry, label, ExtrasRowKind.NotInstalled, selectable = false, checked = false, caption = null, failedLine)
        }
        if (entry.pkg in disabled) {
            return ExtrasRow(entry, label, ExtrasRowKind.Off, selectable = false, checked = false, caption = null, failedLine)
        }
        val (selectable, caption) = homeRowSelectable(entry, homePkg, homePkgHomeInList)
        val checked = selectable && !entry.optional && !entry.home
        return ExtrasRow(
            entry,
            label,
            ExtrasRowKind.On,
            selectable = selectable,
            checked = checked,
            caption = caption,
            failedLine = failedLine,
        )
    }

    private fun homeRowSelectable(
        entry: ExtrasEntry,
        homePkg: String?,
        homePkgHomeInList: Boolean,
    ): Pair<Boolean, String?> {
        if (!entry.home) return true to null
        val captionHome = "This is a home screen app."
        val captionBlocked = "Choose another home screen first in Tools → Home screen."
        if (homePkg == null) return false to captionBlocked
        if (entry.pkg == homePkg) return false to captionBlocked
        if (homePkgHomeInList) return false to captionBlocked
        return true to captionHome
    }

    fun isProtected(pkg: String, setupFlag: Boolean, homePkg: String? = null): Boolean {
        if (ExtrasSafety.isCore(pkg)) return true
        if (setupFlag) return true
        if (pkg == ownPackage) return true
        if (homePkg != null && pkg == homePkg) return true
        if (pkg in queries.settingsHandlerPackages()) return true
        if (pkg in queries.tvInputHardwarePackages()) return true
        return false
    }

    suspend fun turnOffSelected(
        selectedPkgs: Set<String>,
        entries: List<ExtrasEntry>,
        disabledBefore: Set<String>,
        homePkgHomeInList: Boolean,
    ): Pair<ExtrasRunOutcome, ExtrasRunStop?> {
        val homePkg = homeCurrent()?.pkg
        var turnedOff = 0
        var alreadyOff = 0
        var failed = 0
        val entryByPkg = entries.associateBy { it.pkg }
        val failedRows = mutableMapOf<String, String>()
        for (pkg in selectedPkgs) {
            val entry = entryByPkg[pkg] ?: continue
            if (!rowFor(entry, disabledBefore, homePkg, homePkgHomeInList, failedRows).selectable) continue
            if (isProtected(pkg, entry.setup, homePkg)) continue
            if (PackageName.parse(pkg) == null) continue
            if (pkg in disabledBefore) {
                alreadyOff++
                continue
            }
            when (val result = commands.disablePackage(pkg)) {
                is ToolResult.Ok -> {
                    store.addExtrasDisabled(DisabledRecord(pkg, nowMs()))
                    turnedOff++
                }
                is ToolResult.HelperDown -> return ExtrasRunOutcome(turnedOff, alreadyOff, failed) to ExtrasRunStop.HelperDown
                is ToolResult.HelperOutdated -> return ExtrasRunOutcome(turnedOff, alreadyOff, failed) to ExtrasRunStop.HelperOutdated
                is ToolResult.Refused -> {
                    failedRows[pkg] = "refused"
                    failed++
                }
                is ToolResult.Failed -> {
                    failedRows[pkg] = result.message.lineSequence().firstOrNull().orEmpty()
                    failed++
                }
            }
        }
        return ExtrasRunOutcome(turnedOff, alreadyOff, failed) to null
    }

    suspend fun restoreEverything(): Pair<Int, ExtrasRunStop?> {
        val recorded = store.extrasDisabled.first().sortedByDescending { it.atMs }
        var restored = 0
        for (rec in recorded) {
            if (PackageName.parse(rec.pkg) == null) continue
            when (val result = commands.enablePackage(rec.pkg)) {
                is ToolResult.Ok -> {
                    store.removeExtrasDisabled(rec.pkg)
                    restored++
                }
                is ToolResult.HelperDown -> return restored to ExtrasRunStop.HelperDown
                is ToolResult.HelperOutdated -> return restored to ExtrasRunStop.HelperOutdated
                is ToolResult.Refused, is ToolResult.Failed -> { /* stay recorded */ }
            }
        }
        return restored to null
    }

    suspend fun recordedCount(): Int = store.extrasDisabled.first().size
}

enum class ExtrasRunStop { HelperDown, HelperOutdated }