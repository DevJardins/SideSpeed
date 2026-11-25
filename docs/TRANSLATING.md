# Translating SideSpeed

Thank you for helping translate SideSpeed. This guide tells you where to work and what to watch for.

## Where to translate

Translate in the Weblate project:

<https://hosted.weblate.org/projects/sidespeed/>

<!-- Owner: confirm the Weblate project link above before sharing this guide. -->

Make every change in Weblate only. Never edit the translation files in git by hand. Weblate sends the changes as pull requests, and a check reviews them.

## How the app writes

The app uses plain, short English in sentence case. It says what is true and nothing more. Match that tone. Keep sentences short. Do not add detail the English does not give. Do not soften warnings.

## Glossary

Use these terms the same way every time. The first column is the English term. The second column says what it means.

| Term | What it means |
| ---- | ------------- |
| Trusted Network | A network the user marked as safe. The companion only looks for TVs there. Never write this as home network. |
| Home | The TV's main screen, and the app that shows it. Where a string's note says Keep: Home, leave it in English. |
| Compile | Preparing an app on the TV so it opens faster. |
| Companion | SideSpeed Companion, the phone or computer app that starts the helper and checks TVs. |
| My Apps | The list of apps installed on the TV. |
| Tools | The TV screen with device helpers: free space, animations, extras, and home screen. |
| Extras | Preinstalled apps from the TV maker. They can be turned off and back on. |
| Helper | The small on-TV program that does the work. It starts over an ADB connection. |

## Words that stay in English

Never translate these names. Some English strings also carry a Keep note. Keep those words exactly as written, with the same capitals.

SideSpeed, SideSpeed Companion, Fire TV, Google TV, Tailscale, Downloader, Android, ADB, GitHub, F-Droid, IzzyOnDroid, CleanYourTV, DevJardins, Ko-fi, APK, GPL-3.0, Google, Creative Commons 3.0 Attribution License, Allow debugging, Windows, PowerShell, Terminal, Command Prompt, adb shell, Fire OS.

## What never to translate

Leave these exactly as they are:

- Placeholders such as `%1$s` and `%2$d`. Keep every placeholder, in the same order, with the same number and letter.
- Brand and product names from the list above.
- Addresses, ports, versions, host names, and package names.
- The exact button and setting names the text tells you to match.

Never name or recommend apps. The English text never suggests an app to install, and neither should a translation. Write "Trusted Network" as the glossary above says.

## Apostrophes, quotes and percent signs

The two apps read their files differently.

- TV app: write `\'` for an apostrophe, `\"` for a quote, and `%%` for a percent sign.
- Companion app: write `'`, `"` and `%` as they are, with no backslash and no doubled `%`.

## How a language ships

A language ships in the app once 70% of its strings are translated. Below that it stays out, and the app shows English for the missing strings. English fills any gaps, so a partly translated language still works.

## Review

Anyone can translate. A second person approves each change before it counts. To review for a language you know well, ask for reviewer rights by opening a GitHub issue and naming the language.

## Safety strings

These strings warn about security or data loss. Their English starts with "Safety warning:". Translate their meaning exactly. Take extra care with them, and ask a reviewer to check them.

TV app:

- `setup_lan_warning`

Companion app:

- `first_run_body`
- `first_run_tailnet`
- `home_tv_unproven`
- `pair_classic_ack`
- `settings_tailnets_note`

## For maintainers: spot check

Each translation pull request carries a summary from the translation check. Read it first.

Weblate sends one pull request per component, not one per language. The summary groups the changes by language.

For a language you cannot read, paste a few changed safety strings into any translator and compare the meaning with the English. Then merge. This takes five minutes and is not a full review.
