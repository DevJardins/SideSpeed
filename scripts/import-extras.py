#!/usr/bin/env python3
"""Snapshot CleanYourTV examples/ into app/src/main/assets/extras/ (stdlib only)."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")

CORE_EXACT = {
    "android",
    "android.ext.services",
    "android.ext.shared",
    "com.android.sdksandbox",
    "com.android.location.fused",
    "com.android.systemui",
    "com.android.shell",
    "com.android.phone",
    "com.android.settings",
    "com.android.tv.settings",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.ext.services",
    "com.google.android.ext.services",
    "com.android.ext.shared",
    "com.google.android.ext.shared",
    "com.android.modulemetadata",
    "com.google.android.modulemetadata",
    "com.cltv.mal",
    "com.mediatek.tis",
    "com.mediatek.tv.service",
    "com.mediatek.tv.agent",
    "io.github.devjardins.sidespeed",
}

CORE_PREFIX_OVERLAY = "com.google.android.overlay.modules"
CORE_PREFIX_PROVIDERS = "com.android.providers."

CORE_PROVIDER_OK = {
    "com.android.providers.calendar",
    "com.android.providers.contacts",
    "com.android.providers.userdictionary",
    "com.android.providers.blockednumber",
}

HOME_PACKAGES = {
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
    "com.mitv.tvhome",
    "com.mitv.tvhome.atv",
    "com.amazon.tv.launcher",
    "com.tcl.tv.tclhome_passive",
    "com.tcl.tv.tclhome_master",
    "com.cvte.tv.launcher",
    "com.cvte.tv.launcher.highend",
    "com.cvte.tv.launcher.space",
    "com.mediatek.tv.launcher.partner",
    "com.hisense.tv.customerlauncher",
    "com.oneplus.tv.launcher",
    "com.sony.dtv.tvxlauncher.titlelist",
    "com.dangbei.TVHomeLauncher",
    "com.yandex.tv.home",
    "com.verizon.tv.launcher",
    "com.skyworthdigital.sky2dlauncherv4",
    "com.haier.launcher.haiermatrix",
    "com.whaletv.launcher",
    "com.smart.tv.launcher",
    "com.ht1.launcher",
    "com.jm.jmlauncher",
    "com.kgzn.mslauncher",
    "com.vt.tv.customerlauncher",
    "org.droidtv.nettv_launcherapp",
    "org.droidtv.smarttvhome",
    "ru.sberdevices.starlauncher",
    "com.xiaomi.eetv.litehome",
    "com.channel4.youviewandroidlauncher",
    "com.itv.youviewandroidlauncher",
    "mitv.service",
}

SETUP_PACKAGES = {
    "com.google.android.tungsten.setupwraith",
    "com.smartdevice.setupwraith",
    "com.android.managedprovisioning",
    "com.amazon.tv.ffsprovisioneeclient",
    "com.amazon.whisperjoin.wss.wifiprovisioner",
    "com.softwinner.provision",
}


def is_core(pkg: str) -> bool:
    if pkg in CORE_EXACT:
        return True
    if pkg.startswith(CORE_PREFIX_OVERLAY):
        return True
    if pkg.startswith(CORE_PREFIX_PROVIDERS) and pkg not in CORE_PROVIDER_OK:
        return True
    return False


def decode_text(raw: bytes) -> str:
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        text = raw.decode("utf-16")
    else:
        text = raw.decode("utf-8-sig")
    return text.replace("\r\n", "\n").replace("\r", "\n")


def parse_debloat(text: str) -> list[dict]:
    """Return package entries in source order (first wins on dedupe)."""
    seen: set[str] = set()
    out: list[dict] = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        optional = False
        if line.startswith("#"):
            line = line[1:].strip()
            optional = True
        body, _, note = line.partition("#")
        words = body.split()
        if not words or not PACKAGE_RE.fullmatch(words[0]):
            continue
        if optional and len(words) != 1:
            continue
        pkg = words[0]
        if not PACKAGE_RE.fullmatch(pkg):
            continue
        if is_core(pkg):
            continue
        if pkg in seen:
            continue
        seen.add(pkg)
        note = note.strip()
        out.append(
            {
                "package": pkg,
                "note": note,
                "home": pkg in HOME_PACKAGES,
                "setup": pkg in SETUP_PACKAGES,
                "optional": optional,
            }
        )
    return out


def variant_name(path: Path) -> str:
    name = path.name
    if name == "debloat.txt":
        return "default"
    if name.startswith("debloat-") and name.endswith(".txt"):
        return name[len("debloat-") : -len(".txt")]
    raise ValueError(f"unexpected debloat file name: {name}")


def list_file_name(folder_id: str, variant: str) -> str:
    if variant == "default":
        return f"{folder_id}.json"
    return f"{folder_id}--{variant}.json"


def read_tv_json(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("tv", "os", "brand"):
        if key not in data:
            raise ValueError(f"{path}: missing {key}")
    return data


def load_match_file(script_dir: Path) -> dict:
    path = script_dir / "extras-match.json"
    return json.loads(path.read_text(encoding="utf-8"))


def import_checkout(checkout: Path, out_dir: Path, match_by_id: dict) -> str:
    examples = checkout / "examples"
    if not examples.is_dir():
        raise SystemExit(f"{examples}: not a directory")
    commit = subprocess.check_output(
        ["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True
    ).strip()
    lists: list[dict] = []
    list_payloads: dict[str, list] = {}

    folders = sorted(p for p in examples.iterdir() if p.is_dir())
    for folder in folders:
        folder_id = folder.name
        tv_path = folder / "tv.json"
        if not tv_path.is_file():
            continue
        tv = read_tv_json(tv_path)
        debloat_files = sorted(folder.glob("debloat*.txt"))
        match = match_by_id.get(folder_id)
        for debloat in debloat_files:
            variant = variant_name(debloat)
            raw = debloat.read_bytes()
            entries = parse_debloat(decode_text(raw))
            file_name = list_file_name(folder_id, variant)
            list_payloads[file_name] = entries
            lists.append(
                {
                    "id": folder_id,
                    "variant": variant,
                    "file": file_name,
                    "tv": tv["tv"],
                    "os": tv["os"],
                    "brand": tv["brand"],
                    "match": match,
                }
            )

    lists.sort(key=lambda e: (e["id"], e["variant"]))
    index = {"upstream_commit": commit, "lists": lists}
    extras_dir = out_dir / "extras"
    if extras_dir.exists():
        shutil.rmtree(extras_dir)
    extras_dir.mkdir(parents=True)
    (extras_dir / "index.json").write_text(
        json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    for file_name in sorted(list_payloads):
        (extras_dir / file_name).write_text(
            json.dumps(list_payloads[file_name], indent=2) + "\n", encoding="utf-8"
        )
    license_src = checkout / "LICENSE"
    if license_src.is_file():
        shutil.copy2(license_src, extras_dir / "LICENSE")
    return commit


def main(argv: list[str] | None = None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    if len(argv) != 1:
        print(f"usage: {Path(__file__).name} <cleanyourtv-checkout>", file=sys.stderr)
        return 2
    checkout = Path(argv[0]).expanduser().resolve()
    script_dir = Path(__file__).resolve().parent
    repo = script_dir.parent
    out_assets = repo / "app" / "src" / "main" / "assets"
    match_by_id = load_match_file(script_dir)
    commit = import_checkout(checkout, out_assets, match_by_id)
    print(f"imported {commit} into {out_assets / 'extras'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())