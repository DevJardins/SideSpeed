# Extra Discovery Sources (SSDP, mDNS, ARP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find TVs lists likely TVs within the first second or two by asking the LAN who is there (mDNS, SSDP, the desktop ARP table) before the sweep, without ever probing anything the sweep could not.

**Architecture:** Three new `HostSource`s feed the existing `Discovery` engine (`cm/discovery/Discovery.kt`). They only supply candidate addresses; `Discovery` still probes each with one CNXN (`SocketAdbProbe`) and still refuses non-sweepable plans before any source runs. Each new source keeps only addresses inside `plan.subnet` that are RFC 1918, so neither a mDNS reflector on another subnet nor a tailnet peer can enter through them. mDNS and SSDP use one UDP request from an ephemeral port (unicast replies; RFC 6762 section 6.7 "legacy unicast" for mDNS, SSDP replies are unicast to the sender), so nothing binds port 5353 or 1900 and Android needs no multicast lock. ARP is desktop only. Sources run concurrently so their listen windows overlap.

**Tech Stack:** Kotlin (commonMain uses `java.net` already, see `SocketAdbProbe`), kotlinx-coroutines-test virtual time, JUnit 4 in `companion-desktop`.

**Spec:** `MUSE_TASK.md` "Out of scope: SSDP/mDNS/ARP sources" (this plan lifts that), `docs/design/overlay-networks.md` section 2 (`HostSource` is the extension point) and section 6 ("never sweep anything but RFC 1918").

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `cm/` = `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/`, `ct/` = `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/`, `dt/` = `companion/src/desktopTest/kotlin/io/github/devjardins/sidespeed/companion/`, `desk/` = `companion-desktop/src/main/kotlin/io/github/devjardins/sidespeed/companion/desktop/`, `deskt/` = `companion-desktop/src/test/kotlin/io/github/devjardins/sidespeed/companion/desktop/`, `and/` = `companion-android/src/main/kotlin/io/github/devjardins/sidespeed/companion/android/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses (100.64.0.0/10) are never swept, and no new source may return one.
- commonTest cannot use `runBlocking`. Never call `TvConnector.open` in discovery; `FakeTvSession.opened` stays empty in Find tests.

## Decisions assumed (owner may overrule)

- mDNS asks the DNS-SD meta-query `_services._dns-sd._udp.local` PTR, so any responder answers, whatever services it advertises. Which services the Cube and the Onn advertise is **unverified** (HANDOFF "Later device probes: Fire TV mDNS/SSDP advertising"); Task 0 records it but nothing depends on it.
- SSDP sends `M-SEARCH` with `ST: ssdp:all`, `MX: 1`.
- Listen window 1500 ms per source, at most 256 distinct replying addresses per source.
- ARP is desktop only: Android 10+ blocks reading the neighbour table for apps (engineering knowledge, not verified here).
- Source order in production: Tailscale peers (desktop), ARP (desktop), mDNS, SSDP, sweep. Hints come before the sweep so the 4096 cap never pushes them out.
- **Unverified:** whether macOS Local Network privacy blocks the multicast send from the packaged app until the user allows it (TN3179). Plan "macos-local-network-plist" adds the usage string; check the behaviour in its Task 2.

## Review Focus

1. A reply from a public or 100.64/10 address, or from a private address outside `plan.subnet`: dropped. Tests in Task 2.
2. A flood of replies (a noisy network): capped at 256 addresses per source; the run still finishes. Test in Task 3.
3. Cancelling Find TVs during the listen window: the socket closes and nothing is emitted. Test in Task 1 (`SocketDatagramQuery` closes on cancellation).
4. No network (send fails): the source returns empty and the sweep still runs. Test in Task 3.
5. A refused plan (non-sweepable subnet): no source is even called. Test in Task 4.

---

### Task 0: Record what the TVs advertise (owner, optional, not blocking)

- [ ] **Step 1:** With the owner's OK, at home on the Mac run `dns-sd -B _services._dns-sd._udp local.` for 10 s (Ctrl-C) and note service types; then `dns-sd -Z _androidtvremote2._tcp local.` and `dns-sd -Z _amzn-wplay._tcp local.` for 5 s each and note which host names answer (Cube is 192.168.5.140).
- [ ] **Step 2:** Record the result as one line in `HANDOFF.md` under "Still open" (replace "Fire TV mDNS/SSDP advertising"). No commit needed if nothing else changed; otherwise commit with Task 5.

### Task 1: `DatagramQuery` and its socket implementation

**Files:**
- Create: `cm/discovery/DatagramQuery.kt`
- Test: `dt/discovery/SocketDatagramQueryTest.kt`

**Interfaces:**
- Produces:
  - `class Datagram(val from: Ipv4, val bytes: ByteArray)`
  - `fun interface DatagramQuery { suspend fun query(target: Ipv4, port: Int, payload: ByteArray, listenMs: Long, via: Ipv4?): List<Datagram> }` — sends `payload` once to `target:port` from an ephemeral port, collects every reply until `listenMs` passes; empty on any I/O failure.
  - `class SocketDatagramQuery : DatagramQuery` — uses `java.net.MulticastSocket(0)`, `timeToLive = 255`, `setInterface(InetAddress)` for `via` when given (ignore failure), `soTimeout` = time left; closes the socket on cancellation (same `invokeOnCancellation` pattern as `SocketAdbProbe.probe`); runs on `Dispatchers.IO`.

- [ ] **Step 1: Failing tests** (desktopTest may use `runBlocking`): `repliesFromALocalResponderAreCollected` — a `DatagramSocket(0, 127.0.0.1)` responder thread answers each packet twice with `"pong"`; `SocketDatagramQuery().query(ip("127.0.0.1"), responderPort, "ping".toByteArray(), 300, null)` returns 2 datagrams from `127.0.0.1` with bytes `"pong"`. `nothingAnsweringIsEmpty` — query an unused port with `listenMs = 200` returns empty within 1 s.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*SocketDatagramQueryTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3:** Implement as above.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Discovery: one-shot UDP query that collects unicast replies"` (add both files).

### Task 2: mDNS and SSDP packets, and the LAN-only filter

**Files:**
- Create: `cm/discovery/LanHints.kt`
- Test: `ct/discovery/LanHintsTest.kt`

**Interfaces:**
- Produces:
  - `fun DiscoveryPlan.lanOnly(hosts: List<Ipv4>): List<Ipv4>` = distinct hosts with `it in subnet && AddressPolicy.isPrivate(it)`, order kept.
  - `object Mdns { fun servicesQuery(): ByteArray; fun isResponse(b: ByteArray): Boolean }`
  - `object Ssdp { fun mSearch(): ByteArray; fun isResponse(b: ByteArray): Boolean }`

- [ ] **Step 1: Failing tests**
  - `lanOnlyKeepsPrivateHostsInsideThePlan`: plan subnet `192.168.5.0/24`; input `[192.168.5.9, 192.168.6.9, 100.101.1.1, 8.8.8.8, 192.168.5.9]` → `[192.168.5.9]`.
  - `mdnsQueryIsOnePtrQuestionForServiceEnumeration`: bytes 4..5 (QDCOUNT) = `0x0001`; bytes 2..3 (flags) = `0x0000`; the question name labels decode to `_services`, `_dns-sd`, `_udp`, `local` then `0`; last four bytes = `00 0C 00 01` (PTR, IN).
  - `mdnsResponseNeedsQrBitAndAnAnswer`: a 12-byte header with flags `0x8400` and ANCOUNT 1 → true; flags `0x0000` → false; ANCOUNT 0 → false; 5 bytes → false.
  - `ssdpSearchIsTheStandardMSearch`: text equals `"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"`.
  - `ssdpResponseStartsWithHttp200`: `"HTTP/1.1 200 OK\r\n…"` → true; `"NOTIFY * HTTP/1.1"` → false; empty → false.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*LanHintsTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3:** Implement. mDNS header: ID `0x0000`, flags 0, QDCOUNT 1, other counts 0. SSDP bytes are US-ASCII.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Discovery: mDNS and SSDP request packets and a LAN-only hint filter"`.

### Task 3: `MdnsSource` and `SsdpSource`

**Files:**
- Modify: `cm/discovery/LanHints.kt`
- Test: `ct/discovery/LanHintsTest.kt`

**Interfaces:**
- Consumes: `DatagramQuery`, `Mdns`, `Ssdp`, `lanOnly`.
- Produces: `class MdnsSource(private val net: DatagramQuery, private val listenMs: Long = 1500) : HostSource` (target `224.0.0.251:5353`), `class SsdpSource(private val net: DatagramQuery, private val listenMs: Long = 1500) : HostSource` (target `239.255.255.250:1900`); both pass `plan.own` as `via`, keep replies passing `isResponse`, apply `lanOnly`, `take(MAX_HINTS)` with `const val MAX_HINTS = 256` in `LanHints.kt`.

- [ ] **Step 1: Failing tests** with a fake `DatagramQuery` that records `(target, port)` and returns scripted datagrams:
  - `mdnsSourceAsksTheMdnsGroupAndKeepsResponders`: records `224.0.0.251:5353`; replies from `192.168.5.140` (valid response) and `192.168.5.7` (query, QR bit 0) → `[192.168.5.140]`.
  - `ssdpSourceAsksTheSsdpGroup`: records `239.255.255.250:1900`; reply `HTTP/1.1 200 OK` from `192.168.5.140` → `[192.168.5.140]`.
  - `hintSourcesCapTheirReplies`: 400 valid replies from distinct addresses in `10.0.0.0/16` with plan subnet `10.0.0.0/16` → size 256.
  - `aFailedQueryGivesNoHints`: fake returns empty → empty.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*LanHintsTest*' --console=plain` — Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Discovery: mDNS and SSDP host sources"`.

### Task 4: `Discovery` asks its sources concurrently

**Files:**
- Modify: `cm/discovery/Discovery.kt` (`run`, the `sources.flatMap { it.hosts(plan) }` line)
- Test: `ct/discovery/DiscoveryTest.kt`

**Interfaces:** `Discovery` constructor and events unchanged.

- [ ] **Step 1: Failing tests**
  - `sourcesAreAskedTogether`: two sources each `delay(1500)` then return one host (`192.168.5.20`, `192.168.5.21`); sweep-free plan (`limit` large, `FakeLan` scripted `AdbAuthRequired` for both). With `runTest`, record `currentTime` at the first `FakeLan` call: equals `1500`, not `3000`.
  - `sourceOrderIsKeptAfterConcurrentAsk`: source A (delay 100) returns `[.30]`, source B (delay 0) returns `[.31]` → `FakeLan.calls` first two addresses are `.30`, `.31` (order follows the source list, not completion time).
  - `aRefusedPlanNeverAsksAnySource`: plan subnet `100.64.0.0/16` with a source that fails the test if called → the flow completes empty.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*DiscoveryTest*' --console=plain` — Expected: the first test FAIL (3000).
- [ ] **Step 3:** Replace the flatMap with `coroutineScope { sources.map { s -> async { s.hosts(plan) } }.awaitAll().flatten() }`.
- [ ] **Step 4:** Re-run — Expected: PASS (all existing `DiscoveryTest` cases too).
- [ ] **Step 5:** Commit `git commit -m "Discovery: ask host sources concurrently, keeping their order"`.

### Task 5: Desktop ARP source

**Files:**
- Create: `desk/NeighbourParsers.kt`, `desk/ArpSource.kt`, `desk/Commands.kt`
- Test: `deskt/NeighbourParsersTest.kt`

**Interfaces:**
- Produces:
  - `object NeighbourParsers { fun mac(arpAn: String): List<Ipv4>; fun linux(ipNeigh: String): List<Ipv4>; fun windows(arpA: String): List<Ipv4> }` — addresses with a unicast MAC only (skip `(incomplete)`, `FAILED`, `INCOMPLETE`, no `lladdr`, `ff:ff:ff:ff:ff:ff`, MACs whose first octet is odd (multicast, e.g. `01:00:5e:…`)).
  - `internal object Commands { fun run(vararg cmd: String): String }` — same 5 s bounded run as `DesktopFingerprintSource.run` (copy it; do not refactor the existing classes).
  - `class ArpSource(private val read: suspend () -> String, private val parse: (String) -> List<Ipv4>) : HostSource` — `plan.lanOnly(parse(read())).take(MAX_HINTS)`.

- [ ] **Step 1: Failing tests** (JUnit 4, like `deskt/EgressParsersTest.kt`):
  - `macArp`: input lines `? (192.168.5.140) at 3c:5a:b4:1:2:3 on en0 ifscope [ethernet]`, `? (192.168.5.9) at (incomplete) on en0 ifscope [ethernet]`, `? (192.168.5.255) at ff:ff:ff:ff:ff:ff on en0 ifscope [ethernet]`, `? (224.0.0.251) at 1:0:5e:0:0:fb on en0 ifscope permanent [ethernet]` → `[192.168.5.140]`.
  - `linuxNeigh`: `192.168.5.140 dev wlan0 lladdr 3c:5a:b4:01:02:03 REACHABLE`, `192.168.5.9 dev wlan0 FAILED`, `192.168.5.10 dev wlan0  INCOMPLETE` → `[192.168.5.140]`.
  - `windowsArp`: `  192.168.5.140         3c-5a-b4-01-02-03     dynamic`, `  192.168.5.255         ff-ff-ff-ff-ff-ff     static`, `  224.0.0.22            01-00-5e-00-00-16     static` → `[192.168.5.140]`.
  - garbage and empty input → empty for all three.
- [ ] **Step 2:** Run `./gradlew :companion-desktop:test --tests '*NeighbourParsersTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3:** Implement the three files.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Desktop: ARP table as a Find TVs host source"`.

### Task 6: Wire the sources

**Files:**
- Modify: `desk/Main.kt` (the `Discovery(...)` line, line 80 today)
- Modify: `and/CompanionApplication.kt` (`Graph.discovery`, line 41 today)

- [ ] **Step 1:** Desktop: `Discovery(listOf(TailscalePeersSource(tailscale::statusJson), ArpSource(arpReader, arpParser), MdnsSource(SocketDatagramQuery()), SsdpSource(SocketDatagramQuery()), SweepSource), …)` where `arpReader`/`arpParser` pick by OS name exactly as `DesktopFingerprintSource` does (mac: `Commands.run("arp", "-an")` + `NeighbourParsers::mac`; windows: `arp -a` + `windows`; else `ip neigh show` + `linux`), read on `Dispatchers.IO`.
- [ ] **Step 2:** Android: `Discovery(listOf(MdnsSource(SocketDatagramQuery()), SsdpSource(SocketDatagramQuery()), SweepSource), …)`. No new permission (unicast replies only).
- [ ] **Step 3:** Run `./gradlew :companion:desktopTest :companion-desktop:test --console=plain` then `./gradlew :companion-android:assembleDebug --console=plain` — Expected: both BUILD SUCCESSFUL.
- [ ] **Step 4 (owner, with OK):** `./gradlew :companion-desktop:createDistributable`, open the app at home, Find TVs. Expected: the Cube (192.168.5.140) appears before "Scanned" passes 300. Record the time-to-first-TV before and after in `HANDOFF.md`.
- [ ] **Step 5:** Commit `git commit -m "Companion: Find TVs asks ARP, mDNS and SSDP before sweeping"` (Main.kt, CompanionApplication.kt, HANDOFF.md).
