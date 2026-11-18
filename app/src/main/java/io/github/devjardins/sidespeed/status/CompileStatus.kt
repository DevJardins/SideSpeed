package io.github.devjardins.sidespeed.status

/** The `state` column of the compile-status row. */
enum class CompilePhase(val wire: String) {
    COMPILING("compiling"),
    IDLE("idle"),
    INTERRUPTED("interrupted"),
    WAITING_HELPER("waiting_helper"),
}

/** The `self` column of the compile-status row. */
enum class SelfState(val wire: String) {
    OPTIMIZED("optimized"),
    PENDING("pending"),
    DEBUG("debug"),
}

/** The compile currently shown in the row: running, or interrupted and awaiting retry. */
data class CompileCurrent(val pkg: String, val label: String, val mode: String, val startedMs: Long)

/** The most recently finished compile. */
data class CompileLast(val pkg: String, val label: String, val ok: Boolean, val finishedMs: Long)

/** What survives a reboot: the in-progress compile (if any) and the last result. */
data class PersistedCompileStatus(val current: CompileCurrent?, val last: CompileLast?)

/** One snapshot of everything the provider publishes. */
data class StatusRow(
    val phase: CompilePhase,
    val activePkg: String,
    val activeLabel: String,
    val activeMode: String,
    val activeStartedMs: Long,
    val queueWaiting: Int,
    val last: CompileLast?,
    val self: SelfState,
    val version: String,
) {
    /** The 13 provider columns, in order: v, state, pkg, label, mode, started_ms, queue,
     * last_pkg, last_label, last_result, last_ms, self, version. All strings, empty when absent. */
    fun toWireMap(): List<Pair<String, String>> = listOf(
        "v" to "1",
        "state" to phase.wire,
        "pkg" to activePkg,
        "label" to encodeStatusLabel(activeLabel),
        "mode" to activeMode,
        "started_ms" to (if (activePkg.isEmpty()) "" else activeStartedMs.toString()),
        "queue" to queueWaiting.toString(),
        "last_pkg" to (last?.pkg ?: ""),
        "last_label" to encodeStatusLabel(last?.label ?: ""),
        "last_result" to (last?.let { if (it.ok) "ok" else "failed" } ?: ""),
        "last_ms" to (last?.finishedMs?.toString() ?: ""),
        "self" to self.wire,
        "version" to version,
    )
}

/**
 * Escapes a label for the `content query` row format, where `,` and `=` separate pairs:
 * `%` first (so decoding is unambiguous), then `,` and `=`, then control characters.
 * Spaces are left alone; the companion percent-decodes.
 */
fun encodeStatusLabel(raw: String): String = buildString(raw.length) {
    for (c in raw) {
        when {
            c == '%' -> append("%25")
            c == ',' -> append("%2C")
            c == '=' -> append("%3D")
            c < ' ' || c == '\u007F' -> {
                append('%')
                append(c.code.toString(16).uppercase().padStart(2, '0'))
            }
            else -> append(c)
        }
    }
}

/**
 * The source of truth for what SideSpeed is compiling right now. Pure in-memory logic:
 * MaintenanceEngine, the manual compile path and SelfCompiler all report here, and the
 * [CompileStatusProvider] publishes [snapshot]. Persistence ([export]/[restore]) is plain
 * data so the store stays in the thin [CompileStatus] glue.
 *
 * `interrupted` means a compile was in progress when the helper went away or the process
 * died; it clears only when the retry starts ([onStart]). All methods are thread-safe.
 */
class CompileStatusTracker(
    private val version: String,
    private val debuggable: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var current: CompileCurrent? = null
    private var interrupted: CompileCurrent? = null
    private var helperDown = false
    private var queueWaiting = 0
    private var last: CompileLast? = null
    private var selfOptimized = false

    fun onStart(pkg: String, label: String, mode: String) = synchronized(lock) {
        current = CompileCurrent(pkg, label, mode, clock())
        interrupted = null
        helperDown = false
    }

    fun onFinish(pkg: String, label: String, ok: Boolean) = synchronized(lock) {
        if (current?.pkg == pkg) current = null
        last = CompileLast(pkg, label, ok, clock())
    }

    /** A compile was in progress when the helper went away: it stays `interrupted` until retried. */
    fun onHelperLost() = synchronized(lock) {
        current?.let { interrupted = it }
        current = null
        helperDown = true
    }

    fun setHelperDown(down: Boolean) = synchronized(lock) { helperDown = down }

    fun setQueueWaiting(n: Int) = synchronized(lock) { queueWaiting = n.coerceAtLeast(0) }

    fun setSelfOptimized(optimized: Boolean) = synchronized(lock) { selfOptimized = optimized }

    /** True while a compile is running (not merely interrupted and awaiting retry). */
    fun isActive(): Boolean = synchronized(lock) { current != null }

    fun snapshot(): StatusRow = synchronized(lock) {
        val active = current ?: interrupted
        val phase = when {
            current != null -> CompilePhase.COMPILING
            interrupted != null -> CompilePhase.INTERRUPTED
            helperDown && queueWaiting > 0 -> CompilePhase.WAITING_HELPER
            else -> CompilePhase.IDLE
        }
        StatusRow(
            phase = phase,
            activePkg = active?.pkg ?: "",
            activeLabel = active?.label ?: "",
            activeMode = active?.mode ?: "",
            activeStartedMs = active?.startedMs ?: 0L,
            queueWaiting = queueWaiting,
            last = last,
            self = if (debuggable) SelfState.DEBUG else if (selfOptimized) SelfState.OPTIMIZED else SelfState.PENDING,
            version = version,
        )
    }

    fun export(): PersistedCompileStatus? = synchronized(lock) {
        // An interrupted compile is still in progress until the retry starts: persist it as such
        // so a reboot reports it interrupted instead of dropping it.
        val pending = current ?: interrupted
        if (pending == null && last == null) null else PersistedCompileStatus(pending, last)
    }

    /** A compile still in progress at shutdown died with the process: report it interrupted. */
    fun restore(saved: PersistedCompileStatus?) = synchronized(lock) {
        interrupted = saved?.current
        last = saved?.last
    }
}
