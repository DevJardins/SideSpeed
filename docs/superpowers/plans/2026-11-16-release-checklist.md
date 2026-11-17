# Release Checklist Plan (branches, first CI run, signing, installers)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Get the three unpushed M2 branches onto GitHub in dependency order, see the first CI run (including the signing step) succeed, prove the release APKs are signed with the owner's key, and smoke-test the never-tested `.msi`, `.deb` and `.rpm`.

**Architecture:** Mostly owner actions with exact commands. One optional code change: CI also runs the `companion-desktop` tests. Nothing is pushed, merged on GitHub, or downloaded without the owner's explicit go-ahead in chat for that step.

**Tech Stack:** git, GitHub Actions (`.github/workflows/build.yml`), `gh` CLI, `apksigner` / `keytool`, jpackage installers.

**Spec:** `HANDOFF.md` (this worktree) "Still open": "Verify signed APKs with `apksigner` after the first CI run; `.msi/.deb/.rpm` untested", "Merge/push `feature/m2-companion` (owner said leave as is for now)"; `docs/superpowers/specs/2026-11-15-sidespeed-companion-design.md` "Build and release".

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse`. Never edit `/Users/mdez/Developer/SideSpeed` (read-only `git -C` queries are fine).
- **Do not push, open PRs, merge on GitHub, or download artifacts without the owner's explicit OK in chat for that step.** Prefer handing the owner the command.
- Do not add attribution lines to git commit messages.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only); `AddressPolicy.sweepable` stays RFC 1918 /16 to /30; overlay addresses are never swept (no code here changes them).
- Never print, paste or commit keystore passwords or the keystore; only the owner runs `keytool` against it.

## Branch facts (from `git` on 2026-11-16, re-check before acting)

| Branch | Tip | Based on | Commits not in its base |
|---|---|---|---|
| `main` (= `origin/main`) | `fce44f9` | — | — |
| `feature/m2-companion` | `919915c` | `main` `fce44f9` | 46 |
| `feature/m2-companion-rename-network` | `fdf56fe` | `feature/m2-companion` `919915c` | 10 (includes the merged `fix/m2-find-tvs-wider-subnets` `6aa8bb3`, and `fdf56fe` "TV app: choose auto-optimize mode") |
| `feature/m2-discovery-muse` | `4b7c5f6` (plus any plan commits since) | `feature/m2-companion-rename-network` at `8422806` | 22; it lacks `fdf56fe` only (`git rev-list --count feature/m2-discovery-muse..feature/m2-companion-rename-network` = 1) |

Remote heads today: `main`, `feature/m0-m1` only. `git merge-tree --write-tree feature/m2-discovery-muse feature/m2-companion-rename-network` exits 0 (no conflict).

**Dependency order:** `main` ← `feature/m2-companion` ← `feature/m2-companion-rename-network` ← `feature/m2-discovery-muse`. Each contains the one before it (once `fdf56fe` is merged into the muse branch).

## Decisions assumed (owner may overrule)

- Recommended: a stack of three PRs merged in dependency order (reviewable in pieces). Alternative: push only `feature/m2-discovery-muse` (after Task 2) and open one PR into `main`; it carries all three.
- `fix/m2-find-tvs-wider-subnets` is not pushed (already merged into the rename branch).
- CI should also run `:companion-desktop:test` (Task 3); today the ubuntu job runs `:shared:allTests :companion:allTests :app:testDebugUnitTest` and packages, but no `companion-desktop` tests.
- **Unverified:** whether GitHub's `ubuntu-latest` image has Android platform 37 installed (`companion-android` has `compileSdk = 37`); AGP normally downloads a missing platform when licences are accepted. Watch the first run for "Failed to find target with hash string 'android-37'".

## Review Focus

1. The signing step silently skipped (secret missing or misnamed): the APK is unsigned, and `apksigner verify` says "DOES NOT VERIFY". Task 6 Step 3.
2. APKs signed, but with a different key than the owner's (wrong secret): certificate SHA-256 mismatch. Task 6 Step 4.
3. A PR from a fork gets no secrets: unsigned by design (workflow comment says so). Note only.
4. The `.deb`/`.rpm` installs but the tray icon does not show (Linux desktop without a tray, e.g. GNOME without the AppIndicator extension). Task 7 records it.
5. Two instances: the second launch must only show the first window (`instance.lock`). Task 7 Step 2.

---

### Task 1: Pre-flight (read only)

- [ ] **Step 1:** `git -C /Users/mdez/Developer/SideSpeed status --short` and `git -C /Users/mdez/Developer/SideSpeed-muse status --short`. Expected: empty in the muse worktree. If the main checkout has uncommitted work, tell the owner: those sessions must commit or stop before any push of their branch.
- [ ] **Step 2:** Re-run the commands behind the "Branch facts" table: `git rev-parse --short main feature/m2-companion feature/m2-companion-rename-network feature/m2-discovery-muse`, `git merge-base feature/m2-companion-rename-network feature/m2-discovery-muse`, `git rev-list --count feature/m2-discovery-muse..feature/m2-companion-rename-network`. If anything differs, update the table in this file before continuing.

### Task 2: Bring `fdf56fe` into the muse branch (local only)

- [ ] **Step 1:** With the owner's OK, in `/Users/mdez/Developer/SideSpeed-muse`: `git merge --no-ff feature/m2-companion-rename-network -m "Merge feature/m2-companion-rename-network (auto-optimize mode) into feature/m2-discovery-muse"`. Expected: merge succeeds without conflicts.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --console=plain`, then `./gradlew :companion:desktopTest :companion-desktop:test --console=plain`, then `./gradlew :companion-android:assembleDebug --console=plain`. Expected: each BUILD SUCCESSFUL.

### Task 3 (optional, owner decides): CI runs the desktop tests

**Files:** Modify `.github/workflows/build.yml` (the ubuntu job's `./gradlew …` line).

- [ ] **Step 1:** Insert `:companion-desktop:test` after `:app:testDebugUnitTest` in that line.
- [ ] **Step 2:** Check: `grep -c ":companion-desktop:test" .github/workflows/build.yml` — Expected: `1`. Run the same task locally: `./gradlew :companion-desktop:test --console=plain` — Expected: BUILD SUCCESSFUL (the live tests skip without `-Psidespeed.live`).
- [ ] **Step 3:** Commit on the earliest branch the owner chooses (recommended `feature/m2-companion`, so every later branch inherits it; then merge forward): `git commit -m "CI: run the desktop companion tests"`.

### Task 4: Push in dependency order (owner)

Hand the owner these, one at a time, waiting for CI between them (each push triggers `on: push`):

- [ ] **Step 1:** `git push -u origin feature/m2-companion` → open PR into `main` (`gh pr create --base main --head feature/m2-companion --title "M2: SideSpeed Companion and auto-recompile"`).
- [ ] **Step 2:** After Step 1's CI is green: `git push -u origin feature/m2-companion-rename-network` → PR with `--base feature/m2-companion`.
- [ ] **Step 3:** After Step 2's CI is green: `git push -u origin feature/m2-discovery-muse` → PR with `--base feature/m2-companion-rename-network`.
- [ ] **Step 4:** Merge on GitHub in the same order; after each merge, retarget the next PR to `main` (`gh pr edit <n> --base main`).

### Task 5: Watch the first CI run

- [ ] **Step 1:** `gh run list --branch feature/m2-companion --limit 3` then `gh run watch <run-id>`. Expected: jobs `ubuntu`, `macos`, `windows` all succeed.
- [ ] **Step 2:** `gh run view <run-id> --log --job <ubuntu-job-id> | grep -A3 "Decode release keystore"`. Expected: the step ran with no error. Secrets show as `***`; never copy log lines containing them anywhere.
- [ ] **Step 3:** If the ubuntu job fails on Android platform 37 (see Decisions), report the exact error line to the owner; the likely fix is an `sdkmanager "platforms;android-37.0"` step, but do not add it without the owner's OK.

### Task 6: Verify the signed APKs

- [ ] **Step 1:** Ask before downloading (state: artifact `ubuntu` from run `<run-id>`, size from `gh run view <run-id>`). Then `gh run download <run-id> -n ubuntu -D <scratchpad>/ci-ubuntu`.
- [ ] **Step 2:** `ls <scratchpad>/ci-ubuntu/app/build/outputs/apk/release/ <scratchpad>/ci-ubuntu/companion-android/build/outputs/apk/release/` (artifact paths keep the workflow's `path:` layout; adjust if `gh` flattens them).
- [ ] **Step 3:** For each APK: `$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs <apk>`. Expected: `Verifies`, `Verified using v2 scheme (APK Signature Scheme v2): true`, one signer, and a `Signer #1 certificate SHA-256 digest:` line. A file named `*-unsigned.apk` or "DOES NOT VERIFY" means the secrets were not applied.
- [ ] **Step 4:** Owner runs `keytool -list -v -keystore <their release.jks> -alias <their alias>` and compares its `SHA256:` fingerprint (colons removed, lower-case) with Step 3's digest for both APKs. Expected: equal, and the same for `:app` and `:companion-android`.
- [ ] **Step 5:** Record "APK signing verified on run <id>, cert SHA-256 <first 8 hex>…" in `HANDOFF.md`; commit `git commit -m "Record first CI run and APK signing check"`.

### Task 7: Smoke-test the desktop installers (owner, per OS)

For each of `.dmg` (artifact `macos`), `.msi` (`windows`), `.deb` and `.rpm` (`ubuntu`), on a matching machine or VM:

- [ ] **Step 1: Install.** macOS: open the `.dmg`, drag to Applications, first launch with right-click > Open (unsigned). Windows: run the `.msi` (SmartScreen "More info" > "Run anyway"). Ubuntu/Debian: `sudo apt install ./<file>.deb`. Fedora: `sudo dnf install ./<file>.rpm`.
- [ ] **Step 2: Run.** The window opens on "Choose your Trusted Network"; the tray icon appears with "Restart now", "Open SideSpeed Companion", "Pause checks", "Quit". Launch it a second time: only the first window comes forward.
- [ ] **Step 3: Store.** "Trust this network", then quit; the store exists at macOS `~/Library/Application Support/SideSpeed Companion/store/companion.json`, Windows `%APPDATA%\SideSpeed Companion\store\companion.json`, Linux `${XDG_DATA_HOME:-~/.local/share}/sidespeed-companion/store/companion.json` (paths from `DesktopDirs.appData`).
- [ ] **Step 4: Autostart.** Settings > "Start when I log in" on, log out and in: the app starts. Turn it off: it no longer starts.
- [ ] **Step 5: Tailscale (if installed on that machine).** Settings shows "Trusted tailnets" (the CLI is found at the paths in `TailscaleCli.defaultCandidates`).
- [ ] **Step 6: Uninstall** cleanly (Windows: Apps & features; Linux: `sudo apt remove sidespeed-companion` / `sudo dnf remove sidespeed-companion`; the package name comes from `linux { packageName = "sidespeed-companion" }`).
- [ ] **Step 7:** Record one line per format in `HANDOFF.md` (pass/fail and anything odd, such as no tray on GNOME); commit `git commit -m "Record desktop installer smoke tests"`.
