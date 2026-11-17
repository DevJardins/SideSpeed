# Overlay, mesh and VPN networks: design

Status: proposal, 2026-11-16. Scope: everything about overlay networks **except** (a) manual add of
100.64.0.0/10 and (b) the desktop `tailscale status --json` host source, which are being built in parallel.
Section 6 is a review checklist for those two so they line up with this design.

## 1. Recommendation

1. **No generic "any address on any interface" rule.** Keep a fixed, short allowlist of address classes:
   RFC 1918 (today) plus 100.64.0.0/10 minus Tailscale's reserved blocks (in progress). Interface subnets
   and routes are used only to **check where a connection goes**, never to widen what may be contacted.
   A generic rule would make public space reachable: Hamachi puts peers in 25.0.0.0/8 (UK MoD space), and a
   full-tunnel VPN's utun routes 0.0.0.0/1 + 128.0.0.0/1 (section 4).
2. **Per-tool adapters for enumeration and identity, desktop only, Tailscale first.** Tailscale (and
   Headscale, which uses the same client) is the only overlay with a documented, unprivileged, local,
   per-peer view (IP to node ID to WireGuard key) on macOS, Windows and Linux. NetBird (`netbird status
   --json`) is the only plausible second adapter. Everything else stays **manual-add only** (section 3).
3. **Overlay trust is a second kind of Trusted Network, not a relaxed physical fingerprint.** A
   `TrustedOverlay` matches on *tailnet name + this device's own node ID*, and each overlay TV is bound to
   its *peer node ID*. Before every connection the gate re-reads `tailscale status`, checks the TV's address
   is still that peer's address, and checks the OS would send the packet out the Tailscale interface. The
   serial check stays, but it is a misdirection check, not authentication (section 5).
4. **Upkeep away from home: desktop yes (phase 2), Android not yet.** Android apps can't list Tailscale
   peers or tell one VPN app from another, so Android overlay trust would rest on "some VPN with a 100.x
   address is up". That is an owner decision (D3), default off.
5. **Never sweep anything but RFC 1918.** 100.64.0.0/10 is 4 million addresses and is also the carrier's
   range on mobile data, Starlink and some hotel networks. Peer lists give single /32 candidates.
6. **Fix first (cheap, likely bug):** with a VPN up on Android, `AndroidFingerprintSource` reads
   `cm.activeNetwork`, which can be the VPN, so the *home* Trusted Network may stop matching while Tailscale
   is on. Probe P1 confirms. This affects people today, before any overlay feature.

## 2. What the code does today (read before changing)

- `net/Cidr.kt` `AddressPolicy`: `isPrivate` = RFC 1918; `sweepable` = /16../30 inside RFC 1918.
- `discovery/Discovery.kt`: refuses any plan that is not port 5555 + sweepable, and filters every candidate
  through `isPrivate`. `HostSource` is the extension point; `SocketAdbProbe` sends one CNXN and closes.
- `ui/CompanionViewModel.kt`: `collectFind` and `addTv` both start with `currentTrusted()` (physical
  fingerprint). `addTv` rejects non-RFC 1918, opens a full Kadb session, reads serial/model/maker, rechecks
  the network did not change, saves `SavedTv(trustedNetworkId = …)`.
- `upkeep/Upkeep.kt`: gate = `TrustMatcher.currentTrusted(...)?.id == trusted.id`, re-read before every
  visit and every re-find connection. Re-find = `TcpScanner` over the Trusted Network's /24 and a serial
  read on each host that answers. `HelperKeeper.visit` reads `getprop ro.serialno` first and sends nothing
  else on mismatch.
- `Diagnosis.VPN_ACTIVE` assumes a VPN hides the LAN; with split-tunnel overlays that is usually false.

## 3. Survey

"Enumerate" = how a local program lists peers *without the vendor's cloud API*. "Fire/Android TV" = a
client that runs on the TV so the TV itself has an overlay address. Marked **[c]** = checked against a
cited source this time; unmarked = engineering knowledge, confirm with a probe before relying on it.

| Tool | IPv4 / IPv6 | Enumerate locally | Fire TV / Android TV | ADB 5555 through it |
|---|---|---|---|---|
| **Tailscale** | 100.64.0.0/10; reserved 100.100.0.0/24, 100.100.100.0/24 (Quad100 100.100.100.100), 100.115.92.0/23 / fd7a:115c:a1e0::/48 **[c]** [1][2] | `tailscale status --json` on mac (CLI inside Tailscale.app), Windows, Linux; LocalAPI `/localapi/v0/status` over a socket/pipe that differs per OS [3]. Fields: `BackendState`, `TUN`, `CurrentTailnet.Name`, `Self`, `Peer{ID, PublicKey, HostName, OS, TailscaleIPs, Online, ShareeNode, PrimaryRoutes}`. Android: no API for other apps. | Fire TV: sideloaded APK only, Fire OS 7+, not Vega OS, no auto-update **[c]** [4]. Android TV: Play Store app. | Expected to work (TV is a normal node; Android accepts inbound on tun and replies on the same net). Needs ACL allowing companion to `tcp:5555` on the TV. Linux nodes drop non-tailnet 100.64/10 traffic; macOS/Windows don't **[c]** [5]. Not field-verified on Fire TV: probe P2. |
| **Headscale** | Configurable, defaults as Tailscale | Same client, same CLI/JSON (`CurrentTailnet.Name` is the Headscale user/domain). | Same Tailscale APK with custom login server. | Same as Tailscale. |
| **NetBird** | Random /16 inside 100.64.0.0/10 per account, admin-configurable; optional IPv6 overlay **[c]** [6] | `netbird status --json` (`peers[]`, `status`) **[c]** [6]; interface `wt0` / utun. Same /10 as Tailscale, so both on one host conflict [6]. | Android TV app, beta, Android 8+ **[c]** [7]. Fire TV: sideload, unverified. | Plausible, unverified. |
| **ZeroTier** | Admin-chosen pools, usually RFC 1918 (10.147.x, 172.2x, 192.168.19x); v6 RFC 4193 `fd…` and 6PLANE `fc…` | Local service API on 127.0.0.1:9993 with `authtoken.secret` (`/network`, `/peer`) **[c]** [8]; the token file is root/admin-readable. `/peer` gives ZeroTier node IDs, not their IPs: no IP-to-peer map. Android apps embed the core, no local API [8]. | Android app; TV and Fire TV only by sideload, unofficial. | Works if the TV joins (L2 Ethernet emulation). Addresses are usually RFC 1918 already. |
| **WireGuard** (plain) | Anything the user configures | `wg show all allowed-ips` needs root/CAP_NET_ADMIN; macOS/Windows apps keep config inside the app/service. Unprivileged: interface + routes only. | Official Android app has a TV UI; Fire TV by sideload. | Works when the TV is a peer or behind a peer whose AllowedIPs cover it. |
| **Netmaker** | Admin-chosen CIDR | `netclient` CLI on desktop; mobile/TV via plain WireGuard "external client" config. | Via WireGuard app. | As WireGuard. |
| **Nebula** (+ Defined Networking) | Admin-chosen, docs use 192.168.100.0/24 | No peer-listing API by default (optional SSH debug console); `nebula1` interface. | Mobile Nebula app (Android); TV unverified. | Plausible; Nebula firewall rules must allow 5555. |
| **Cloudflare WARP / Zero Trust** | Device IPs 100.96.0.0/12 by default, configurable **[c]** [9] | `warp-cli` shows only own state; no peer list. WARP-to-WARP must be enabled and Split Tunnels must include 100.96.0.0/12 [9]. Windows firewall blocks inbound from that range by default [9]. | Cloudflare One client on Android TV: unverified. | Possible via WARP-to-WARP or a Tunnel exposing the TV's LAN IP; heavy setup; treat as manual. |
| **Twingate** | Client reserves 100.96.0.0/12 and hands out DNS-mapped CGNAT IPs that are *not* the resource's real IP **[c]** [10] | No peer concept: client → connector → resource. Nothing to enumerate locally. | Android client; TV unverified. | Only if the TV is a defined resource (by its home LAN IP or DNS name). Addresses can differ per device [10]. |
| **OpenVPN** / Access Server | Server-chosen, commonly 10.8.0.0/24 | Server status file only; client sees own tun + pushed routes. | "OpenVPN for Android" works on Android TV. | Needs `client-to-client` or a pushed route to the home LAN (TV keeps its RFC 1918 address). |
| **Hamachi** | 25.0.0.0/8 (publicly allocated to UK MoD), earlier 5.0.0.0/8; IPv6 too **[c]** [11] | Hamachi client UI/CLI. | No current Android client. | **Never supported**: the addresses are public space. |
| **Firezone** | Tunnel 100.64.0.0/11 + fd00:2021:1111::/107 (unverified); DNS proxy 100.100.111.0/24 **[c]** [12] | No local peer list (resources, not peers). | Android client; TV unverified. | Resource-based, like Twingate. |
| **Subnet routers / site-to-site** (Tailscale `--advertise-routes`, ZeroTier, WG, OpenVPN) | TV keeps its home RFC 1918 address | Tailscale shows `PrimaryRoutes` on the router peer. | TV needs nothing installed. | Works, but the address *looks* like a LAN address while away; collides with any hotel/office LAN using the same /24 (section 5.3). |
| Others: Yggdrasil (200::/7), Husarnet (fc94::/16, v6 only), tinc, SSH `-L`, ngrok-style public TCP tunnels | — | — | — | Yggdrasil/Husarnet are IPv6-only (companion is IPv4-only). Public TCP tunnels expose adbd to the internet: **never**. Loopback forwards (127.0.0.1:5555) collide with the Android emulator's ADB port: **never**. |

Takeaways: (1) almost every overlay either uses RFC 1918 (already allowed, at home) or CGNAT 100.64/10
(Tailscale, Headscale, NetBird, WARP, Twingate, Firezone); (2) only Tailscale/Headscale and NetBird give an
unprivileged, local, per-peer IP list; only Tailscale also gives a stable node ID and key per IP;
(3) 100.64/10 is shared by several products **and by carriers**, so the range alone proves nothing.

## 4. One generic mechanism vs adapters

| Option | Verdict |
|---|---|
| "Any address in a subnet of any up interface is manual-addable" | **Reject.** Overlay interfaces carry /10, /12 or /0-ish routes (full-tunnel VPN, exit node, iCloud Private Relay also uses utun); Hamachi's subnet is public; a hostile Wi-Fi can hand out any subnet, so this rule lets the network decide what is "local". It also cannot tell Tailscale from a carrier on 100.64/10. |
| Generic peer source from routes / WireGuard AllowedIPs | **Reject for enumeration.** Routes are aggregates (nothing to probe but a sweep); AllowedIPs need root; ZeroTier has no IP-to-peer map; Twingate/WARP have no peers. |
| Generic *classifier + egress check* | **Adopt.** Classify an address by fixed ranges; ask the OS which interface a destination egresses on (`route -n get`, `ip route get`, `Find-NetRoute`); classify interfaces (`tailscale0`/`Tailscale`/utun with a 100.64/10 address, `zt*`, `wg*`, `wt0`, `tun*`) for labels and diagnosis only. |
| Per-tool adapters (`OverlayAdapter`) | **Adopt for enumeration + identity**: Tailscale now, NetBird maybe later. |

Architecture (new types in `companion/src/commonMain/.../net/` unless noted):

```
AddressClass  (PRIVATE_LAN, CGNAT, OVERLAY_RESERVED, LOOPBACK, LINK_LOCAL, MULTICAST, PUBLIC, OTHER_SPECIAL)
AddressPolicy.classify(ip) / manualAddable(ip) / sweepable(cidr)          sweep rule unchanged
EgressCheck   fun interface: suspend fun interfaceFor(ip: Ipv4): EgressRoute?   (desktopMain, androidMain impls)
OverlayAdapter   interface: suspend fun snapshot(): OverlaySnapshot?          (TailscaleAdapter in desktopMain)
OverlaySnapshot(kind, tailnet, selfId, selfIps, interfaceName?, running, tun, peers: List<OverlayPeer>)
OverlayPeer(id, publicKey, hostName, os, ips, online, shared)
TrustedOverlay(id, name, kind, tailnet, selfId)                             stored next to TrustedNetwork
TrustScope = Lan(TrustedNetwork) | Overlay(TrustedOverlay)
Gate          fun interface: suspend fun stillOpen(tv: SavedTv): GateVerdict   LanGate, OverlayGate
```

What stays manual-only, with no overlay trust (works only while on a Trusted LAN, as today): ZeroTier,
plain WireGuard, Netmaker, Nebula, OpenVPN, WARP, Twingate, Firezone, subnet-routed TVs. What is never
possible: Hamachi 25/8, any public address, loopback, link-local, multicast, Tailscale reserved blocks,
IPv6 (until a separate decision).

## 5. Trust and remote management

### 5.1 TrustedOverlay fingerprint (desktop, Tailscale/Headscale)

Matches when, from one fresh `OverlaySnapshot`:
- `BackendState == "Running"` and `TUN == true` (userspace-networking mode means our sockets can't reach
  peers; report "Tailscale runs without a network interface" instead of failing silently);
- `CurrentTailnet.Name` equals the saved tailnet name (not just the MagicDNS suffix);
- `Self.ID` (StableNodeID) equals the saved one, so a different login on the same machine, or the same
  account on another machine with a copied store, does not match.

Fields deliberately **not** used: the interface name (utun numbers change), own 100.x address (stable but
not secret), DNS 100.100.100.100 (every Tailscale node has it, any VPN can mimic it).

### 5.2 Per-TV binding and the gate

`SavedTv` gains `overlayPeerId: String?` (Tailscale StableNodeID) and `trustScopeId` (rename/alias of
`trustedNetworkId`, keeping the stored key). `OverlayGate.stillOpen(tv)` runs before **every** connection
(same places `gateOpen` runs today) and is open only if:

1. the TrustedOverlay still matches (5.1);
2. a peer with `ID == tv.overlayPeerId` exists, `ShareeNode == false` (not shared in from another tailnet);
3. `tv.address` is in that peer's `TailscaleIPs` (if the peer's IP changed, the gate returns the new
   address: this replaces the /24 re-find for overlay TVs, which must never run);
4. `EgressCheck.interfaceFor(tv.address)` is the Tailscale interface from the snapshot (catches: Tailscale
   stopped between steps, a carrier/hotel 100.64/10 network taking the route, another VPN with an
   overlapping range; macOS/Windows have no Tailscale drop rule [5]);
5. then connect, then the existing serial check in `HelperKeeper.visit`.

Why this does not weaken the model: the identity of the far end is now the WireGuard key Tailscale has
authenticated for that node, which is stronger than "same gateway IP/MAC/DNS" on a LAN (all spoofable by
whoever runs the Wi-Fi). The weakest link becomes the tailnet admin: anyone who can add nodes and change
ACLs to the tailnet can already reach the TV. Owner should accept that explicitly (D1).

Away from home: none of 5.1/5.2 reads the physical network, so a laptop on a hotel network with Tailscale
up keeps upkeep running for overlay TVs. Physical Trusted Networks still gate LAN TVs as before; a TV can
be bound to one scope only (add it twice, once per scope, if wanted: same serial, two `SavedTv`s, so
`addTv`'s "existing by serial" merge must become "existing by serial **and** scope").

### 5.3 Threats checked

| Threat | Outcome |
|---|---|
| Hostile LAN (hotel, carrier, Starlink) uses 100.64/10 and has an ADB host at the TV's 100.x | Gate step 4: route must go out the Tailscale interface; step 1: Tailscale must be running. |
| Attacker device on the same physical LAN spoofs a 100.x source | Irrelevant: we initiate; WireGuard drops unauthenticated packets on the tunnel. |
| Peer shared in from another tailnet, or a new node added by the tailnet owner | Not bound (step 2 checks node ID, not IP); shared nodes rejected. |
| VPN/exit node routes to unexpected networks | Only 100.64/10 overlay addresses get overlay trust; RFC 1918 addresses reached via a subnet router are **not** overlay-trusted in phase 2 (D4). |
| Subnet-router collision: home 192.168.1.0/24 routed via Tailscale, hotel also 192.168.1.0/24 | Classic failure: the connected hotel subnet wins the route, the companion talks to a hotel device. Hence D4 stays closed until egress check + router peer binding exist. |
| Phone roaming between Wi-Fi, mobile data, VPN | Android: no overlay trust (D3). Desktop: gate re-evaluated per connection, a run stops when it closes. |
| Rogue ADB server at the expected address answers the serial | Serial is self-reported and ADB's RSA auth authenticates only the client, so the serial cannot prove the device. Also: answering an `AUTH TOKEN` signs attacker-chosen bytes with the companion key, which a man in the middle can relay to a real TV that trusts that key. This exists today on LANs; overlay binding (key-authenticated peer) narrows it; the bare 100.64/10 manual add (in progress) does not. |

**Is the serial check enough?** For a LAN it is a good guard against DHCP moving addresses and adding the
wrong box; it is not authentication. For overlays it is enough **only together with** the peer-ID binding
and the egress check. For a bare manual 100.x add without an adapter (Android, or desktop without the CLI),
it is no better than the LAN case and should stay usable only while on a physical Trusted Network.

## 6. Security of relaxing "RFC 1918 only"

| Address class | Manual add | Sweep | Peer list probe | Upkeep gate |
|---|---|---|---|---|
| RFC 1918 | yes, on Trusted LAN (today) | /16../30, Trusted LAN only (today) | n/a | LAN fingerprint |
| 100.64.0.0/10 minus 100.100.0.0/24, 100.100.100.0/24, 100.115.92.0/23 | yes (in progress); while on a Trusted LAN, or when a TrustedOverlay matches and the address is one of its peers | **never** | yes, /32 each, one CNXN, peers that are `Online`, not `ShareeNode`, `OS == "android"`, cap 256, egress check first | Overlay gate (5.2) |
| 25.0.0.0/8 and all other public, loopback 127/8, 0/8, 169.254/16, 224/4, 240/4, 255.255.255.255, 192.0.0/24, 198.18/15, docs ranges | **never** | never | never | never |
| IPv6 ULA (fd7a:115c:a1e0::/48 etc.) | not now (companion is IPv4-only); D6 | never | later | later |

Review checklist for the parallel work:
- Manual add must exclude Tailscale's reserved blocks (Quad100 serves DNS and HTTP on 100.100.100.100 [2])
  and must not loosen `sweepable` (add a test that `100.64.0.0/16` is not sweepable).
- Until D1 lands, a 100.x TV should still be saved under the current physical Trusted Network: it will be
  kept alive only at home. Say so in the UI ("Kept alive while on <network>"). **Done**: `d4fd954`.
- The Tailscale host source should take `--json` output through a pure parser (fixture tests), call the CLI
  with a fixed argv and a 5 s timeout like `DesktopFingerprintSource.run`, locate the CLI at fixed paths
  (`/Applications/Tailscale.app/Contents/MacOS/Tailscale`, `C:\Program Files\Tailscale\tailscale.exe`,
  `tailscale` on PATH for Linux) and never pass user text to it. `Discovery.run` today drops every
  non-sweepable plan and non-RFC 1918 host, so peers need their own plan type, not a widened filter.

Permissions and prompts:
- **macOS** (Local Network privacy, macOS 15+): a "local network" is one on a broadcast-capable interface
  (Wi-Fi, Ethernet), explicitly **not** VPN; outgoing TCP to it needs the privilege; command-line tools run
  from Terminal are exempt, apps are tracked by code signature [13]. So: connections to 100.x over utun need
  no prompt; the LAN sweep/upkeep already needs it; the packaged desktop app needs
  `NSLocalNetworkUsageDescription` in its Info.plist and a stable Apple signing identity (testing from
  `./gradlew run` in Terminal hides the prompt). Spawning the `tailscale` CLI needs nothing.
- **Android**: the companion targets SDK 36, so `INTERNET` implies local-network access today. Moving to
  targetSdk 37 makes `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group) a runtime permission needed for any
  outgoing TCP to a local address; blocked connects look like timeouts [14]. The page does not say whether
  VPN/CGNAT destinations count as local: probe P7 before targeting 37. Using another app's VPN needs no VPN
  permission; but if the user excluded the companion in Tailscale's per-app settings, 100.x is unreachable
  (diagnose it). No location permission is needed for any of this.
- **Windows**: outbound connections need no firewall rule by default; Defender Firewall only prompts for
  listening sockets. Corporate outbound policies can block; report as `OS_BLOCKED`. `tailscale.exe status`
  runs without admin. (Inbound blocks such as WARP's 100.96/12 rule [9] matter only on the TV side.)
- **TV side**: the TV must run the overlay client, stay logged in after reboot, and the overlay ACL must
  allow tcp:5555 from the companion's node only. Recommend the copy say "allow only port 5555 from your
  computer", never "allow all".

## 7. Task list (test-first, small steps)

Run tests with `./gradlew :companion:desktopTest` (one Gradle run at a time). Each task: write the
failing test, then the code.

### Phase 0: safe now (no owner decision; no behaviour change for existing users)

- **T0.1 `AddressClass` + `AddressPolicy.classify`** (`net/Cidr.kt`, test `net/AddressPolicyClassifyTest`).
  Assert boundaries: 10.0.0.0, 172.15.255.255 (PUBLIC), 172.16.0.0, 100.63.255.255 (PUBLIC), 100.64.0.0
  (CGNAT), 100.100.100.100 and 100.115.92.5 (OVERLAY_RESERVED), 100.127.255.255 (CGNAT), 25.1.2.3
  (PUBLIC), 127.0.0.1, 169.254.1.1, 224.0.0.1, 255.255.255.255. `isPrivate` and `sweepable` results
  unchanged (existing `CidrTest` passes untouched); `sweepable(100.64.0.0/16) == false`.
- **T0.2 `AddressPolicy.manualAddable(ip, context)`** where context is `OnTrustedLan | OnTrustedOverlay(peers)`.
  Coordinate with the parallel manual-add change so there is one function, not two. Assert reserved and
  public never addable; CGNAT addable on Trusted LAN; CGNAT on overlay only if in a peer's IPs.
- **T0.3 `EgressCheck` + parsers** (`desktopMain/.../net/EgressParsers.kt`, `desktopTest`): parse captured
  outputs of `route -n get 100.101.1.2` (macOS, `interface: utun4`), `ip route get 100.101.1.2` (Linux,
  `dev tailscale0`), PowerShell `(Find-NetRoute -RemoteIPAddress 100.101.1.2).InterfaceAlias` (Windows,
  `Tailscale`). Assert null on garbage, on "not in table", and on non-IPv4 input (argument validated before
  any command runs, as `DesktopFingerprintSource` does for the gateway).
- **T0.4 `OverlayInterfaces.classify(name, ipv4s)`** (common, pure): utun/tailscale0/"Tailscale" with a
  100.64/10 address = TAILSCALE_LIKE; `zt*` = ZEROTIER; `wt0` = NETBIRD; `wg*` = WIREGUARD; `tun*` = VPN.
  Used for labels and `Diagnosis` only; test that classification never feeds `manualAddable`.
- **T0.5 `TailscaleStatusParser`** (desktopMain; extend the parallel source's parser if it exists) to an
  `OverlaySnapshot`: fixtures for Running, Stopped, NeedsLogin, `TUN:false`, a `ShareeNode` peer, a peer
  with two IPs (v4 + v6, keep v4), missing `CurrentTailnet`. Assert unknown fields are ignored and a
  malformed document gives null, not an exception.
- **T0.6 Diagnosis copy**: rename the meaning of `VPN_ACTIVE` to "a full-tunnel VPN or exit node hides the
  home network" and add `OVERLAY_USERSPACE` / `OVERLAY_STOPPED` with copy ending in the manual-address
  fallback (existing `helpFor` test pattern). No platform wiring yet.

### Phase 1: after probe P1 (Android fingerprint with a VPN up)

- **T1.1** `FingerprintMapping` gets the non-VPN network: `AndroidFingerprintSource` picks the network with
  `TRANSPORT_WIFI`/`TRANSPORT_ETHERNET` and `NET_CAPABILITY_NOT_VPN` instead of `activeNetwork`. Test via
  `LinkData` fixtures: VPN link (100.x/32, no default route) is ignored; Wi-Fi link maps as before.
- **T1.2** LAN sockets on Android bind to that Wi-Fi `Network` (`network.bindSocket`) so a VPN cannot
  carry LAN traffic elsewhere. Test with a fake socket factory that records the bound network.

### Phase 2: needs D1 (overlay trust, desktop only)

- **T2.1 `TrustedOverlay` + store** (`net/TrustedOverlay.kt`, `store/JsonFileCompanionStore`): round-trip
  test; old stores without the key load unchanged; removing a TrustedOverlay removes its TVs (mirror
  `removeTrustedNetwork`). **Done** (`d6528a1`).
- **T2.2 `OverlayTrustMatcher.matches(snapshot, trusted)`**: table test of 5.1 (each field mismatch false;
  `TUN:false` false; not Running false). **Done** (`d6528a1`).
- **T2.3 `Gate` interface, `LanGate` (wraps today's `gateOpen`), `OverlayGate`**: tests for each step in
  5.2, including "peer IP changed → verdict carries the new address" and "egress is en0 → closed".
  **Done** (`d358b4c`, `f1a0c37`; built as `OverlayGate.addressToUse`/`bindingFor`, the LAN gate stays
  `Upkeep.gateOpen`, no `Gate` interface).
- **T2.4 `Upkeep` takes a `Gate` per scope**: existing `UpkeepTest` passes with `LanGate`; new tests: an
  overlay TV is never re-found by `Scanner` (fake scanner asserts zero calls); gate closing between two TVs
  stops the run with `gated = true`; moved peer IP updates `address` only after the serial matches.
  **Done** (`d358b4c`).
- **T2.5 `SavedTv.overlayPeerId` + `addTv` overlay path** in `CompanionViewModel`: adding 100.x while a
  TrustedOverlay matches binds the peer whose IPs contain it; rejects if no peer has it; "existing by
  serial" becomes "by serial and scope". `FakeTvSession.opened` stays empty in Find tests.
  **Done** (`f1a0c37`).
- **T2.6 "Trust this tailnet" UI action** `trustCurrentOverlay(name)` mirroring `trustCurrentNetwork`.
  **Done** (`7ee79e4`).
- **T2.7 Tray timers / Autostart** run upkeep when either kind of scope matches; report which one.
  **Done** (`b79bb73`; banner: `422adb7`).

Owner decision 2026-11-16 (changes D-level behaviour): there is no manual "Trust this tailnet" step.
Adding a TV on the tailnet the computer is signed into trusts that tailnet automatically (`5aeb35f`).
Shared-in peers are still refused, and a TV bound to another tailnet is still not trusted. Android is
unchanged (no Tailscale source). TV cards show how each one was last reached (`d54fa2b`, `fb7a32f`): a
2dp border in ACCENT for LAN, 0xFF8FAACC for tailnet, 0xFFC98A8A for unreachable, and no border when not
checkable here.

Device check 2026-11-16 (Mac, Onn at 100.123.103.8): add over the tailnet auto-trusts it; Tailscale off on
the Mac gives Not checked; Tailscale back gives a blue check; Tailscale off on the Onn gives red; LAN card
green. Away-from-home on another network not yet run.

### Phase 3: needs D3/D4/D5 (do not start without the owner)

- Android overlay trust (weak: VPN network present, 100.x link address, route covers the TV, socket bound to
  the VPN `Network`; no peer identity).
- Subnet-routed TVs: bind to the router peer's ID + `PrimaryRoutes` containing the TV's /32's subnet + egress
  check; refuse when a physical interface's connected subnet overlaps. **Declined 2026-11-16 (D4)**.
- MagicDNS names in manual add (resolve, then classify the result). **Declined 2026-11-16 (D5)**.
- NetBird adapter; IPv6 overlay addresses.

## 8. Open questions for the owner

- **D1** Is "anyone who administers the tailnet can reach the TV" acceptable as the trust root for remote
  upkeep? (Recommended: yes, desktop only.)
  **Decided (2026-11-16): yes, desktop only, as recommended.** Phase 2 is unblocked; Phase 3 still waits on D3/D4/D5.
- **D2** Tailscale/Headscale as the only adapter for now? NetBird later only if someone asks?
- **D3** Android away-from-home upkeep with the weaker trust in phase 3, or desktop-only for remote?
- **D4** Support TVs reached via a subnet router (no Tailscale on the TV)? Riskier (collisions) and the
  TV's identity is only its serial.
  **Declined 2026-11-16 (owner):** not worth building; almost every user sets up one home, and TVs that run Tailscale already cover remote upkeep.
- **D5** Accept MagicDNS / hostnames in manual add?
  **Declined 2026-11-16 (owner):** not worth building; almost every user sets up one home, and TVs that run Tailscale already cover remote upkeep.
- **D6** IPv6 overlay addresses at all?
- **D7** Peer-list probing limited to `OS == "android"` peers (fewer surprises for others' devices)?
- **D8** Bare 100.x manual adds without an adapter: keep them tied to the physical Trusted Network
  (recommended) or allow them anywhere?

## 9. Cheap probes the owner can run

- **P1** Android phone at home, Tailscale on (no exit node): open the companion. Does it still show the
  Trusted Network? Then `adb shell dumpsys connectivity | grep -iE "VPN|tun0|default"` from a computer.
- **P2** Fire TV with Tailscale, laptop on a phone hotspot with Tailscale: `nc -vz <tv-100.x> 5555`, then
  `adb connect <tv-100.x>:5555 && adb shell getprop ro.serialno`. Repeat after the TV sleeps 30 min and
  after a TV reboot (does Tailscale come back without opening it?).
- **P3** On each desktop OS: `tailscale status --json | jq '{BackendState, TUN, t: .CurrentTailnet.Name,
  self: .Self.ID, peers: [.Peer[] | {ID, HostName, OS, TailscaleIPs, Online, ShareeNode}]}'`. Send the
  output (redact names) as test fixtures for T0.5.
- **P4** `route -n get <tv-100.x>` (macOS), `ip route get <tv-100.x>` (Linux), `Find-NetRoute
  -RemoteIPAddress <tv-100.x>` (Windows): note the interface, with Tailscale on and off. Fixtures for T0.3.
- **P5** Phone on mobile data, Tailscale off: does the phone have a 100.64/10 address (`adb shell ip addr`)?
  This shows how common the carrier collision is.
- **P6** Tailscale ACL with only `tcp:5555` from the laptop to the TV: P2 still works; port 22 blocked.
- **P7** Android 17 device, a debug build with targetSdk 37 and `ACCESS_LOCAL_NETWORK` denied: does a
  connection to the TV's 100.x via Tailscale still succeed? (Decides whether overlays need that prompt.)
- **P8** macOS packaged app (not from Terminal): first LAN probe shows the Local Network alert; a 100.x
  probe alone does not.

## Sources

1. Tailscale, "What are these 100.x.y.z addresses?" https://tailscale.com/kb/1015/100.x-addresses
2. Tailscale, "Reserved IP addresses" https://tailscale.com/docs/reference/reserved-ip-addresses
3. Tailscale `ipnstate` package (Status, PeerStatus, CurrentTailnet) https://pkg.go.dev/tailscale.com/ipn/ipnstate
   and LocalAPI client https://pkg.go.dev/tailscale.com/client/tailscale
4. Tailscale, "Install Tailscale on Amazon Fire devices" https://tailscale.com/docs/install/amazon-fire
5. Tailscale, "CGNAT interoperability" (Linux drop rule; not on macOS/Windows) https://tailscale.com/docs/reference/cgnat-interoperability
6. NetBird, "Network Settings" https://docs.netbird.io/manage/settings/networks ; troubleshooting/CLI
   https://docs.netbird.io/help/troubleshooting-client ; Arch Wiki on the 100.64/10 overlap https://wiki.archlinux.org/title/Netbird
7. NetBird, "Install NetBird on an Android TV" https://docs.netbird.io/get-started/install/android-tv
8. ZeroTier Service API https://docs.zerotier.com/api/service ; config/authtoken https://docs.zerotier.com/config/
9. Cloudflare, "Device IPs" and "Peer-to-peer connectivity" https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/device-ips/ ,
   https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/private-net/warp-to-warp/
10. Twingate, "How DNS works with Twingate" https://www.twingate.com/docs/how-dns-works-with-twingate ;
    CGNAT conflicts https://help.twingate.com/articles/4359531030-cgnat-ip-conflicts-dns-resolution-resource-access-issues-with-twingate
11. LogMeIn/GoTo Hamachi virtual IP https://support.logmeininc.com/central/help/the-logmein-hamachi-client-central-c-hamachi-aboutclients ;
    "25.0.0.0/8 is not your private network" https://blog.dynamoo.com/2017/04/250008-is-not-your-private-network.html
12. Firezone DNS docs (DNS proxy ranges) https://firezone-5ldg0u66q-firezone.vercel.app/kb/deploy/dns
13. Apple, TN3179 "Understanding local network privacy" https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
14. Android, "Local network permission" https://developer.android.com/privacy-and-security/local-network-permission
15. Amazon, "Connect to Fire TV through ADB" https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html
