# How to bump the app version

## Permanent release-channel URLs (NEVER change for a routine release)

| Platform | URL | Replace the binary at… |
|---|---|---|
| Android APK | `https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk` | the GitHub v1.39 release asset |
| Android TV APK | `https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.40/novelapp-androidtv.apk` | the GitHub v1.40 release asset |
| Windows EXE | `https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.41/novelapp-android.exe` | the GitHub v1.41 release asset (the permanent EXE channel) |
| iOS IPA | `https://novelapp1.onrender.com/downloads/novelapp-ios.ipa` | `site/downloads/novelapp-ios.ipa` (workflow pushes it) |

To ship a new build you REPLACE the binary behind these URLs and update
`versionCode` / `versionName` / `releaseNotes` **plus the sha256 + bytes for
every platform**. Do not rewrite the URLs.

> ⚠️ iOS has NO auto-update / auto-install. iOS cannot self-install an IPA
> outside the App Store / device-management profile, so the iOS update button
> only opens the ipaUrl for a manual download. Never add auto-install for iOS.

## Per-platform release tracks — the #1 rule

Each platform compares the manifest's channel version against the version that
platform **actually builds with**. If they drift, that platform shows a false
"update available" prompt on every launch. Keep each pair in sync:

| Platform channel in `site/app-version.json` | Must equal the build version in |
|---|---|
| `versionCode` + `versionName` (Android phone) | `composeApp/build.gradle.kts` → `defaultConfig.versionCode/versionName` |
| `tvVersionCode` + `tvVersionName` | `tvApp/build.gradle.kts` → `defaultConfig.versionCode/versionName` |
| `desktopVersionCode` + `desktopVersionName` | `PlatformAppVersion.desktop.kt` + `compose.desktop.application.packageVersion` |
| `iosVersionCode` + `iosVersionName` | `iosApp/project.yml` → `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION` |

Examples of matching vs broken:
- ✅ Android 42, TV 41, Desktop 42, iOS 42 in the JSON **and** in the builds.
- ❌ JSON says `desktopVersionCode: 41` but the EXE builds with 42 → every
  desktop user sees a spurious update prompt (this exact bug was fixed).

**Only the Android phone APK auto-prompts on a top-level bump.** TV, Windows
EXE, and iOS only prompt when their own channel (`tvVersion*`,
`desktopVersion*`, `iosVersion*`) is bumped. The GitHub `releases/latest`
fallback is Android-only and version-gated, so a routine Android tag never
surfaces on TV / EXE / iOS.

## Files you MUST update (every release)

### 1. `composeApp/build.gradle.kts`
```kotlin
defaultConfig {
    versionCode = 30        // ← increment by 1
    versionName = "1.29"    // ← bump
}
compose.desktop.application {
    nativeDistributions {
        packageVersion = "1.29.0"  // ← match versionName above
    }
}
```

### 2. `site/app-version.json`
```json
{
  "_instructions": "PERMANENT release-channel URLs — do NOT change apkUrl / tvApkUrl / desktopUrl / ipaUrl for a routine release. Per-platform versionCode MUST equal the build's versionCode.",
  "versionCode": 42,
  "versionName": "1.42",
  "apkUrl": "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk",
  "apkSha256": "…",   // ← fill after building
  "apkBytes": 123456789,
  "tvApkUrl": "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.40/novelapp-androidtv.apk",
  "tvApkSha256": "…", // ← fill after building
  "tvApkBytes": 0,
  "desktopUrl": "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.41/novelapp-android.exe",
  "desktopSha256": "…", // ← fill after building
  "desktopBytes": 0,
  "ipaUrl": "https://novelapp1.onrender.com/downloads/novelapp-ios.ipa",
  "ipaSha256": "…",   // ← fill after building (iOS never auto-installs; still recorded)
  "ipaBytes": 0,
  "tvVersionCode": 41,             // MUST match tvApp/build.gradle.kts
  "tvVersionName": "1.41",
  "tvReleaseNotes": [ "..." ],
  "desktopVersionCode": 42,        // MUST match PlatformAppVersion.desktop.kt
  "desktopVersionName": "1.42",
  "desktopReleaseNotes": [ "..." ],
  "iosVersionCode": 42,            // MUST match iosApp/project.yml
  "iosVersionName": "1.42",
  "iosReleaseNotes": [ "..." ],
  "releaseNotes": [ "..." ],
  "forceUpdate": false
}
```

### 3. `composeApp/src/desktopMain/kotlin/.../platform/PlatformAppVersion.desktop.kt`
```kotlin
actual object PlatformAppVersion {
    actual val versionCode: Int = 30      // ← match
    actual val versionName: String = "1.29"  // ← match
}
```

## What you DON'T need to touch anymore

**`AppReleaseConfig.kt`** — it reads from `PlatformAppVersion` automatically.
**`AppUpdate.kt`** — the comparison (`versionCode > CURRENT_VERSION_CODE`) works without changes.

## Per-platform integrity AFTER building

Every platform verifies its download against the manifest's `sha256` + `bytes`.
While those are empty (`""` / `0`) the check is SKIPPED for that platform. After
you build, fill them so the installers verify the download:

```bash
sha256sum novelapp-android.apk          # → fill apkSha256
stat --format=%s novelapp-android.apk   # → fill apkBytes

sha256sum novelapp-androidtv.apk        # → fill tvApkSha256
stat --format=%s novelapp-androidtv.apk # → fill tvApkBytes

sha256sum novelapp-windows.exe          # → fill desktopSha256
stat --format=%s novelapp-windows.exe   # → fill desktopBytes

sha256sum novelapp-ios.ipa              # → fill ipaSha256
stat --format=%s novelapp-ios.ipa       # → fill ipaBytes
```

Then upload/overwrite the binary behind its permanent channel URL and confirm
the download matches what you hashed:

```bash
curl -sL "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk" | tee /tmp/check.apk | sha256sum
stat --format=%s /tmp/check.apk
```

## Known pitfalls

- **Stale `apkBytes` / `apkSha256`** — if the values don't match what the URL
  actually serves, the in-app download fails at verify time ("checksum did not
  match"). Always re-verify after replacing the binary.
- **Android 8+ "Install unknown apps" permission** — the phone and TV apps both
  prompt the user to enable this. If denied, updates silently fail (the dialog
  explains what to allow).
- **Android TV / Android phone are SEPARATE APKs signed with the SAME key.**
  Both installers verify package name, newer version, and same signature before
  allowing an update.

## After building

Update these fields in `site/app-version.json`:
- `versionCode` and `versionName` — the new version
- `apkSha256` / `apkBytes` — Android phone APK (run `sha256sum` / `stat`)
- `tvApkSha256` / `tvApkBytes` — Android TV APK
- `desktopSha256` / `desktopBytes` — Windows EXE
- `ipaSha256` / `ipaBytes` — iOS IPA (informational; no auto-install)
- `releaseNotes` — what changed

Then deploy `site/app-version.json` to Render (its `/app-version.json` endpoint
serves this file and passes every field straight through to the apps).

## How the update check works

1. App fetches `https://novelapp1.onrender.com/app-version.json`
2. Compares `server.versionCode` vs `PlatformAppVersion.versionCode`
3. If server > app → shows "Update available" with a per-platform button
   - Android phone → in-app download + SHA-256/size verify + package installer
   - Android TV → in-app download + SHA-256/size verify + package installer
   - Windows → in-app download + SHA-256/size verify + launch installer
   - iOS → opens the ipaUrl (manual download; no auto-install)
4. If server == app → "You are up to date"

## Checklist

- [ ] Bump `versionCode` (+1 from previous)
- [ ] Bump `versionName` in build.gradle.kts
- [ ] Bump `packageVersion` for Desktop
- [ ] Update `PlatformAppVersion.desktop.kt`
- [ ] Update `site/app-version.json` (versionCode, versionName, release notes)
- [ ] Build the APK (phone), TV APK, Windows EXE, iOS IPA
- [ ] Upload each binary to its PERMANENT channel URL (v1.39 asset / v1.40 asset / Render downloads)
- [ ] Update sha256 + bytes for EVERY platform in site/app-version.json to match the *actual* downloaded files
- [ ] Deploy site/app-version.json to Render
