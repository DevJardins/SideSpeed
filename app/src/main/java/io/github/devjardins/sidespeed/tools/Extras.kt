package io.github.devjardins.sidespeed.tools

import io.github.devjardins.sidespeed.compile.PackageName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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

data class ProtectionFacts(
    val settingsHandlers: Set<String>,
    val tvInputHardware: Set<String>,
)

data class ExtrasRunOutcome(
    val turnedOff: Int,
    val failed: Int,
    val failedByPkg: Map<String, String> = emptyMap(),
)

data class ExtrasRestoreOutcome(
    val restored: Int,
    val failed: Int,
    val failedByPkg: Map<String, String> = emptyMap(),
)

data class ExtrasTurnOnOutcome(
    val turnedOn: Int,
    val failed: Int,
    val failedByPkg: Map<String, String> = emptyMap(),
)

fun formatRunOutcome(o: ExtrasRunOutcome): String = buildString {
    append("Turned off ${o.turnedOff}.")
    if (o.failed > 0) append(" Failed ${o.failed}.")
}

fun formatRestoreOutcome(o: ExtrasRestoreOutcome): String = buildString {
    append("Turned back on ${o.restored}.")
    if (o.failed > 0) append(" Failed ${o.failed}.")
}

fun formatTurnOnOutcome(o: ExtrasTurnOnOutcome): String = buildString {
    append("Turned on ${o.turnedOn}.")
    if (o.failed > 0) append(" Failed ${o.failed}.")
}

internal fun displayLabel(label: String?, pkg: String): String {
    if (label.isNullOrBlank()) return pkg
    val trimmed = label.trim()
    if (!trimmed.contains(' ') && trimmed.contains('.')) return pkg
    return trimmed
}

/** PackageManager facts the extras screen needs; all testable without a device. */
interface ExtrasQueries {
    fun labelOf(pkg: String): String?
    fun isInstalled(pkg: String): Boolean
    /** null when the query failed. */
    fun settingsHandlerPackages(): Set<String>?
    /** null when the query failed. */
    fun tvInputHardwarePackages(): Set<String>?
}

interface ExtrasDisableCommands {
    suspend fun disablePackage(pkg: String): ToolResult<Unit>
    suspend fun enablePackage(pkg: String): ToolResult<Unit>
    suspend fun disabledPackages(): ToolResult<Set<String>>
}

data class ExtrasRefreshResult(
    val rows: List<ExtrasRow>,
    val recordedCount: Int,
    val protectionCheckFailed: Boolean,
    val helperMessage: String?,
)

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    suspend fun readDisabledPackages(): ToolResult<Set<String>> =
        withContext(ioDispatcher) { commands.disabledPackages() }

    suspend fun loadProtectionFacts(): ProtectionFacts? = withContext(ioDispatcher) {
        val settings = queries.settingsHandlerPackages() ?: return@withContext null
        val tvInput = queries.tvInputHardwarePackages() ?: return@withContext null
        ProtectionFacts(settings, tvInput)
    }

    suspend fun refresh(
        meta: ExtrasListMeta,
        failedByPkg: Map<String, String> = emptyMap(),
    ): ExtrasRefreshResult {
        val disabledResult = withContext(ioDispatcher) { commands.disabledPackages() }
        when (disabledResult) {
            is ToolResult.HelperDown ->
                return ExtrasRefreshResult(emptyList(), 0, protectionCheckFailed = false, helperMessage = HELPER_DOWN_MSG)
            is ToolResult.HelperOutdated ->
                return ExtrasRefreshResult(emptyList(), 0, protectionCheckFailed = false, helperMessage = HELPER_OUTDATED_MSG)
            is ToolResult.Refused, is ToolResult.Failed ->
                return ExtrasRefreshResult(emptyList(), 0, protectionCheckFailed = false, helperMessage = HELPER_DOWN_MSG)
            is ToolResult.Ok -> { /* continue */ }
        }
        val disabled = disabledResult.value
        val facts = loadProtectionFacts()
        val protectionFailed = facts == null
        val rows = rows(meta, disabled, facts, failedByPkg, forceNonSelectable = protectionFailed)
        val recorded = store.extrasDisabled.first().size
        return ExtrasRefreshResult(rows, recorded, protectionFailed, helperMessage = null)
    }

    suspend fun rows(
        meta: ExtrasListMeta,
        disabled: Set<String>,
        facts: ProtectionFacts?,
        failedByPkg: Map<String, String> = emptyMap(),
        forceNonSelectable: Boolean = false,
    ): List<ExtrasRow> {
        val home = homeCurrent()
        val homePkg = home?.pkg
        val homePkgHomeInList = meta.let { loadEntries(it).any { e -> e.pkg == homePkg && e.home } }
        val entries = loadEntries(meta)
        return entries.map { entry ->
            rowFor(entry, disabled, homePkg, homePkgHomeInList, facts, failedByPkg, forceNonSelectable)
        }
    }

    private fun rowFor(
        entry: ExtrasEntry,
        disabled: Set<String>,
        homePkg: String?,
        homePkgHomeInList: Boolean,
        facts: ProtectionFacts?,
        failedByPkg: Map<String, String>,
        forceNonSelectable: Boolean,
    ): ExtrasRow {
        val label = displayLabel(queries.labelOf(entry.pkg), entry.pkg)
        val failedLine = failedByPkg[entry.pkg]
        if (isProtected(entry.pkg, entry.setup, homePkg, facts)) {
            return ExtrasRow(entry, label, ExtrasRowKind.Protected, selectable = false, checked = false, caption = null, failedLine)
        }
        if (!queries.isInstalled(entry.pkg)) {
            return ExtrasRow(entry, label, ExtrasRowKind.NotInstalled, selectable = false, checked = false, caption = null, failedLine)
        }
        if (entry.pkg in disabled) {
            val caption = if (entry.home) "This is a home screen app." else null
            return ExtrasRow(
                entry,
                label,
                ExtrasRowKind.Off,
                selectable = !forceNonSelectable,
                checked = false,
                caption = caption,
                failedLine,
            )
        }
        val (selectable, caption) = homeRowSelectable(entry, homePkg, homePkgHomeInList)
        val effectiveSelectable = selectable && !forceNonSelectable
        val checked = effectiveSelectable && !entry.optional && !entry.home
        return ExtrasRow(
            entry,
            label,
            ExtrasRowKind.On,
            selectable = effectiveSelectable,
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

    fun isProtected(
        pkg: String,
        setupFlag: Boolean,
        homePkg: String? = null,
        facts: ProtectionFacts? = null,
    ): Boolean {
        if (ExtrasSafety.isCore(pkg)) return true
        if (setupFlag) return true
        if (pkg == ownPackage) return true
        if (homePkg != null && pkg == homePkg) return true
        if (facts != null) {
            if (pkg in facts.settingsHandlers) return true
            if (pkg in facts.tvInputHardware) return true
        }
        return false
    }

    suspend fun turnOffSelected(
        selectedPkgs: Set<String>,
        entries: List<ExtrasEntry>,
        disabledBefore: Set<String>,
        homePkgHomeInList: Boolean,
        facts: ProtectionFacts,
    ): Pair<ExtrasRunOutcome, ExtrasRunStop?> {
        val homePkg = homeCurrent()?.pkg
        var turnedOff = 0
        var failed = 0
        val entryByPkg = entries.associateBy { it.pkg }
        val failedByPkg = mutableMapOf<String, String>()
        for (pkg in selectedPkgs) {
            val entry = entryByPkg[pkg] ?: continue
            if (pkg in disabledBefore) continue
            if (!rowFor(entry, disabledBefore, homePkg, homePkgHomeInList, facts, failedByPkg, forceNonSelectable = false).selectable) {
                continue
            }
            if (isProtected(pkg, entry.setup, homePkg, facts)) continue
            if (PackageName.parse(pkg) == null) continue
            when (val result = commands.disablePackage(pkg)) {
                is ToolResult.Ok -> {
                    store.addExtrasDisabled(DisabledRecord(pkg, nowMs()))
                    turnedOff++
                }
                is ToolResult.HelperDown -> return ExtrasRunOutcome(turnedOff, failed, failedByPkg) to ExtrasRunStop.HelperDown
                is ToolResult.HelperOutdated -> return ExtrasRunOutcome(turnedOff, failed, failedByPkg) to ExtrasRunStop.HelperOutdated
                is ToolResult.Refused -> {
                    failedByPkg[pkg] = "refused"
                    failed++
                }
                is ToolResult.Failed -> {
                    failedByPkg[pkg] = result.message.lineSequence().firstOrNull().orEmpty()
                    failed++
                }
            }
        }
        return ExtrasRunOutcome(turnedOff, failed, failedByPkg.toMap()) to null
    }

    suspend fun turnOnSelected(
        selectedPkgs: Set<String>,
        entries: List<ExtrasEntry>,
        disabledBefore: Set<String>,
        facts: ProtectionFacts,
    ): Pair<ExtrasTurnOnOutcome, ExtrasRunStop?> {
        val homePkg = homeCurrent()?.pkg
        var turnedOn = 0
        var failed = 0
        val entryByPkg = entries.associateBy { it.pkg }
        val recorded = store.extrasDisabled.first().map { it.pkg }.toSet()
        val failedByPkg = mutableMapOf<String, String>()
        for (pkg in selectedPkgs) {
            val entry = entryByPkg[pkg] ?: continue
            if (pkg !in disabledBefore) continue
            if (isProtected(pkg, entry.setup, homePkg, facts)) continue
            if (PackageName.parse(pkg) == null) continue
            when (val result = commands.enablePackage(pkg)) {
                is ToolResult.Ok -> {
                    if (pkg in recorded) store.removeExtrasDisabled(pkg)
                    turnedOn++
                }
                is ToolResult.HelperDown -> return ExtrasTurnOnOutcome(turnedOn, failed, failedByPkg) to ExtrasRunStop.HelperDown
                is ToolResult.HelperOutdated -> return ExtrasTurnOnOutcome(turnedOn, failed, failedByPkg) to ExtrasRunStop.HelperOutdated
                is ToolResult.Refused -> {
                    failedByPkg[pkg] = "refused"
                    failed++
                }
                is ToolResult.Failed -> {
                    failedByPkg[pkg] = result.message.lineSequence().firstOrNull().orEmpty()
                    failed++
                }
            }
        }
        return ExtrasTurnOnOutcome(turnedOn, failed, failedByPkg.toMap()) to null
    }

    suspend fun restoreEverything(): Pair<ExtrasRestoreOutcome, ExtrasRunStop?> {
        val recorded = store.extrasDisabled.first().sortedByDescending { it.atMs }
        var restored = 0
        var failed = 0
        val failedByPkg = mutableMapOf<String, String>()
        for (rec in recorded) {
            if (PackageName.parse(rec.pkg) == null) continue
            when (val result = commands.enablePackage(rec.pkg)) {
                is ToolResult.Ok -> {
                    store.removeExtrasDisabled(rec.pkg)
                    restored++
                }
                is ToolResult.HelperDown -> return ExtrasRestoreOutcome(restored, failed, failedByPkg) to ExtrasRunStop.HelperDown
                is ToolResult.HelperOutdated -> return ExtrasRestoreOutcome(restored, failed, failedByPkg) to ExtrasRunStop.HelperOutdated
                is ToolResult.Refused -> {
                    failedByPkg[rec.pkg] = "refused"
                    failed++
                }
                is ToolResult.Failed -> {
                    failedByPkg[rec.pkg] = result.message.lineSequence().firstOrNull().orEmpty()
                    failed++
                }
            }
        }
        return ExtrasRestoreOutcome(restored, failed, failedByPkg.toMap()) to null
    }

    suspend fun recordedCount(): Int = store.extrasDisabled.first().size

    companion object {
        const val HELPER_DOWN_MSG = "Helper not running. Start the helper, then try again."
        const val HELPER_OUTDATED_MSG = "Restart the helper to use this."
        const val PROTECTION_CHECK_FAILED_MSG = "Couldn't check which apps are safe to turn off. Try again."
    }
}

enum class ExtrasRunStop { HelperDown, HelperOutdated }