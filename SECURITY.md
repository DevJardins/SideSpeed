# Security

SideSpeed changes how a TV runs, so mistakes here matter.
This page says how to report a problem, what protects what,
and what SideSpeed does not protect against.

## Reporting a problem

Please report security problems privately through GitHub:
[Report a vulnerability](https://github.com/DevJardins/SideSpeed/security/advisories/new).

- Do not open a public issue for a security problem.
- Say what you found, how to reproduce it, and which version you used.
- Only the latest release gets fixes. Please check it is still a problem there.

This covers the code in this repository:
the TV app, its helper, the Companion apps (Android and desktop),
and the release files on GitHub.

## What SideSpeed sends and keeps

- No accounts, no ads, no analytics.
- The TV app downloads release info and updates from GitHub,
  and one update list from `sidespeed.app/updates.json`.
  An app update downloads from the address that list gives.
  Your app list never leaves the TV.
- The Companion talks to your local network, to find and check your TVs,
  and to GitHub, for its own updates. Nothing else.
- Neither app asks for location.
  The Trusted Network is recognised by its router, not by the Wi-Fi name.

## What protects what

### The helper on the TV

The helper runs as the shell user (uid 2000), so it can do more than a normal app.
It is kept small on purpose.

- It listens on 127.0.0.1 only, never on the network
  (`bind` in `app/.../helper/HelperMain.kt`).
- It starts only when it can find SideSpeed's own uid. Otherwise it exits.
- It answers only SideSpeed's uid. It checks the caller's socket owner in
  `/proc/net/tcp` before it reads a single byte, and closes anything else
  (`HelperServer.kt`, `ProcNetTcp.kt`).
- It runs only the fixed commands in `CommandAllowlist.kt`,
  as a fixed list of arguments. It never runs a shell command line.
  Anything else is refused and logged.
- It proves itself on every request. SideSpeed sends a fresh random value,
  and the helper writes it where apps cannot write (`AckProofs.kt`).
  SideSpeed sends nothing more until it sees that proof (`HelperClient.kt`).
  This stops another app from pretending to be the helper.
- Its log is readable only by the shell user (`HelperLog.kt`).
- It never uninstalls anything. Turning an app off is `pm disable-user`,
  and turning it back on is `pm enable`.

### Updates

- SideSpeed installs only updates of apps already installed. It never installs new apps.
- Before any update is installed, `app/.../update/UpdateVerify.kt` checks, in order:
  the checksum, the package name, the version,
  and that the signing keys are exactly the same as the installed app's.
  Any mismatch stops the update.
- The helper caps installs at 64 MiB for SideSpeed itself and 200 MiB for other apps.
  It updates only installed sideloaded apps, never store or system apps.

### The Companion

The Companion keeps the helper running over ADB. ADB is powerful,
so the Companion is careful about where it connects and to what.

- It contacts TVs only at private addresses, or at overlay addresses
  (such as Tailscale) that are listed or typed in.
  Never at a public address (`AddressPolicy` in `net/Cidr.kt`).
- It checks TVs only on the Trusted Network you saved them on.
  It re-checks the network before every TV call and stops a search
  when the network changes (`upkeep/Upkeep.kt`, `ui/CompanionViewModel.kt`).
- A Trusted Network is matched by its router's address, subnet and DNS servers.
  Once the router's hardware address is known, it must match too,
  or the network is not trusted (`TrustMatcher` in `net/TrustedNetworks.kt`).
- TVs that support it are paired and reached over an encrypted link (TLS).
  A session for an encrypted TV is used only after TLS is confirmed.
  If the other side answers without encryption, the session is closed
  before any command is sent.
- Every session proves the TV before any other command.
  When you add a TV, the Companion leaves a random secret on it,
  in a folder only the shell user can read.
  Each later session asks the TV to prove it holds that secret,
  without sending the secret again (`tv/TvIdentity.kt`, `tv/TvSessions.kt`).
  A TV that fails is stopped and shown as "Stopped: check failed".
  The only choice offered is to remove it.
- The Companion's ADB key and saved TVs are readable only by your user account
  (`store/OwnerOnly.kt`, `tv/CompanionKeys.kt`).
- Network replies during a search are capped in count and size
  (`discovery/ReplyBudget.kt`).

## What SideSpeed does not protect against

These are known limits. Each one is a choice, and the reason is given.

- **ADB access is full access.** Anyone your TV has allowed to use ADB
  can do everything the helper can, and more.
  SideSpeed cannot protect a TV from a device it has already allowed.
  Setup says that ADB debugging must stay on,
  and that anyone on the network could ask the TV for access.
  The TV still asks you before it allows a new device.
- **The older connection is not encrypted.** Some TVs cannot use an encrypted link.
  The Companion can still reach them the older way, but only after you tick
  "I understand. Connect the older way." for that TV.
  On that link, anyone on your network could see or change the commands,
  and could send their own while it is connected.
  That includes the TV's secret, once, when the TV is first added.
  If the TV later offers an encrypted link, the Companion stops and asks you to pair it.
- **A look-alike network can be searched.** On desktop, a copy needs your router's
  address, subnet, a DNS server and hardware address.
  On Android the hardware address is not read. `TrustMatcher` checks a hardware
  address only once one has been recorded, so on Android the address, subnet
  and one shared DNS server are enough. The Companion may then search that network.
  It still sends no commands to a TV that cannot prove it holds its secret.
- **A reset TV must be added again.** The secret lives in `/data/local/tmp` on the TV.
  A factory reset, or anything that clears that folder, removes it.
  The TV then fails its check. Remove it and add it again.
- **Windows file permissions are best effort.** On macOS and Linux the Companion's
  files are owner-only. On Windows the Companion asks for the same,
  but Windows may not enforce it the same way.
- **Desktop encryption uses BouncyCastle.** ADB's encrypted pairing needs a feature
  Conscrypt does not ship for Apple Silicon or Linux on ARM.
  The desktop app uses BouncyCastle instead, through two small classes named
  `org.conscrypt.*` that the ADB library looks for
  (`companion/src/desktopMain/kotlin/org/conscrypt/`).
  They are used only for ADB, never for the app's other connections.

## Rules for contributors

The full list is in [AGENTS.md](AGENTS.md), section 5. In short:

- Never widen the helper's powers (new commands, looser checks, weaker install checks)
  without the owner's decision.
  New commands are exact entries in `CommandAllowlist.kt`, with tests.
- Never run `sh -c`, and never pass unchecked input to a shell.
- Refusals fail closed. A test that expects a refusal must keep expecting one.
- Never send a TV a command before its proof (`TvIdentity.prepare` in `TvSessions.open`).
- Never contact a TV from a network that is not its Trusted Network.
- Never commit key material: keystores, `keystore.properties` or passwords.
