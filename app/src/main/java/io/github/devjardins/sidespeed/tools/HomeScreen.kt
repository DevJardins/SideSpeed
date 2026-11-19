package io.github.devjardins.sidespeed.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.devjardins.sidespeed.compile.ActivityClassName
import io.github.devjardins.sidespeed.compile.PackageName
import kotlinx.coroutines.flow.first

/** A Home activity component: package plus fully-qualified activity class. */
data class HomeComponent(val pkg: String, val cls: String) {
    val flattened: String get() = "$pkg/$cls"
}

fun encodeHomeComponent(c: HomeComponent): String = c.flattened

/** Parses a stored `pkg/cls` string; both parts must validate exactly, else null. */
fun parseHomeComponent(raw: String?): HomeComponent? {
    val parts = raw?.split("/")?.takeIf { it.size == 2 } ?: return null
    val pkg = PackageName.parse(parts[0])?.value?.takeIf { it == parts[0] } ?: return null
    val cls = ActivityClassName.parse(parts[1])?.value?.takeIf { it == parts[1] } ?: return null
    return HomeComponent(pkg, cls)
}

data class Launcher(val component: HomeComponent, val label: String)

/** PackageManager reads for the Home switcher, wrapped so the logic is unit-testable. */
interface HomeQueries {
    fun currentHome(): HomeComponent?
    fun homeLaunchers(): List<Launcher>
    fun isPackageDisabled(pkg: String): Boolean
}

/** Helper writes the Home switcher needs; [ToolsCommands] is the production implementation. */
interface HomeCommands {
    suspend fun setHome(pkg: String, cls: String): ToolResult<Unit>
    suspend fun enablePackage(pkg: String): ToolResult<Unit>
    suspend fun homeRoleHolder(): ToolResult<String?>
}

sealed interface HomeResult {
    data class Ok(val component: HomeComponent) : HomeResult
    data object CouldntChange : HomeResult
    data object NoOriginal : HomeResult
    data object StockDisabledElsewhere : HomeResult
    data object FireOs : HomeResult
    data object HelperDown : HomeResult
    data object HelperOutdated : HomeResult
    data object Refused : HomeResult
    data class Failed(val message: String) : HomeResult
}

sealed interface HomeCardState {
    data object FireOs : HomeCardState
    data class NoOtherLauncher(val current: HomeComponent?) : HomeCardState
    data class Ready(
        val current: HomeComponent?,
        val launchers: List<Launcher>,
        val original: HomeComponent?,
        val drifted: Boolean,
    ) : HomeCardState
}

/** True for activities that answer HOME but must never be offered or set as Home. */
fun isExcludedHome(pkg: String, cls: String, ownPackage: String): Boolean = when {
    pkg == ownPackage -> true
    pkg == "android" -> true
    pkg.contains("setupwraith") && cls.substringAfterLast('.') == "RecoveryActivity" -> true
    cls.substringAfterLast('.') == "FallbackHome" -> true
    else -> false
}

/**
 * Home screen switching. Reads are in-app PackageManager queries; the only write is the
 * helper `set-home` form (plus `pm enable` on Back to stock for a stock package SideSpeed
 * itself disabled). The original Home is recorded once, only after a change is confirmed
 * by re-resolving Home, and Back to stock only ever touches that record.
 *
 * When resolve is ambiguous (it returns the system resolver once a second HOME app is
 * installed, so [HomeQueries.currentHome] is null), the HOME role holder is the fallback
 * for "current": its package's sole launcher activity, or null when there is not exactly
 * one. A change is confirmed when resolve returns the target, or when resolve is still
 * ambiguous and the role holder is the target package. A failed role read (helper down,
 * refused, ...) falls back to no current, exactly as before.
 */
class HomeSwitcher(
    private val queries: HomeQueries,
    private val commands: HomeCommands,
    private val store: ToolsStore,
    private val ownPackage: String,
    val isFireOs: Boolean = false,
    private val sideSpeedDisabled: suspend () -> Set<String> = { emptySet() },
) {
    fun current(): HomeComponent? = queries.currentHome()

    /** Role holder package on a successful read; null on any failure or invalid value. */
    private suspend fun roleHolderPackage(): String? =
        (commands.homeRoleHolder() as? ToolResult.Ok)?.value

    /** [current] with the role-holder fallback when resolve is ambiguous. */
    suspend fun currentWithFallback(): HomeComponent? = effectiveCurrent()

    private suspend fun effectiveCurrent(): HomeComponent? {
        queries.currentHome()?.let { return it }
        val pkg = roleHolderPackage() ?: return null
        return launchers().filter { it.component.pkg == pkg }.singleOrNull()?.component
    }

    /** True when [target] is confirmed Home: resolve returns it, or resolve is ambiguous and the role holder is its package. */
    private suspend fun isConfirmedHome(target: HomeComponent): Boolean {
        val resolved = queries.currentHome()
        if (resolved != null) return resolved == target
        return roleHolderPackage() == target.pkg
    }

    fun launchers(): List<Launcher> =
        queries.homeLaunchers()
            .filterNot { isExcludedHome(it.component.pkg, it.component.cls, ownPackage) }
            .distinctBy { it.component }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /** True when a recorded original exists but Home is that original again (it drifted back). */
    suspend fun hasDrifted(): Boolean {
        val original = parseHomeComponent(store.originalHome.first()) ?: return false
        val now = effectiveCurrent() ?: return false
        return now == original
    }

    suspend fun cardState(): HomeCardState {
        if (isFireOs) return HomeCardState.FireOs
        val now = effectiveCurrent()
        val all = launchers()
        val original = parseHomeComponent(store.originalHome.first())
        val others = all.filter { it.component != now }
        if (others.isEmpty()) return HomeCardState.NoOtherLauncher(now)
        return HomeCardState.Ready(
            current = now,
            launchers = all,
            original = original,
            drifted = original != null && now == original,
        )
    }

    suspend fun makeHome(target: HomeComponent): HomeResult {
        if (isFireOs) return HomeResult.FireOs
        if (isExcludedHome(target.pkg, target.cls, ownPackage)) {
            return HomeResult.Failed("That app can't be the home screen.")
        }
        if (launchers().none { it.component == target }) {
            return HomeResult.Failed("That app isn't a home screen app.")
        }
        val before = effectiveCurrent()
        if (before == target) return HomeResult.Ok(target)
        when (val result = commands.setHome(target.pkg, target.cls)) {
            is ToolResult.Ok -> {}
            is ToolResult.HelperDown -> return HomeResult.HelperDown
            is ToolResult.HelperOutdated -> return HomeResult.HelperOutdated
            is ToolResult.Refused -> return HomeResult.Refused
            is ToolResult.Failed -> return HomeResult.Failed(result.message)
        }
        if (!isConfirmedHome(target)) return HomeResult.CouldntChange
        // Record the original once, only after the change is confirmed.
        if (before != null && store.originalHome.first() == null) {
            store.setOriginalHome(encodeHomeComponent(before))
        }
        return HomeResult.Ok(target)
    }

    suspend fun backToStock(): HomeResult {
        if (isFireOs) return HomeResult.FireOs
        val raw = store.originalHome.first() ?: return HomeResult.NoOriginal
        val original = parseHomeComponent(raw) ?: run {
            store.setOriginalHome(null)
            return HomeResult.Failed("The saved home screen isn't valid any more.")
        }
        if (effectiveCurrent() == original) {
            store.setOriginalHome(null)
            return HomeResult.Ok(original)
        }
        if (queries.isPackageDisabled(original.pkg)) {
            if (original.pkg !in sideSpeedDisabled()) return HomeResult.StockDisabledElsewhere
            when (val enabled = commands.enablePackage(original.pkg)) {
                is ToolResult.Ok -> {}
                is ToolResult.HelperDown -> return HomeResult.HelperDown
                is ToolResult.HelperOutdated -> return HomeResult.HelperOutdated
                is ToolResult.Refused -> return HomeResult.Refused
                is ToolResult.Failed -> return HomeResult.Failed(enabled.message)
            }
        }
        when (val result = commands.setHome(original.pkg, original.cls)) {
            is ToolResult.Ok -> {}
            is ToolResult.HelperDown -> return HomeResult.HelperDown
            is ToolResult.HelperOutdated -> return HomeResult.HelperOutdated
            is ToolResult.Refused -> return HomeResult.Refused
            is ToolResult.Failed -> return HomeResult.Failed(result.message)
        }
        if (!isConfirmedHome(original)) return HomeResult.CouldntChange
        store.setOriginalHome(null)
        return HomeResult.Ok(original)
    }
}

/** [HomeQueries] backed by the real PackageManager (in-app; no helper needed). */
class AndroidHomeQueries(context: Context) : HomeQueries {
    private val pm: PackageManager = context.packageManager

    override fun currentHome(): HomeComponent? {
        val intent = homeIntent()
        val resolved = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val info = resolved.activityInfo ?: return null
        if (info.packageName == "android") return null
        return HomeComponent(info.packageName, info.name)
    }

    override fun homeLaunchers(): List<Launcher> {
        val intent = homeIntent()
        val matches = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        return matches.mapNotNull { ri ->
            val info = ri.activityInfo ?: return@mapNotNull null
            val label = try {
                ri.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: info.packageName
            } catch (_: Exception) {
                info.packageName
            }
            Launcher(HomeComponent(info.packageName, info.name), label)
        }
    }

    override fun isPackageDisabled(pkg: String): Boolean = try {
        when (pm.getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> true
            else -> false
        }
    } catch (_: Exception) {
        // Not installed (or unreadable): it cannot take Home, so treat it as disabled.
        true
    }

    private fun homeIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
}
