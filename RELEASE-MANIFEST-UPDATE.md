# PLAN: Update APK hash/size + release notes in site/app-version.json

Created: 2026-08-12 · Scope: site/app-version.json only (NO git push)

## Goal
Point the permanent release channels at the freshly built APKs:

- Android phone APK → composeApp/build/outputs/apk/release/novelapp-android.apk (built 1.42)
- Android TV APK   → tvApp/build/outputs/apk/release/novelapp-androidtv.apk (built 1.41)

Update only:
1. `apkSha256` / `apkBytes`
2. `tvApkSha256` / `tvApkBytes`  (currently EMPTY → TV skips integrity check today)
3. `tvReleaseNotes` (TV build contains new binge-playback work)
4. Keep `releaseNotes` (Android 1.42 notes still accurate — no Android source changes in tree)
5. Leave all permanent `*Url` fields untouched

## Versions verified
| Channel | Manifest | Build file | Match |
|---|---|---|---|
| Android phone | 42 / 1.42 | composeApp/build.gradle.kts 42 / 1.42 | ✅ |
| Android TV | 41 / 1.41 | tvApp/build.gradle.kts 41 / 1.41 | ✅ |

## New hashes (computed 2026-08-12)
- phone: c84f3f830eccad9738a56cce918529e920d2489a707c6501150e91a6cc3a29da · 193,188,861 B
- tv:    4445a12e06b6006117400affb399b3da4f18ef109cc03f267eb3ea17eb8f458e · 231,460,802 B

## Steps
- [x] Gather APK hashes / sizes (sha256sum + stat)
- [x] Verify built versions match manifest channels
- [x] Check knowledge-graph memory for prior release context (empty — no conflicts)
- [x] Update site/app-version.json (hashes + sizes + tvReleaseNotes)
- [x] Validate JSON parses (tmp/validate_manifest.py → deleted after use)
- [x] Save task progress to knowledge graph
