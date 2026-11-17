# Overlay Phase 3 Implementation Plan (D3, D4, D5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On desktop, let the user add a TV by its tailnet name (D5) and keep alive a TV reached through a Tailscale subnet router (D4), without weakening the Phase 2 gate.

**Architecture:** Both parts extend the Phase 2 overlay model. `OverlayPeer` gains `dnsName` and `routes` read from `tailscale status --json`. Part A resolves a typed name against the **trusted tailnet's peer list only** (never system DNS) and then follows the existing 100.x add path. Part B binds an RFC 1918 TV to the router peer whose `PrimaryRoutes` cover it; the overlay gate then also requires that the current physical subnet does not contain the address.

**Tech Stack:** Kotlin Multiplatform (commonMain/commonTest), kotlinx-coroutines-test, `MiniJson` parser.

**Spec:** `docs/design/overlay-networks.md` sections 5.2, 5.3 (subnet-router collision row), 6 table, 7 "Phase 3", 8 (D2 to D6).

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `cm/` = `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/`, `ct/` = `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it after Plan "finish-overlay-phase2"). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses (100.64.0.0/10) are never swept; routed (Part B) TVs are never re-found by a scan either.
- commonTest cannot use `runBlocking`.
- Names typed by the user are never passed to a process, to DNS, or to `InetAddress`.
- **Do not start a Part whose decision the owner has not confirmed in chat.**

## Decisions assumed (owner may overrule)

- **D2:** Tailscale/Headscale is the only adapter. No NetBird task here.
- **D3:** remote upkeep stays desktop only. No Android overlay trust task here (it would contradict "Android companion passes no overlay access"). If the owner chooses Android overlay trust, a separate plan is needed.
- **D4 (no recommendation in the design; assumed YES for planning, desktop only):** Part B. A routed TV is bound to the router peer's node ID, its address must be inside one of that peer's `PrimaryRoutes`, egress must leave through the tailnet interface, and the address must not be inside the current physical subnet. Adding a routed TV happens only while the computer is **not** on a Trusted Network (at home the same address is a LAN TV, as today); to have both, add it twice (design 5.2).
- **D5 (no recommendation; assumed YES, desktop only):** Part A. A name resolves only through the trusted tailnet's peer list: a bare host label matching `HostName` or the first label of `DNSName`, or the full MagicDNS name (trailing dot optional). Ambiguous names (two peers) and shared peers are refused.
- **D6:** no IPv6. `OverlayPeer.ips` stays IPv4 only.
- **Unverified:** that `PrimaryRoutes` in `tailscale status --json` lists a router's approved subnet routes as CIDR strings (design section 3 says so, marked unchecked). Task B1 Step 0 checks it on this Mac before coding.

## Review Focus

1. A name that is a valid IPv4 literal ("100.101.1.1") must keep the existing literal path, never the name path. Test in Task A3.
2. Two routers advertising the same subnet: refuse to bind (ambiguous). Test in Task B2.
3. Exit node peer advertising 0.0.0.0/0 (or a /8): must never bind a TV (routes are kept only when wholly RFC 1918 with prefix /16 to /32). Test in Task B1.
4. Hotel LAN using the home subnet with Tailscale up: gate closed (physical subnet contains the address). Test in Task B3.
5. A routed TV whose router goes offline or is removed from the tailnet: gate closed, counted as unreachable, never re-found. Test in Task B3.

---

## Part A: tailnet names in manual add (D5)

### Task A1: `OverlayPeer.dnsName` from the status JSON

**Files:**
- Modify: `cm/net/TrustedOverlay.kt` (`OverlayPeer`)
- Modify: `cm/discovery/TailscaleStatus.kt` (`snapshot`)
- Test: `ct/discovery/TailscaleStatusTest.kt`

**Interfaces:**
- Produces: `OverlayPeer.dnsName: String? = null` (new last parameter; lower-case, no trailing dot).

- [ ] **Step 1: Failing test** `snapshotKeepsThePeersMagicDnsName`: a fixture with one peer `"DNSName": "Cube.tail1234.ts.net."` gives `dnsName == "cube.tail1234.ts.net"`; a peer without `DNSName` gives null; an empty string gives null.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*TailscaleStatusTest*' --console=plain` — Expected: compile FAIL on `dnsName`.
- [ ] **Step 3:** Add the field with default `null` (existing positional constructors in tests keep compiling); in `snapshot` read `m["DNSName"] as? String`, trim one trailing `.`, lowercase, empty to null.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Overlay: keep each peer's MagicDNS name"` (add the three files).

### Task A2: `OverlayNames.resolve`

**Files:**
- Create: `cm/net/OverlayNames.kt`
- Test: `ct/net/OverlayNamesTest.kt`

**Interfaces:**
- Produces: `object OverlayNames { fun resolve(name: String, snapshot: OverlaySnapshot): OverlayPeer? }` returns the single non-shared peer the name designates, or null.

- [ ] **Step 1: Failing tests** (`snapshot` built inline with peers `cube` (`dnsName = "cube.tail1.ts.net"`, ips `100.101.1.1`), `onn` (`dnsName = "onn.tail1.ts.net"`, ips `100.123.103.8`), shared peer `guest` and two peers both with hostName `"tv"`):
  - `resolve("cube", s)?.id == "nCUBE"`; `resolve("CUBE", s)` same; `resolve("cube.tail1.ts.net", s)` same; `resolve("cube.tail1.ts.net.", s)` same;
  - `resolve("tv", s) == null` (ambiguous); `resolve("guest", s) == null` (shared); `resolve("nope", s) == null`;
  - malformed names give null: `""`, `"a b"`, `"cube;rm"`, `"-"`, a 254-character name, `"cube..ts.net"`.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*OverlayNamesTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement.** Validate first: total length 1..253, labels split on `.` (one trailing dot allowed), each label 1..63 chars of `[A-Za-z0-9-]`, not starting or ending with `-`. Lower-case. Candidates: non-shared peers where `hostName.lowercase() == name` or `dnsName == name` or `dnsName?.substringBefore('.') == name`. Exactly one candidate, else null.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Overlay: resolve a typed name against the trusted tailnet's peers only"`.

### Task A3: add a TV by tailnet name (desktop)

**Files:**
- Modify: `cm/ui/CompanionViewModel.kt` (`Copy`, `addTv`)
- Test: `ct/ui/OverlayViewModelTest.kt`

**Interfaces:**
- Consumes: `OverlayNames.resolve`, `OverlayGate.bindingFor(address: Ipv4, trusted: List<TrustedOverlay>)`.
- Produces: `Copy.NO_SUCH_PEER = "No device by that name on your trusted tailnet"`.

- [ ] **Step 1: Failing tests** (rig snapshot uses `OverlayPeer("nCUBE", "cube", "android", listOf(ip("100.101.1.1")), true, false, dnsName = "cube.tail1.ts.net")`):
  - `aTailnetNameAddsThatPeer`: `addTvByAddress("cube")` → stored TV `address == "100.101.1.1"`, `overlayPeerId == "nCUBE"`, `trustedNetworkId == trusted.id`.
  - `anUnknownNameIsRefusedWithoutAConnection`: `addTvByAddress("nope")` → `addErrors["nope"] == Copy.NO_SUCH_PEER`, `fake.opened` empty.
  - `aNameNeedsATrustedTailnet`: `rig(overlay = false)`, `addTvByAddress("cube")` → `addErrors["cube"] == Copy.BAD_ADDRESS`, `fake.opened` empty.
  - `anIpv4LiteralNeverTakesTheNamePath`: `addTvByAddress("100.101.1.1")` behaves exactly as today (bound to `nCUBE`).
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*OverlayViewModelTest*' --console=plain` — Expected: FAIL.
- [ ] **Step 3: Implement** at the top of `addTv`: when `!isIpv4(address)` and `overlayGate != null`: take one snapshot via `overlay.source.snapshot()`; if `OverlayTrustMatcher.currentTrusted(snapshot, store.trustedOverlays())` is null → `refresh(error = Copy.BAD_ADDRESS)`; else `OverlayNames.resolve(address, snapshot)?.ips?.firstOrNull(AddressPolicy::isOverlay)` → null gives `refresh(error = Copy.NO_SUCH_PEER)`, otherwise continue `addTv` with that address string (recursive call with the literal is fine). With no overlay (Android) the existing `BAD_ADDRESS` path is unchanged.
- [ ] **Step 4:** Re-run — Expected: PASS. Then `./gradlew :companion:desktopTest --console=plain` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `git commit -m "Companion: add a TV by its tailnet name on desktop"`.

---

## Part B: TVs behind a subnet router (D4)

### Task B1: `OverlayPeer.routes` from `PrimaryRoutes`

**Files:**
- Modify: `cm/net/TrustedOverlay.kt`, `cm/discovery/TailscaleStatus.kt`
- Test: `ct/discovery/TailscaleStatusTest.kt`

**Interfaces:**
- Produces: `OverlayPeer.routes: List<Cidr> = emptyList()` (after `dnsName`): only IPv4 routes wholly inside RFC 1918 with prefix 16..32.

- [ ] **Step 0 (verify the field):** Run `/Applications/Tailscale.app/Contents/MacOS/Tailscale status --json | python3 -c 'import json,sys; d=json.load(sys.stdin); print([(p.get("HostName"), p.get("PrimaryRoutes")) for p in d.get("Peer",{}).values()])'`. If no peer in the owner's tailnet advertises a route, the field cannot be confirmed live: say so in the commit message body and rely on the fixture only.
- [ ] **Step 1: Failing test** `snapshotKeepsOnlyPrivateSubnetRoutes`: peer `"PrimaryRoutes": ["192.168.5.0/24", "0.0.0.0/0", "10.0.0.0/8", "8.8.8.0/24", "fd7a::/48", "garbage"]` → `routes == listOf(Cidr.parse("192.168.5.0/24"))`; missing key → empty.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*TailscaleStatusTest*' --console=plain` — Expected: FAIL.
- [ ] **Step 3:** Parse with `Cidr.parse`, keep `c.prefix in 16..32 && AddressPolicy.isPrivate(c.network) && AddressPolicy.isPrivate(c.broadcast)`.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Overlay: keep each router peer's private subnet routes"`.

### Task B2: routed binding in `OverlayGate`

**Files:**
- Modify: `cm/upkeep/OverlayGate.kt`
- Test: create `ct/upkeep/RoutedGateTest.kt`

**Interfaces:**
- Produces:
  - `suspend fun OverlayGate.routedBindingFor(address: Ipv4, trusted: List<TrustedOverlay>, physicalSubnet: Cidr?): Binding?`
  - `suspend fun OverlayGate.addressToUse(trusted: TrustedOverlay, tv: SavedTv, physicalSubnet: Cidr? = null): String?` (new parameter; existing callers compile unchanged).

- [ ] **Step 1: Failing tests** (fake `OverlaySource`/`EgressCheck` as in `ct/upkeep/OverlayUpkeepTest.kt`; router peer `nROUTER` with `routes = listOf(192.168.5.0/24)`):
  - `routedBindingNeedsAPrivateAddressInsideARoute`: `192.168.5.20` → binding with `peer.id == "nROUTER"`; `192.168.6.20` → null; `100.101.1.1` → null.
  - `routedBindingRefusesWhenThePhysicalSubnetHoldsTheAddress`: `physicalSubnet = 192.168.5.0/24` → null.
  - `routedBindingRefusesTwoRouters`: second router with the same route → null.
  - `routedBindingRefusesSharedRouterAndPhysicalEgress`: shared router → null; egress `EgressRoute("en0", listOf(ip("192.168.1.9")))` → null.
  - `addressToUseForARoutedTvIsItsOwnAddress`: TV `address = "192.168.5.20"`, `overlayPeerId = "nROUTER"` → `"192.168.5.20"`; with `physicalSubnet = 192.168.5.0/24` → null; router absent from snapshot → null.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*RoutedGateTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement.** `routedBindingFor`: require `AddressPolicy.isPrivate(address)`, `physicalSubnet?.contains(address) != true`, a matching trusted overlay, exactly one non-shared peer whose `routes.any { address in it }`, and `leavesThroughTailnet(snapshot, address)`. In `addressToUse`, branch first on the TV's address: if `Ipv4.parse(tv.address)` is private, apply the same four checks to the bound peer and return `tv.address` (a routed TV never moves); otherwise the existing 100.x logic unchanged.
- [ ] **Step 4:** Re-run, then `./gradlew :companion:desktopTest --tests '*OverlayUpkeepTest*' --console=plain` — Expected: both PASS.
- [ ] **Step 5:** Commit `git commit -m "Overlay gate: bind a private-address TV to the subnet router that routes it"`.

### Task B3: upkeep and add for routed TVs

**Files:**
- Modify: `cm/upkeep/Upkeep.kt` (`checkOverlay`), `cm/ui/CompanionViewModel.kt` (`addTv`)
- Test: `ct/upkeep/OverlayUpkeepTest.kt`, `ct/ui/OverlayViewModelTest.kt`

**Interfaces:**
- Consumes: Task B2 signatures. Upkeep passes `fingerprints.current()?.subnet?.let(Cidr::parse)` as `physicalSubnet`.

- [ ] **Step 1: Failing tests**
  - Upkeep: `aRoutedTvIsKeptAliveAwayFromHome` (fingerprint hotel 10.9.9.0/24, TV 192.168.5.20 bound to `nROUTER`, visit happens, `opened == ["192.168.5.20"]`); `aHotelOnTheHomeSubnetClosesTheRoutedGate` (fingerprint 192.168.5.0/24 untrusted → report `gated == true`, nothing opened); `aRoutedTvIsNeverRefound` (3 unreachable runs, fake scanner records zero calls).
  - View model: `awayFromHomeAPrivateAddressBindsToItsRouter` (fp = hotel, `addTvByAddress("192.168.5.20")` → `overlayPeerId == "nROUTER"`, `trustedNetworkId == trusted.id`); `atHomeTheSameAddressStaysALanTv` (fp = home → `trustedNetworkId == "home"`, `overlayPeerId == null`); `onAnUntrustedNetworkHoldingTheAddressItIsRefused` (fp = untrusted 192.168.5.0/24 → `addErrors[...] == Copy.NOT_TRUSTED_ERROR`, nothing opened).
- [ ] **Step 2:** Run both test classes — Expected: FAIL.
- [ ] **Step 3: Implement.** `Upkeep.checkOverlay`: pass the physical subnet to `addressToUse`. `addTv` binding order: 100.x peer binding (unchanged) → `currentTrusted()` LAN (unchanged) → `overlayGate?.routedBindingFor(ip, store.trustedOverlays(), fingerprints.current()?.subnet?.let(Cidr::parse))` → else `NOT_TRUSTED_ERROR`. The "still here" re-check after the connect calls the same function that produced the binding.
- [ ] **Step 4:** Re-run, then `./gradlew :companion:desktopTest :companion-desktop:test --console=plain` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `git commit -m "Companion: keep TVs behind a Tailscale subnet router alive on desktop"`.

### Task B4: docs

- [ ] **Step 1:** In `docs/design/overlay-networks.md` section 8, append to D4 and D5 the owner's answers and "Built: <commits>"; in section 7 Phase 3 mark the subnet-router and MagicDNS bullets done; leave NetBird, IPv6 and Android bullets open.
- [ ] **Step 2:** Commit `git commit -m "Overlay design: record D4 and D5 and Phase 3 progress"`.
