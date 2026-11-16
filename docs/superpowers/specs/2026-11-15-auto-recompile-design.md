# Auto-recompile after updates: design

## Why
`pm compile -m speed` makes sideloaded apps feel fast, but an app update throws the compiled code away. Today the user must notice and tap Compile again. SideSpeed should keep chosen apps compiled by itself.

## Goals
- The user picks apps; SideSpeed keeps them compiled in the mode they chose, after every update.
- One setting, "Optimize all my apps", adds every non-preinstalled app (store or sideloaded) automatically.
- Works unattended on the TV, with no phone or PC involved once the helper is running.
- Never leaves the user guessing: queued, running, done and failed work is visible.

## Non-goals
- Changing other apps' code or APKs. Compiling only.
- A companion (phone/desktop) role in recompiling. The companion keeps the helper alive and nothing more.
- An "All apps" mode. Store apps are left alone, since they ship with Baseline Profiles.

## Constraints
- Compiling needs the helper (shell uid). If it is down, work waits; it is never dropped.
- Compile jobs run one at a time. `pm compile` is heavy on the low-power CPUs these boxes have, and parallel runs would slow each other and the TV's UI. This is a design choice, not a platform limit.
- Spotting an updated app needs no ADB: PackageManager `lastUpdateTime`.
- The mode stored with each app is the mode used (existing `CompileMode`, `LastCompile`).
- Background survival of a foreground service on the onn and the Fire TV Cube is unverified and must be tested on both.

## Approaches considered
1. **Engine in the TV app (chosen).** A boot receiver starts a foreground service. It watches for package changes, queues stale apps, and drains the queue through the helper.
2. Engine in the companion. Only works while a phone or desktop is around; rejected.
3. Both. Two writers could compile the same app at once; rejected.

## Design
**Maintained set.** An app is maintained if the user ticked it (`maintained` flag per package, kept in DataStore), or if the setting "Optimize all my apps" is on and the app is not preinstalled (`!AppEntry.isSystem`). Owner decision 2026-11-16: store apps ship in speed-profile, and speed is better, so they are covered too. Unticking an app removes it unless the setting still covers it. The first manual compile of an app offers "Keep this app optimized", which ticks it.

**Staleness.** A maintained app is stale when it has no `LastCompile`, or its `lastUpdateTime` is newer than `LastCompile.epochMs`, or its current compiler filter (one `dumpsys package dexopt` call per scan) is below its target mode (speed unless the user chose speed-profile). A failed status call falls back to the time rule. Apps that are not installed any more are dropped from the set.

**Triggers.** The service re-checks when: it starts (boot, or app open), a runtime-registered receiver sees package added/replaced, the user changes the maintained set, and every 6 hours as a safety net. Each trigger runs the same "find stale apps, enqueue" step; the step is idempotent.

**Queue.** An in-memory ordered set of package names, one worker coroutine, duplicates ignored. Each job runs `CompileRunner` in the app's stored mode, records `LastCompile` on success, and records the failure reason on failure. Failed apps retry after 30 minutes, at most 3 times, and then wait for the next update or a manual retry. If the helper is unreachable the worker pauses and the queue holds; it resumes when the helper answers a ping. The queue is rebuilt from staleness after a restart, so it needs no storage of its own.

**Settings.** One switch, off by default: "Optimize all my apps". A short line under it names what counts (every app you installed, excluding preinstalled apps).

**Visibility.** Apps screen: a row pill "Waiting", "Optimizing…", "Optimized" or "Retry" for maintained apps. A "Maintenance" line at the top of the screen shows queue length and, when the helper is down, "Waiting for the helper". A short history of the last 20 jobs (app, time, result) is kept in DataStore.

**Notifications.** Android requires a visible notification for any foreground service, so while anything is maintained the service shows one minimum-importance notification ("Keeping N apps optimized", "Optimizing apps (N left)" or "Waiting for the helper"), and nothing, with the service stopped, when nothing is maintained.

## What is left to the builder
Service type and manifest declarations, battery-optimization prompts if the boxes need them, exact retry numbers, and layout of the new Apps-screen elements, following the existing calm theme and D-pad focus rules.

## Risks
- The service may be killed in the background on one of the boxes. The 6-hour re-check and the on-open check bound the damage; the Apps screen always shows the true state.
- A very large queue after a bulk update takes a long time. That is acceptable and visible.
