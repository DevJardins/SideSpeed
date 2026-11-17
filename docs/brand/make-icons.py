#!/usr/bin/env python3
"""Regenerate every SideSpeed icon from the master logo.

Master: docs/brand/sidespeed-logo-2026.png (1024x1024).
Run from the repo root:  uv run --with pillow python docs/brand/make-icons.py

Regenerates: Android launcher foregrounds + legacy icons (app and
companion-android), the Android TV banner, desktop app.png/app.icns/app.ico,
the desktop tray icon, and the in-app logo-mark PNGs. Needs macOS `iconutil`
for the .icns build step.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent.parent
MASTER = Path(__file__).resolve().parent / "sidespeed-logo-2026.png"
FONT = Path(__file__).resolve().parent / "fonts" / "Poppins-SemiBold.ttf"

NAVY = (0, 14, 50)  # #000E32: matches the master's corner pixels (0-1,14-16,48-51)
ROBOT_BOX = (290, 60, 890, 660)  # robot only, no wordmark, no badge (600x600)

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
APP_PREFIXES = [ROOT / "app/src/main/res", ROOT / "companion-android/src/main/res"]


def feathered_mask(size: int, feather: int) -> Image.Image:
    """White mask fading to transparent over `feather` px at every edge."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    for i in range(feather):
        v = round(255 * (i + 1) / feather)
        draw.rectangle([i, i, size - 1 - i, size - 1 - i], fill=v)
    return mask.filter(ImageFilter.GaussianBlur(feather / 3))


def clean_robot() -> Image.Image:
    """Robot-only crop with streak/glow cut ends faded into the navy background."""
    robot = Image.open(MASTER).convert("RGB").crop(ROBOT_BOX)
    w, h = robot.size
    fade = Image.new("L", (w, h), 255)
    px = fade.load()
    margins = {"left": 70, "top": 15, "right": 25, "bottom": 100}
    for y in range(h):
        fy = min(1.0, y / margins["top"], (h - 1 - y) / margins["bottom"])
        for x in range(w):
            fx = min(1.0, x / margins["left"], (w - 1 - x) / margins["right"])
            px[x, y] = round(255 * min(fx, fy))
    navy = Image.new("RGB", (w, h), NAVY)
    navy.paste(robot, (0, 0), fade)
    return navy


GREEN = (142, 244, 24)  # #8EF418: median of the solid 'Speed' pixels in the master


def banner_text(width: int) -> Image.Image:
    """'Side' in white + 'Speed' in logo green, drawn 4x and downscaled.

    'Speed' starts exactly at the advance width of 'Side' so the two parts
    stay kerned as one word with no extra gap at the colour change.
    """
    scale = 4
    probe = ImageFont.truetype(str(FONT), 100)
    left, _, right, _ = probe.getbbox("SideSpeed")
    font = ImageFont.truetype(str(FONT), round(100 * width * scale / (right - left)))
    left, top, right, bottom = font.getbbox("SideSpeed")
    img = Image.new("RGBA", (right - left, bottom - top), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.text((-left, -top), "Side", font=font, fill=(255, 255, 255, 255))
    draw.text(
        (-left + draw.textlength("Side", font=font), -top),
        "Speed",
        font=font,
        fill=GREEN + (255,),
    )
    return img.resize((width, round(img.size[1] * width / img.size[0])), Image.LANCZOS)


def foreground(robot: Image.Image, canvas: int) -> Image.Image:
    """Opaque navy foreground with the robot centred in the 66dp safe zone."""
    fg = Image.new("RGB", (canvas, canvas), NAVY)
    side = round(canvas * 0.62)
    small = robot.resize((side, side), Image.LANCZOS)
    fg.paste(small, ((canvas - side) // 2,) * 2, feathered_mask(side, max(2, side // 22)))
    return fg


def main() -> None:
    robot = clean_robot()
    word = banner_text(300)

    for prefix in APP_PREFIXES:
        for name, scale in DENSITIES.items():
            foreground(robot, round(108 * scale)).save(
                prefix / f"mipmap-{name}/ic_launcher_foreground.png"
            )
            robot.resize((round(48 * scale),) * 2, Image.LANCZOS).save(
                prefix / f"mipmap-{name}/ic_launcher.png"
            )

    banner = Image.new("RGB", (640, 360), NAVY)
    banner.paste(robot.resize((250, 250), Image.LANCZOS), (36, 55))
    banner.paste(word, (304, 180 - word.size[1] // 2), word)
    banner.save(ROOT / "app/src/main/res/drawable-xhdpi/banner.png")

    icons = ROOT / "companion-desktop/src/main/resources/icons"
    robot.resize((512, 512), Image.LANCZOS).save(icons / "app.png")
    robot.convert("RGB").save(
        icons / "app.ico", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)]
    )
    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "app.iconset"
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            img = robot.resize((size, size), Image.LANCZOS)
            img.save(iconset / f"icon_{size}x{size}.png")
            img.resize((size * 2,) * 2, Image.LANCZOS).save(iconset / f"icon_{size}x{size}@2x.png")
        subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(icons / "app.icns")], check=True
        )

    tray = Image.new("RGBA", (64, 64), NAVY + (0,))
    rounded = Image.new("L", (64, 64), 0)
    ImageDraw.Draw(rounded).rounded_rectangle([0, 0, 63, 63], radius=14, fill=255)
    tray.putalpha(rounded)
    small = robot.resize((52, 52), Image.LANCZOS).convert("RGBA")
    tray.alpha_composite(small, ((64 - 52) // 2,) * 2)
    tray.save(ROOT / "companion-desktop/src/main/resources/tray.png")

    mark = robot.resize((256, 256), Image.LANCZOS)
    desktop_mark = ROOT / "companion/src/desktopMain/resources/ss_logo_mark.png"
    desktop_mark.parent.mkdir(parents=True, exist_ok=True)
    mark.save(desktop_mark)

    # Android has no working res/ mechanism in this module (no R is generated
    # for androidMain/res or androidMain/assets), so the bytes are embedded.
    import base64
    import io as _io

    buf = _io.BytesIO()
    mark.save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    kt = ROOT / (
        "companion/src/androidMain/kotlin/io/github/devjardins/sidespeed/"
        "companion/ui/SsLogoMarkBytes.kt"
    )
    chunks = [b64[i : i + 128] for i in range(0, len(b64), 128)]
    literal = '" +\n        "'.join(chunks)
    kt.write_text(
        "// Generated by docs/brand/make-icons.py from sidespeed-logo-2026.png - do not edit.\n"
        "package io.github.devjardins.sidespeed.companion.ui\n\n"
        "internal object SsLogoMarkBytes {\n"
        f'    val BASE64: String =\n        "{literal}"\n'
        "}\n"
    )

    print("icons regenerated from", MASTER.name)


if __name__ == "__main__":
    sys.exit(main())
