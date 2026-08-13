# PLAN: Bump Android + TV → release V1.43 / TV V1.42

Created: 2026-08-13 · Scope: version bump + release builds + manifest hash/size/notes (NO git push)

## Versions (current → new)
| Channel | File | Current | New |
|---|---|---|---|
| Android phone | composeApp/build.gradle.kts (versionCode/versionName) | 42 / 1.42 | 43 / 1.43 |
| Android TV | tvApp/build.gradle.kts (versionCode/versionName) | 41 / 1.41 | 42 / 1.42 |
| Top-level manifest | site/app-version.json (versionCode/versionName) | 42 / 1.42 | 43 / 1.43 |
| TV manifest | site/app-version.json (tvVersionCode/tvVersionName) | 41 / 1.41 | 42 / 1.42 |
| Desktop | PlatformAppVersion.desktop.kt + packageVersion | 42 / 1.42 (UNCHANGED — not building) | — |
| iOS | iosApp/project.yml | 42 / 1.42 (UNCHANGED — not building) | — |

⚠️ Desktop + iOS stay at 42/1.42 in BOTH build files and manifest so their channels never drift (no false update prompts).

## Files to edit
- [ ] `composeApp/build.gradle.kts` → versionCode 43, versionName "1.43" (packageVersion stays 1.42.0 — desktop unchanged)
- [ ] `tvApp/build.gradle.kts` → versionCode 42, versionName "1.42"
- [ ] `site/app-version.json` → versionCode 43 / versionName "1.43", tvVersionCode 42 / tvVersionName "1.42", new release notes
- [ ] `package.json` → version 1.43.0 (bump-script convention)

## Build steps
- [ ] `./gradlew :composeApp:assembleRelease` (Android phone APK)
- [ ] `./gradlew :tvApp:assembleRelease` (Android TV APK)
- [ ] Fix any build errors as they appear
- [ ] Rename/output APKs: `composeApp/build/outputs/apk/release/novelapp-android.apk`, `tvApp/build/outputs/apk/release/novelapp-androidtv.apk`

## After building
- [ ] `sha256sum` + `stat --format=%s` for both APKs
- [ ] Update `apkSha256`/`apkBytes` and `tvApkSha256`/`tvApkBytes` in site/app-version.json
- [ ] Update `releaseNotes` (Android) + `tvReleaseNotes` (TV)
- [ ] Validate JSON parses
- [ ] Save progress to knowledge graph memory

## NOT doing
- No desktop/iOS builds or version bumps
- No git commit / push of the version bump
- No rebase / revert anywhere
- Permanent *Url fields untouched
