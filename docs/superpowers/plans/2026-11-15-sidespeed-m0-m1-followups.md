# SideSpeed M0+M1 — rulings and deferred follow-ups

Recorded from the build ledger when the M0+M1 branch finished (2026-11-15).

## Open before merge
- ~~The full Google TV run on the onn 4K Pro has not been verified on a device~~ Verified 2026-11-15 (helper auto-start, compile, badge, update restart) after the `trap "" HUP` launch fix; in-app TLS pairing with 5555 closed also verified by the owner the same day.: in-app pairing, the helper auto-starting through SideSpeed's own ADB (newline fix 3b92814), compile and badge, and "needs restart" after an update. Record the results in docs/spike/M0-results.md under "M1 verification".
- HiddenApiBypass: pairing and TLS worked on the onn (Android 14) without calling it. Consider removing the unused dependency and its NOTICE entry.
- Signed release with the owner's keystore (Task 11 Step 2).

## Rulings made during execution
- | T1 self | SmokeTest asserts `true` | rubric treats empty test as defect | Ruling: allow in T1 (needed to prove test task runs); T2 deletes it — cost if wrong: trivial |
- | T3→T5/T6/T8/T9 | FakeAdbBackend | does fake see wrapped or raw commands? | Ruling: fake works on raw (unwrapped) commands; wrapping lives only in LibAdbBackend — cost: fake/real mismatch caught on device |
- | T4 vs T8 | PairingCodeGrid | spike uses it before T8 defines it | Ruling: spike has its own throwaway inline grid in debug sources — cost: small duplication, deleted in T10 |
- | T4 self | Step 5 hand-off vs T5–7 | plan allows T5–7 during wait | Ruling: run T1–T7, then stop for owner hand-off before T8 — cost: none |
- | T5 self | Step 5 needs device captures | unavailable until hand-off | Ruling: T5 completes without Step 5; Step 5 done after hand-off as separate commit |
- | T8 self | Prefs: Produces says class, Step 1 says interface | contradiction | Ruling: `interface Prefs` + `DataStorePrefs(context)` impl; T9 extends the interface — cost: none |
- | T7→T9 | InstalledApps vs AppSource | T9 wants interface | Ruling: T9 adds `interface AppSource { fun list(); fun find(pkg) }`, InstalledApps implements it — cost: none |
- Task 1: implementer DONE_WITH_CONCERNS (9859f82). Ruling: accept Compose BOM 2026.06.01 + navigation-compose 2.9.8 (newer need compileSdk 37; spec pins 36) — cost if wrong: trivial version bump later.
- Task 1: Ruling: SmokeTest stays in T1, Task 2 deletes it (already in pre-flight) — cost: none.
- Ruling: plan amended with Task 4b (helper spike) before Task 8; production helper tasks and revised Task 8 to be planned after M0/M0b results — cost if wrong: one more planning pass.
- Task 8: Ruling: carry the Important into Task 8b scope — helper accepts `ping <nonce>` (nonce [A-Za-z0-9]{16,64}), writes nonce to /data/local/tmp/sidespeed_helper.ack (0644, atomic) before replying; HelperClient sends a fresh nonce and verifies the ack file — cost if wrong: one more small helper change. Also fold minor #4 (zombie: destroy+waitFor in finally) into 8b.
- Ruling: plan's Task 10/11 say "old Task 9/10 text applies" but that text was removed in 85fe94d — controller appends old text from 69cc622 into the briefs — cost: none.
- Task 10: Ruling: while a compile runs, Back is blocked with an on-screen note ("A compile is running — please wait until it finishes"), because cancelling the client does not stop pm compile on the helper; on entering Compile when not running, vm.reset(pkg) — cost if wrong: users wait on long compiles (≈3 min Fire TV).
- Task 11: controller verified — libadb COPYING: "GPL-3.0-or-later or Apache-2.0" (earlier research); sun-security-android: GPL-2.0-only with Oracle Classpath Exception on files carrying the designation (README + LICENSE + AVA.java header). Ruling: CPE permits linking from GPLv3 app; NOTICE states it precisely; flag to owner as a licensing note — cost if wrong: swap cert generation to BouncyCastle (already a dependency).
- Ruling: one fix wave covers Critical 1, Important 2/4/5, Minors 6–11; Issue 12 (AdbIdentity atomic/corrupt) deferred to M2 diagnostics; HiddenApiBypass question and Onn M1 verification (Issue 3) done by controller+owner on device after the fix wave — cost if wrong: Onn path issues found later.

## Deferred minors (triaged by the final review as safe to defer)
- Task 1: minor (deferred): no distributionSha256Sum in gradle-wrapper.properties
- Task 1: minor (deferred): adaptive icon lacks monochrome layer
- Task 1: minor (deferred): "Unable to strip libraries" build noise (jniLibs keepDebugSymbols)
- Task 1: minor (deferred): release signing/minify not configured yet (Task 10 owns it)
- Task 1: minor (deferred): lintDebug fails on QueryAllPackagesPermission — add tools:ignore (permission is required)
- Task 1: minor (deferred): legacy icon layer-list has no inset (full-bleed square)
- Task 2: minor (deferred): AdbIdentity non-atomic writes; corrupt file → permanent load failure (consider regenerate-on-failure)
- Task 2: minor (deferred): missing/corrupt-file behaviour untested
- Task 2: minor (deferred): app-private dir relies on caller (AppContainer must pass filesDir)
- Task 2: minor (deferred): loadOrCreate not synchronized
- Task 3: minor (deferred): openService readText not cancellable (ok for tcpip)
- Task 3: minor (deferred): fake shell succeeds when not connected; fake not thread-safe; dropAfterLines keeps unused exit code
- Task 4: minor (deferred): 20 s reconnect can't interrupt blocking connect; compile duration not logged; dexopt.txt written on Main; Wi-Fi IP may be VPN/Ethernet; runbook assumes Android 14; tcpip step lacks follow-up check; long button labels
- Task 4: minor (deferred): persisted Classic target collapses to 127.0.0.1:5555; stray blank line in runbook
- Task 5: minor (deferred, PRIORITY for final review — Review Focus #4): unparseable ABI status line silently dropped → sibling speed could give false "speed ✓"; harden to return null
- Task 5: minor (deferred): last block runs to end of input in pkg fallback; samples hand-written (Step 5 verifies); missing CRLF/location-line/k-only tests; fallback exit code ignored
- Task 6: minor (deferred): no test for flow completing without Exit (misnamed test); PackageName newline/empty-segment tests; speedProfileMode assertion loose
- Task 7: minor (deferred): redundant asserts; list() binder cost — caller must use IO (Task 9); implicit disabled-app exclusion uncommented; no list()/find() tests; non-locale sort
- Task 5b: minor (deferred): Key Set block bleeds to Dexopt header (stop at lower-indent line); unresolved line stops search; missing decoy/multi-split/secondary-dex tests
- Task 8: minor (deferred): pre-auth slot starvation (client retries with backoff in 8b); port-reuse race re-check; uid TOCTOU note; per-read timeout; refusal log flood rate-limit; delete HelperSpikeMain (Task 11)
- Task 8b: minor (deferred): @InternalCoroutinesApi onCancelling (public watcher alternative); shared ack file → single client instance in AppContainer (Task 9); ping clears verifiedPort early; interrupt flag swallowed; uid unused in HelperPing; __END__ in output
- Task 8b: minor (deferred): connect SocketTimeout now retried (harmless); immediate-close tests need <300 ms on loaded machine; no explicit cancel-vs-window test
- Task 9: minor (deferred): wifiIp/pcStartCommands in composition; Windows quoting; ready rememberSaveable — no return to Setup when helper dies (Task 10 "Check helper" covers)
- Task 9: minor (deferred): port non-numeric/range tests; WaitingForAllowPrompt mapping test; 500 ms delay untested; outer-cancel test; focus untested on device
- Task 10: minor (deferred): log D-pad scroll; typed connectionLost flag; formatElapsed test; tab focus traversal unverified
- Task 10: minor (deferred): reset(pkg) param unused
- Task 11: minor (deferred): broad BC/Conscrypt keeps (APK size); README 30-60 MB unverified (from SPEC)

## Device findings
- M0b Fire TV results (controller, 2026-11-15):
- M0b Fire TV: helper killed instantly when ADB switched off (no heartbeat after); exits on APK update (15 s). Results committed.
- Device verify (controller, release APK signed with local debug key, Fire TV): app launches, warning screen, helper (started from Mac) found → Apps list; Manual entry com.nuvio.tv → Compile screen; compile running with timer, wait note, log.
- FINDING (for final review fix wave): Sideloaded tab lists Amazon system apps (null installer) — isSideloaded must exclude FLAG_SYSTEM/FLAG_UPDATED_SYSTEM_APP.
- FINDING (minor): Apps screen has no initial D-pad focus (first key press establishes focus).
- Device verify Fire TV: compile Success, badge 'speed ✓', dumpsys agrees. FINDING (minor): banner shows 'Success: Success' (message duplicates label).

- Flaky test: HelperClientTest.serverClosingBeforeEndThrows failed once under load (`expected [Line(partial)] but was []`), then passed 3/3 reruns. Harden the fake server's write/close ordering.
