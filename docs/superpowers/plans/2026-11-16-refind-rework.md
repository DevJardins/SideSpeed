# Re-find Rework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatic re-find works on any Trusted Network Find TVs can sweep (/16 to /30, a bigger LAN shrinks to the /16 around the TV), not only a /24, and it uses the one-packet ADB probe so only ADB-speaking hosts are opened.

**Architecture:** Today `Upkeep.refind` (`cm/upkeep/Upkeep.kt`) returns NotFound unless the Trusted Network's subnet ends with `/24` (dead on the owner's /22, per `MUSE_TASK.md`), then TCP-connect scans with `TcpScanner` and opens every host that answers. After this plan: `refind` computes `sweepScope(subnet, tvAddress, gateway)` and asks the `Scanner` for hosts near the TV's last address; production uses a new `ProbeScanner` that runs `Discovery(listOf(SweepSource), probe)` (CNXN probe, two passes, 4096 cap, order outward from the TV's last address) and returns only `AdbAuthRequired`/`AdbOpen` hosts. Every gate check, the prompted-host skip and the serial match stay exactly as they are.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines-test, `FakeLan`.

**Spec:** `docs/superpowers/specs/2026-11-15-sidespeed-companion-design.md` "Automatic re-find (owner decision 3 = option a)"; `HANDOFF.md` rules ("Verifier sends one raw ADB CNXN…", "Sweep only a Trusted Network's own subnet; RFC1918 only; port 5555 only"); `docs/design/overlay-networks.md` section 2 (Upkeep) and 5.2 (overlay TVs are never re-found).

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `cm/` = `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/`, `ct/` = `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/`, `desk/` = `companion-desktop/src/main/kotlin/io/github/devjardins/sidespeed/companion/desktop/`, `and/` = `companion-android/src/main/kotlin/io/github/devjardins/sidespeed/companion/android/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses are never swept: re-find never runs for overlay TVs (already true in `Upkeep.checkOverlay`) and `ProbeScanner` never includes `TailscalePeersSource`.
- commonTest cannot use `runBlocking`.

## Decisions assumed (owner may overrule)

- Scope of a re-find = `sweepScope(trustedSubnet, own = tvLastAddress, gateway)`: the Trusted Network's subnet if /16 or smaller, else the /16 around the TV's last address. Capped at 4096 probes by `DiscoveryPlan.limit` (`SweepOrder.DEFAULT_LIMIT`).
- Order: the TV's last /24 first, then outward from its last address (passed to `DiscoveryPlan` as `gateway`, not `own`, so that address itself is probed too).
- TLS-only answers (`AdbTls`) are not opened (Kadb here speaks classic ADB only).
- `TcpScanner` stays in the code base (its `desktopTest` `ScannerTest` keeps passing) but is no longer wired in production.

## Review Focus

1. A Trusted Network saved with a /8 (office): scans the /16 around the TV, never the /8. Test in Task 2.
2. A scanner result outside the scope, or the scope's network/broadcast address, or non-IPv4 text: ignored (existing `scannerResultsOutsideTheSubnetAreIgnored` must stay green unchanged). Task 2.
3. A Trusted Network saved with a public or 100.64/10 subnet (corrupt store): no scan (existing `refindNeverScansASubnetOutsidePrivateSpace` stays green). Task 2.
4. Cancellation mid-scan (app quit): the `Discovery` flow is cancelled with the run; nothing is opened after. Covered by `Discovery` cancellation tests; `ProbeScanner` adds no buffering. Task 1.
5. A foreign ADB device that prompted once is skipped for 24 h, also with the new scanner (prompted-host logic is untouched). Existing `promptedForeignHostIsSkippedUntilUserActionOrExpiry` stays green. Task 2.

---

### Task 1: `Scanner.scan(..., near)` and `ProbeScanner`

**Files:**
- Modify: `cm/upkeep/Scanner.kt` (interface `Scanner`, class `TcpScanner`)
- Create: `cm/upkeep/ProbeScanner.kt`
- Test: create `ct/upkeep/ProbeScannerTest.kt`; modify `ct/upkeep/UpkeepTest.kt` (`FakeScanner` only)

**Interfaces:**
- Produces:
  - `interface Scanner { suspend fun scan(subnet: String, port: Int = 5555, near: String? = null): List<String> }` — KDoc: "ADB hosts in [subnet] answering on [port]; [near], a host in [subnet], is scanned around first."
  - `TcpScanner.scan(subnet: String, port: Int, near: String?)` ignores `near` (behaviour unchanged).
  - `class ProbeScanner(private val probe: AdbProbe, private val dispatcher: CoroutineDispatcher? = null) : Scanner`.
- `UpkeepTest.FakeScanner` gets `val nears = mutableListOf<String?>()` and the new parameter; its `calls` strings stay `"$subnet:$port"` so existing assertions stay valid.

- [ ] **Step 1: Failing tests** in `ProbeScannerTest` (use `FakeLan` from `ct/discovery/FakeLan.kt`; it is `internal` in the same test source set):
  - `returnsOnlyClassicAdbHosts`: script `192.168.5.20 → AdbAuthRequired`, `.21 → AdbOpen`, `.22 → AdbTls`, `.23 → NotAdb` → `ProbeScanner(lan).scan("192.168.5.0/24", 5555, null)` as a set equals `{"192.168.5.20", "192.168.5.21"}`.
  - `startsNearTheGivenAddress`: subnet `192.168.4.0/22`, `near = "192.168.6.50"` → `lan.calls.first().address.toString().startsWith("192.168.6.")`.
  - `neverProbesOutsideTheSubnetOrOffPort`: `scan("100.64.0.0/16")` → empty and `lan.calls` empty; `scan("192.168.5.0/24", port = 22)` → empty and no calls; `scan("garbage")` → empty.
  - `aNearOutsideTheSubnetIsIgnored`: `near = "10.0.0.1"` on `192.168.5.0/24` → non-empty calls, all inside `192.168.5.0/24`.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*ProbeScannerTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement.** Change the interface and `TcpScanner`'s override signature; update `FakeScanner`. `ProbeScanner.scan`: return empty unless `port == 5555` and `Cidr.parse(subnet)` is non-null; `anchor = near?.let(Ipv4::parse)?.takeIf { it in cidr }`; collect `Discovery(listOf(SweepSource), probe, dispatcher).run(DiscoveryPlan(cidr, own = null, gateway = anchor))`, keeping `Found` events whose `result` is `AdbAuthRequired` or `AdbOpen`, as dotted strings in arrival order.
- [ ] **Step 4:** Run `./gradlew :companion:desktopTest --console=plain` — Expected: BUILD SUCCESSFUL (the new class and every existing test, including `UpkeepTest` and the desktopTest `ScannerTest`).
- [ ] **Step 5:** Commit `git commit -m "Upkeep: ProbeScanner finds ADB hosts with the one-packet probe, starting near a given address"`.

### Task 2: `Upkeep.refind` over the whole Trusted Network

**Files:**
- Modify: `cm/upkeep/Upkeep.kt` (`refind`, remove `inSubnet`, update the class KDoc's re-find paragraph)
- Test: `ct/upkeep/UpkeepTest.kt`

- [ ] **Step 1: Failing tests** (`wide = NetworkFingerprint("192.168.4.1", "192.168.4.0/22", setOf("192.168.4.1"))`, `wideNet = TrustedNetwork("home", "Trusted Network 1", wide)`, source `FingerprintSource { wide }`):
  - `refindCoversASlash22TrustedNetwork`: TV `tv("a", "192.168.5.50").copy(offlineCount = 2)`; `FakeScanner(listOf("192.168.6.77"))`; `net.sessions["192.168.6.77"] = runningTv("SER-a")`; one `BACKGROUND` run → `scanner.calls == listOf("192.168.4.0/22:5555")`, `scanner.nears == listOf("192.168.5.50")`, stored `address == "192.168.6.77"`, `status == RUNNING`.
  - `refindOnASlash8ScansTheSlash16AroundTheTv`: fingerprint `10.0.0.1`, `10.0.0.0/8`; TV at `10.20.30.40`, `offlineCount = 2` → `scanner.calls == listOf("10.20.0.0/16:5555")`.
  - `refindDropsTheScopesNetworkAndBroadcast`: on `wide`, scanner returns `["192.168.4.0", "192.168.7.255", "192.168.8.1"]` → only the TV's own address was opened (`net.opened == listOf("192.168.5.50")`).
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*UpkeepTest*' --console=plain` — Expected: the three new tests FAIL (no scan on a /22, /8).
- [ ] **Step 3: Implement** in `refind`: `subnet = Cidr.parse(trusted.fingerprint.subnet) ?: NotFound`; `scope = sweepScope(subnet, Ipv4.parse(tv.address), Ipv4.parse(trusted.fingerprint.gatewayIp))`; `if (!AddressPolicy.sweepable(scope)) NotFound`; `hosts = scanner.scan(scope.toString(), TvConnector.DEFAULT_PORT, near = tv.address).filter { h -> Ipv4.parse(h)?.let { it in scope && it != scope.network && it != scope.broadcast } == true }.distinct()`. The loop after it (prompted skip, `gateOpen` before each connect, serial read) is unchanged.
- [ ] **Step 4:** Re-run — Expected: all `UpkeepTest` and `OverlayUpkeepTest` cases PASS.
- [ ] **Step 5:** Commit `git commit -m "Upkeep: re-find covers the whole Trusted Network, not only a /24"`.

### Task 3: Wire `ProbeScanner` in both apps

**Files:**
- Modify: `desk/Main.kt` (line 72 today: `val scanner = TcpScanner()`)
- Modify: `and/CompanionApplication.kt` (line 36 today: `val scanner: Scanner = TcpScanner()`)

- [ ] **Step 1:** Desktop: `val scanner = ProbeScanner(SocketAdbProbe(), Dispatchers.IO.limitedParallelism(Discovery.PARALLELISM))` (opt in to `ExperimentalCoroutinesApi` as the `discovery` line does). Android: same in `Graph`. Remove the now-unused `TcpScanner` imports.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest :companion-desktop:test --console=plain`, then `./gradlew :companion-android:assembleDebug --console=plain` — Expected: both BUILD SUCCESSFUL.
- [ ] **Step 3 (owner, with OK):** Optional live check on the owner's /22 with the Cube: needs the Cube to change address (DHCP reservation change). Only if the owner offers; otherwise record "re-find not device-tested".
- [ ] **Step 4:** Commit `git commit -m "Companion: use the probe-based scanner for re-find"`.
