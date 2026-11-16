# Auto-recompile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The TV app keeps user-chosen apps compiled after every update, unattended, through the existing helper.

**Architecture:** A pure-Kotlin `MaintenanceEngine` in `:app` (one serial queue, one worker coroutine, idempotent `scan()`) driven by triggers. A foreground `MaintenanceService` owns the triggers (start, package added/replaced, set changed, 6-hourly) and the notification. The Apps screen shows engine state; settings live in a new DataStore-backed `MaintenanceStore`.

**Tech Stack:** Kotlin, coroutines (`kotlinx-coroutines-test` virtual time), AndroidX DataStore, Compose for TV, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-15-auto-recompile-design.md`

## Global Constraints

- Engine lives in `:app`, never in the companion modules.
- One `pm compile` at a time; duplicates ignored; the queue has no storage and is rebuilt from staleness after a restart.
- Mode used is the app's stored `LastCompile.mode`; apps never compiled use `CompileMode.SPEED`.
- Stale = no `LastCompile`, or `AppEntry.lastUpdateTime > LastCompile.epochMs`.
- Setting: one switch, off by default, label "Also optimize apps not from a store" (uses `AppEntry.isSideloaded`). No "All apps" mode.
- Triggers: service start, package added/replaced, set changed, every 6 hours. Retry after 30 minutes, at most 3 retries, then wait for the next update or a manual retry.
- Helper down: the worker pauses and the queue holds; resume when `HelperClient.ping()` is non-null.
- History: last 20 jobs (app, time, result) in DataStore.
- Pill labels exactly: "Waiting", "Optimizing…", "Optimized", "Retry". Line text when helper is down: "Waiting for the helper".
- SideSpeed's own package is never maintained. Tests run with `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; Gradle in the foreground, one at a time.

## Review Focus

- An app updates while its own compile is running: it must be compiled again afterwards (so `LastCompile.epochMs` is the job's start time). Pinned in Task 3.
- The helper dies mid-compile: the job is requeued and does not consume a retry. Pinned in Task 3.
- A ticked app is uninstalled (before or while queued): skipped, pruned from the store, no crash. Pinned in Task 3.
- The switch is turned off while sideloaded apps wait: those drop out of the queue; a running job finishes. Pinned in Task 3.
- A burst of package-replaced broadcasts (and SideSpeed updating itself): one job per app, nothing for SideSpeed. Pinned in Tasks 3 and 4.

## File Structure

All new code under `app/src/main/java/io/github/devjardins/sidespeed/maintenance/` (tests mirror it under `app/src/test/java/...`):
- `Staleness.kt`: pure rules (`isStale`, `JobRecord`, `appendHistory`, history codec).
- `MaintenanceStore.kt`: store interface + `DataStoreMaintenanceStore`.
- `MaintenanceEngine.kt`: state, queue, worker, retries.
- `MaintenanceService.kt`, `MaintenanceReceivers.kt`: Android wiring.
Modified: `AppContainer.kt`, `AndroidManifest.xml`, `ui/apps/AppsViewModel.kt`, `ui/apps/AppsScreen.kt`, `ui/compile/CompileScreen.kt`, `MainActivity.kt`.

---

### Task 1: Staleness rules and history codec

**Files:**
- Create: `maintenance/Staleness.kt`
- Test: `maintenance/StalenessTest.kt`

**Interfaces:**
- Produces:
  - `fun isStale(app: AppEntry, last: LastCompile?): Boolean`
  - `data class JobRecord(val pkg: String, val label: String, val epochMs: Long, val success: Boolean, val message: String)`
  - `const val HISTORY_LIMIT = 20`
  - `fun appendHistory(history: List<JobRecord>, record: JobRecord): List<JobRecord>` (newest first, capped at `HISTORY_LIMIT`)
  - `fun encodeHistory(list: List<JobRecord>): String` / `fun decodeHistory(raw: String): List<JobRecord>` (round-trips any text in `label`/`message`, including `|` and newlines; skips malformed lines)

- [ ] **Step 1: Write failing tests** in `StalenessTest`: `neverCompiledIsStale`, `updatedAfterCompileIsStale` (update 2000 vs epochMs 1000), `compiledAfterUpdateIsFresh`, `equalTimesAreFresh`, `appendHistoryKeepsNewest20` (25 appends, size 20, first is the last appended), `historyRoundTripsHostileText` (label `a|b\nc`, message `x|y`), `decodeSkipsMalformedLines`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*StalenessTest'`. Expected: FAIL (unresolved reference).
- [ ] **Step 3: Implement** the signatures above in `Staleness.kt`; encode one record per line, fields percent-escaped (`|`, `\n`, `%`) and joined by `|`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git add app/src && git commit -m "Add staleness rules and job history codec"`

---

### Task 2: MaintenanceStore

**Files:**
- Create: `maintenance/MaintenanceStore.kt`
- Test: `app/src/test/java/.../maintenance/FakeMaintenanceStore.kt` (in-memory, used by Tasks 3, 5)

**Interfaces:**
- Consumes: `JobRecord`, `appendHistory`, `encodeHistory`, `decodeHistory` (Task 1).
- Produces:
```kotlin
interface MaintenanceStore {
    val maintained: Flow<Set<String>>          // ticked packages
    suspend fun setMaintained(pkg: String, on: Boolean)
    val optimizeSideloaded: Flow<Boolean>      // default false
    suspend fun setOptimizeSideloaded(on: Boolean)
    val history: Flow<List<JobRecord>>
    suspend fun addHistory(record: JobRecord)
}
class DataStoreMaintenanceStore(context: Context) : MaintenanceStore  // own DataStore "sidespeed_maintenance"
```

- [ ] **Step 1: Write `FakeMaintenanceStore`** (MutableStateFlows; `addHistory` uses `appendHistory`) and `FakeMaintenanceStoreTest.defaultsAreOffAndEmpty`, `setMaintainedAddsAndRemoves`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*FakeMaintenanceStoreTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the interface and `DataStoreMaintenanceStore` (`stringSetPreferencesKey("maintained")`, `booleanPreferencesKey("optimize_sideloaded")`, `stringPreferencesKey("history")` via the codec). Separate DataStore file so the existing `Prefs` interface and its test fakes stay untouched.
- [ ] **Step 4: Run** the same command. Expected: PASS; also `./gradlew :app:compileDebugKotlin` succeeds.
- [ ] **Step 5: Commit** `git commit -am "Add MaintenanceStore with DataStore implementation"` (add new files first).

---

### Task 3: MaintenanceEngine

**Files:**
- Create: `maintenance/MaintenanceEngine.kt`
- Test: `maintenance/MaintenanceEngineTest.kt`

**Interfaces:**
- Consumes: `AppSource`, `MaintenanceStore`, `Prefs.lastCompile/setLastCompile`, `CompileRunner.run`, `isStale`, `JobRecord` (Tasks 1-2).
- Produces:
```kotlin
enum class JobPhase { WAITING, RUNNING, RETRY }
data class MaintenanceState(
    val maintained: Set<String> = emptySet(),
    val phases: Map<String, JobPhase> = emptyMap(),  // only apps with work or a failure
    val helperDown: Boolean = false,
) { val queueLength: Int }                            // count of WAITING + RUNNING

class MaintenanceEngine(
    source: AppSource, store: MaintenanceStore, prefs: Prefs,
    runCompile: (PackageName, CompileMode) -> Flow<CompileEvent>,  // CompileRunner::run in production
    helperUp: suspend () -> Boolean,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    retryDelayMs: Long = 30 * 60_000L, maxRetries: Int = 3, helperPollMs: Long = 30_000L,
    ownPackage: String,
) {
    val state: StateFlow<MaintenanceState>
    fun scan()               // idempotent: find stale maintained apps, enqueue, prune uninstalled ticked apps
    fun retry(pkg: String)   // manual retry: clears the failure record, enqueues
}
```

- [ ] **Step 1: Write failing tests** (`runTest`, `StandardTestDispatcher`, fakes for source/store/prefs/runner/helper), each with its assertion:
  - `ticked stale app compiles in its stored mode and records LastCompile` (mode SPEED_PROFILE stored → runner called with it; `prefs.setLastCompile` called).
  - `fresh app is not enqueued`.
  - `unticked app is not enqueued; sideloaded app is enqueued only when switch on`.
  - `duplicate scans enqueue once` (three `scan()` calls → runner called once).
  - `jobs run one at a time` (runner flow suspends; second app stays WAITING until first finishes).
  - `own package never enqueued`.
  - `LastCompile epoch is the job start time` (clock 1000 at start, app update at 1500 during the compile, clock 2000 at end → stored epoch 1000, so the next `scan()` re-enqueues).
  - `helper down pauses and resumes` (helperUp false → `helperDown` true, runner not called; flips true → compiles, `helperDown` false).
  - `helper dying mid-compile requeues without using a retry` (runner emits `Finished(false, …, "Connection lost")` and `helperUp` is false after → app back to WAITING, history gets no entry).
  - `failure retries after 30 minutes up to 3 times` (advance virtual time; 4th failure leaves phase RETRY with no timer; history records each failure).
  - `retry() restarts an exhausted app`.
  - `update clears the failure record` (changed `lastUpdateTime` on rescan enqueues at once).
  - `uninstalled ticked app is skipped and pruned` (`source.find` null → not enqueued, `store.maintained` no longer holds it; uninstalled while queued → job skipped without runner call).
  - `switch turned off drops waiting sideloaded apps but not the running one`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*MaintenanceEngineTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `MaintenanceEngine`. Approach: a `Channel`-free design: `LinkedHashSet<String>` guarded by a `Mutex`, one worker coroutine launched in `init` that waits on a `Channel<Unit>(CONFLATED)` wake-up. Maintained set = ticked apps (resolved with `source.find`, null → prune) plus `source.list().filter { it.isSideloaded }` when the switch is on, minus `ownPackage`. Collect `store.maintained`/`optimizeSideloaded` in `init` and call `scan()` on change (that is the "set changed" trigger; waiting apps no longer maintained leave the queue). Failure records live in memory: `Map<String, Failure(updateTime, attempts)>`; `scan()` skips an app whose failure record has the same `lastUpdateTime`, and a per-app delayed coroutine re-enqueues it after `retryDelayMs`. Before each job and after any failed job, check `helperUp()`.
- [ ] **Step 4: Run** the same command. Expected: all PASS.
- [ ] **Step 5: Commit** `git add app/src && git commit -m "Add MaintenanceEngine: serial queue, retries, helper gate"`

---

### Task 4: Service, receivers and wiring

**Files:**
- Create: `maintenance/MaintenanceService.kt`, `maintenance/MaintenanceReceivers.kt`
- Modify: `AppContainer.kt`, `AndroidManifest.xml`, `MainActivity.kt`
- Test: `maintenance/PackageEventsTest.kt`

**Interfaces:**
- Consumes: `MaintenanceEngine.scan()/state` (Task 3).
- Produces:
  - `AppContainer.maintenance: MaintenanceEngine` (lazy; scope `SupervisorJob() + Dispatchers.Default`; `helperUp = { helper.ping() != null }`; `runCompile = compileRunner::run`; `ownPackage = appContext.packageName`) and `AppContainer.maintenanceStore: MaintenanceStore`.
  - `fun packageEventTarget(action: String?, dataSchemeSpecificPart: String?, ownPackage: String, replacing: Boolean): String?` returns the package to rescan, or null to ignore (null for SideSpeed itself, for `ACTION_PACKAGE_ADDED` with `EXTRA_REPLACING` true since `PACKAGE_REPLACED` follows, and for other actions).
  - `MaintenanceService.start(context)` (`startForegroundService`), `BootReceiver` (`BOOT_COMPLETED` → `MaintenanceService.start`).

- [ ] **Step 1: Write failing test** `PackageEventsTest`: `replacedTriggersScan`, `addedTriggersScan`, `addedWhileReplacingIgnored`, `ownPackageIgnored`, `removedIgnored`, `nullDataIgnored`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*PackageEventsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `packageEventTarget`, then the service: `onStartCommand` calls `startForeground` immediately (channel `maintenance`, `IMPORTANCE_MIN`, text from `engine.state`), registers a runtime `BroadcastReceiver` for `PACKAGE_ADDED`/`PACKAGE_REPLACED` (data scheme `package`) that calls `engine.scan()` when `packageEventTarget` is non-null, runs a coroutine `while (true) { engine.scan(); delay(6.hours) }` (this covers "service start" and the safety net), updates the notification as `state` changes, and `stopSelf()` when the maintained set is empty. Manifest: `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`; service `foregroundServiceType="dataSync"`; `BootReceiver` exported=false with a `BOOT_COMPLETED` filter. `MainActivity.onCreate` calls `MaintenanceService.start(this)` when the maintained set is non-empty or the switch is on. Catch `ForegroundServiceStartNotAllowedException` (API 31+) and fall back to running the engine in-process without the service.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest --tests '*PackageEventsTest'` and `./gradlew :app:assembleDebug`. Expected: PASS; build succeeds.
- [ ] **Step 5: Commit** `git add app/src && git commit -m "Add maintenance service, boot receiver and package triggers"`

---

### Task 5: Apps screen state, pills and Maintenance line

**Files:**
- Modify: `ui/apps/AppsViewModel.kt`, `ui/apps/AppsScreen.kt`, `MainActivity.kt`
- Test: `app/src/test/java/.../ui/MaintenanceUiTest.kt`

**Interfaces:**
- Consumes: `MaintenanceState`, `JobPhase`, `JobRecord`, `MaintenanceStore.history` (Tasks 2-3).
- Produces:
  - `fun maintenancePill(pkg: String, state: MaintenanceState): Pill?` : not maintained → null; `RUNNING` → `Pill("Optimizing…", NEUTRAL)`; `WAITING` → `Pill("Waiting", NEUTRAL)`; `RETRY` → `Pill("Retry", WARN)`; maintained with no phase → `Pill("Optimized", OK)`.
  - `fun maintenanceLine(state: MaintenanceState): String?` : null when nothing is maintained and the queue is empty; `"Waiting for the helper"` when `helperDown`; else `"Maintenance: N waiting"` / `"Maintenance: up to date"`.
  - `AppsViewModel` gains constructor arg `maintenance: StateFlow<MaintenanceState>`; `AppsUiState` is unchanged. `AppsScreen` collects it; a maintained row shows `maintenancePill` in place of the dexopt pill; a "Maintenance" line sits under the header; a focusable "History" button opens a list of the last 20 `JobRecord`s (app label, formatted time, result).

- [ ] **Step 1: Write failing tests** in `MaintenanceUiTest`: one per pill label above, `unmaintainedHasNoPill`, `lineWaitingForHelper`, `lineCountsQueue`, `lineHiddenWhenIdle`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*MaintenanceUiTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the two functions in `AppsViewModel.kt` and wire the screen with existing `Ss*` components and D-pad focus rules (History reachable from the tab row). Update `AppsViewModelTest` construction for the new argument.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest`. Expected: whole suite PASS.
- [ ] **Step 5: Commit** `git add app/src && git commit -m "Show maintenance status and history on the Apps screen"`

---

### Task 6: Switch, tick toggles and the Keep-optimized offer

**Files:**
- Modify: `ui/apps/AppsScreen.kt`, `ui/compile/CompileScreen.kt`, `ui/compile/CompileViewModel.kt`, `MainActivity.kt`
- Test: `app/src/test/java/.../ui/CompileViewModelTest.kt`, `ui/MaintenanceUiTest.kt`

**Interfaces:**
- Consumes: `MaintenanceStore.setMaintained/setOptimizeSideloaded/maintained` (Task 2), `MaintenanceService.start` (Task 4).
- Produces:
  - `CompileViewModel` gains optional ctor arg `store: MaintenanceStore? = null` and `fun keepOptimized(pkg: PackageName)` (ticks it; no-op when `store` is null so existing tests stay valid). `CompileUiState` gains `offerKeepOptimized: Boolean` (true after a successful compile when the app is not already maintained).
  - Apps screen: a toggle "Keep optimized" per selected row action, plus a switch row "Also optimize apps not from a store" with the caption "Anything not installed by Google Play or the Amazon Appstore, excluding preinstalled apps." Both start the service when turned on.

- [ ] **Step 1: Write failing tests**: `successOffersKeepOptimizedWhenNotMaintained`, `noOfferWhenAlreadyMaintained`, `failureNeverOffers`, `keepOptimizedTicksPackage` (`FakeMaintenanceStore.maintained` contains it), `keepOptimizedWithoutStoreIsNoOp`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*CompileViewModelTest'`. Expected: new tests FAIL.
- [ ] **Step 3: Implement** the view-model changes, the "Keep this app optimized" button on the success card in `CompileScreen`, and the switch and per-row toggle in `AppsScreen` (calls into `MaintenanceStore`, then `MaintenanceService.start`).
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest`. Expected: whole suite PASS.
- [ ] **Step 5: Commit** `git add app/src && git commit -m "Add the optimize switch, tick toggles and Keep-optimized offer"`

---

### Task 7: Device verification and docs

**Files:**
- Modify: `docs/superpowers/specs/2026-11-15-auto-recompile-design.md` (record the notification decision below), `README.md` (one short section), `HANDOFF.md`

- [ ] **Step 1: Decide the notification, then amend the spec.** The platform requires a visible notification for any foreground service, so "nothing otherwise" cannot hold while the service keeps watching. Plan default: a minimum-importance notification ("Keeping N apps optimized") for as long as anything is maintained, none when the set is empty. Confirm with the owner before building Task 4's text; amend the spec's Notifications paragraph to match.
- [ ] **Step 2: Build and install** `./gradlew :app:assembleDebug`, install on the onn and the Fire TV Cube.
- [ ] **Step 3: Verify on each device** (record results in `HANDOFF.md`): tick one app, update it (`adb install -r`), confirm "Waiting" then "Optimizing…" then "Optimized" without opening SideSpeed; reboot and confirm the service returns; leave the TV idle 15 minutes then update again; kill the helper mid-queue and confirm "Waiting for the helper" then resume once the companion restarts it.
- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest :companion:desktopTest` (use the project's existing full-suite command). Expected: all PASS.
- [ ] **Step 5: Commit** `git add docs README.md HANDOFF.md && git commit -m "Document auto-recompile and record device results"`
