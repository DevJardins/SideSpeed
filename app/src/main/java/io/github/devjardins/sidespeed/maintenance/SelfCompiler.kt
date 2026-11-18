package io.github.devjardins.sidespeed.maintenance

import android.util.Log
import io.github.devjardins.sidespeed.compile.CompileEvent
import io.github.devjardins.sidespeed.compile.CompileMode
import io.github.devjardins.sidespeed.compile.PackageName
import io.github.devjardins.sidespeed.data.Prefs
import io.github.devjardins.sidespeed.status.CompileStatus
import io.github.devjardins.sidespeed.data.SelfCompile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Compiles SideSpeed itself to speed, once per install/update.
 *
 * Runs `pm compile -m speed -f` for our own package through the existing helper path (the command
 * allowlist accepts any valid package for `pm compile`) when the helper is reachable and our install
 * has not been compiled for its current version code + update time. Success is recorded in DataStore
 * keyed by version code + update time, so it runs once per install/update and never touches the app
 * list, history, or "Optimized" counts. Only call this while SideSpeed is in the background: compiling
 * the foreground app may kill or restart it.
 */
class SelfCompiler(
    private val prefs: Prefs,
    private val runCompile: (PackageName, CompileMode) -> Flow<CompileEvent>,
    private val helperUp: suspend () -> Boolean,
    private val ownPackage: String,
    private val versionCode: Long,
    private val lastUpdateTime: Long,
    private val debuggable: Boolean,
    private val status: CompileStatus? = null,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    /** Package with an in-flight launch sequence; never compile while set (it would skew its timings). */
    private val measuring: () -> String? = { null },
) {
    private val compileLock = Mutex()

    /** Suspends until no self-compile is running; launch sequences await this before their before-timing. */
    suspend fun awaitIdle() {
        compileLock.withLock { }
    }

    suspend fun maybeCompile() {
        // ART will not AOT-compile a debuggable build; compiling it would only reach verify.
        if (debuggable) {
            log("self-compile skipped: debuggable build")
            return
        }
        // A launch sequence owns the device right now: stay pending and run on
        // the next normal leave after it ends (its before-timing must see no competition).
        if (measuring() != null) {
            log("self-compile deferred: launch sequence running")
            return
        }
        compileLock.withLock { compileOnce() }
    }

    private suspend fun compileOnce() {
        val done = try {
            prefs.selfCompile.first()
        } catch (_: Exception) {
            return
        }
        if (done != null && done.versionCode == versionCode && done.lastUpdateTime == lastUpdateTime) return
        if (!helperUp()) return
        val name = PackageName.parse(ownPackage) ?: run {
            log("self-compile skipped: invalid own package")
            return
        }
        var success = false
        status?.onStart(ownPackage, SELF_LABEL, CompileMode.SPEED.arg)
        try {
            runCompile(name, CompileMode.SPEED).collect { if (it is CompileEvent.Finished) success = it.success }
        } catch (_: Exception) {
            if (!helperUp()) status?.onHelperLost()
            else status?.onFinish(ownPackage, SELF_LABEL, false)
            return
        }
        if (success) {
            try {
                prefs.setSelfCompile(SelfCompile(versionCode, lastUpdateTime))
            } catch (_: Exception) {
            }
            status?.onFinish(ownPackage, SELF_LABEL, true)
            status?.setSelfOptimized(true)
        } else {
            if (!helperUp()) status?.onHelperLost()
            else status?.onFinish(ownPackage, SELF_LABEL, false)
        }
    }

    companion object {
        private const val TAG = "SideSpeedSelfCompile"
        /** The provider row label while SideSpeed compiles itself. */
        const val SELF_LABEL = "SideSpeed"
    }
}
