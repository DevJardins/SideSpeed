# macOS Local Network Usage String Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The packaged macOS app carries `NSLocalNetworkUsageDescription`, so macOS 15+ shows a clear Local Network prompt the first time the companion reaches a TV on the LAN.

**Architecture:** One Gradle DSL change in `companion-desktop/build.gradle.kts`: `compose.desktop.application.nativeDistributions.macOS.infoPlist.extraKeysRawXml`. Verified to exist in the Compose Gradle plugin 1.12.2 used here (`javap` on `compose-gradle-plugin-1.12.2.jar`: `JvmMacOSPlatformSettings.infoPlist(Action<InfoPlistSettings>)`, `InfoPlistSettings.setExtraKeysRawXml(String)`). Then probe P8 with the owner.

**Tech Stack:** Gradle Kotlin DSL, Compose Multiplatform desktop packaging (jpackage), `plutil`.

**Spec:** `docs/design/overlay-networks.md` section 6 "Permissions and prompts" (macOS bullet) and probe P8.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses are never swept.

## Decisions assumed (owner may overrule)

- Copy: "SideSpeed Companion checks your TVs on your Trusted Networks." (one sentence, no "may").
- No `NSBonjourServices` key: the companion does not use Bonjour APIs (plan "discovery-sources" sends raw UDP). Whether macOS wants it for raw mDNS sends is **unverified**; Task 2 Step 4 checks.
- Signing identity is out of scope. Apple tracks Local Network permission by code signature (TN3179, design source 13); the jpackage build is not signed with a stable identity, so a rebuilt app may prompt again. Note it, do not fix it here.

## Review Focus

1. The key lands in the `.app`'s `Contents/Info.plist`, not only in a Gradle model: verified by `plutil` in Task 1.
2. XML special characters in the copy would break the plist: the copy has none; `plutil -lint` in Task 1 catches a mistake.
3. Running from `./gradlew run` or Terminal hides the prompt (Terminal tools are exempt): Task 2 opens the packaged app with `open`.
4. A 100.x (tailnet) connection alone should not prompt (design: VPN is not "local"): Task 2 Step 3.
5. Denying the prompt: Find TVs ends with nothing found; the copy for `OS_BLOCKED` exists but is not wired (platform sources pass `osBlocked = false`). Record the observed diagnosis in Task 2; no code change here.

---

### Task 1: Add the usage string

**Files:**
- Modify: `companion-desktop/build.gradle.kts` (the `macOS { … }` block inside `nativeDistributions`)

- [ ] **Step 1: Check it is absent (the failing check).** Run `./gradlew :companion-desktop:createDistributable --console=plain` then `plutil -p "companion-desktop/build/compose/binaries/main/app/SideSpeed Companion.app/Contents/Info.plist" | grep -c NSLocalNetworkUsageDescription` — Expected: `0`.
- [ ] **Step 2:** Inside `macOS { … }` add:

```kotlin
infoPlist {
    extraKeysRawXml = """
        <key>NSLocalNetworkUsageDescription</key>
        <string>SideSpeed Companion checks your TVs on your Trusted Networks.</string>
    """.trimIndent()
}
```

- [ ] **Step 3:** Run `./gradlew :companion-desktop:createDistributable --console=plain`, then `plutil -lint "companion-desktop/build/compose/binaries/main/app/SideSpeed Companion.app/Contents/Info.plist"` — Expected: `OK`; and `plutil -p "…/Info.plist" | grep NSLocalNetworkUsageDescription` — Expected: `"NSLocalNetworkUsageDescription" => "SideSpeed Companion checks your TVs on your Trusted Networks."`.
- [ ] **Step 4:** Commit `git commit -m "Desktop: Local Network usage string in the macOS app"` (add `companion-desktop/build.gradle.kts`).

### Task 2: Probe P8 (owner)

- [ ] **Step 1:** Ask the owner for a go-ahead. In System Settings > Privacy & Security > Local Network, note whether "SideSpeed Companion" is listed and its state (to compare).
- [ ] **Step 2:** Quit any running copy, then `open "companion-desktop/build/compose/binaries/main/app/SideSpeed Companion.app"`. At home, "Add a TV" > "Find TVs". Expected: macOS shows the Local Network alert with the Task 1 copy. Owner allows.
- [ ] **Step 3:** If the owner can reset the permission (toggle off in that Settings pane), turn it off, quit, relaunch, and with only a trusted tailnet TV saved press "Restart now" on it while Find TVs is not used. Expected (design): no alert for a 100.x connection over Tailscale.
- [ ] **Step 4:** If plan "discovery-sources" is merged, press Find TVs once more and note whether mDNS/SSDP hints appear (Cube found before "Scanned 300"). If not, record "mDNS/SSDP may need NSBonjourServices or the multicast entitlement: unverified".
- [ ] **Step 5:** Record P8 results in `HANDOFF.md` (this worktree) and in `docs/design/overlay-networks.md` section 9 after P8; commit `git commit -m "Record probe P8 (macOS Local Network prompt)"`.
