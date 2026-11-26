# Releasing

1. **Merge translations.** Merge the open Weblate PRs whose checks pass. Use a merge commit, never squash or rebase, or Weblate locks up (see [TRANSLATING.md](TRANSLATING.md)).
2. **Bump the version.** Change only `sidespeed.versionName` in `gradle.properties`. Every other version number comes from it.
3. **Write release notes** in `.github/release-notes/v<version>.md`. List the included languages, and any that show some English, from the build's language check.
4. **Tag.** Merge to `main`, then run `git tag v<version> && git push origin v<version>`.
5. **Check the draft.** CI builds everything and makes a draft release. It fails if an APK isn't signed with the project key.
6. **Publish** the draft.
7. **Update the website and README.** On sidespeed.app, update the changes page and What's new.

## Fixed file names

Downloader code 3503400 and the sidespeed.app links always fetch `releases/latest/download/<name>`. Every release must include all of these, named exactly like this:

| File | Used by |
|---|---|
| `SideSpeed.apk` | Downloader 3503400, `/tv` |
| `SideSpeed-Companion.apk` | `/companion.apk` |
| `SideSpeed-Companion-macos-arm64.dmg` | `/mac` |
| `SideSpeed-Companion-windows-x64.msi` | `/windows` |
| `SideSpeed-Companion-linux-amd64.deb` | `/linux` |
| `SideSpeed-Companion-linux-x86_64.rpm` | `/linux-rpm` |

CI uploads these next to the versioned files and `SHA256SUMS.txt`.

## How versions are worked out

- Android `versionCode` (TV and Companion): `major*10000 + minor*100 + patch`.
- Desktop package version: `(major+1).minor.patch`.
