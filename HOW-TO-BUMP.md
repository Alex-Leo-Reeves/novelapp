# How to bump the app version

## Files you MUST update (every release)

### 1. `composeApp/build.gradle.kts`
```kotlin
defaultConfig {
    versionCode = 29        // ← increment by 1
    versionName = "1.28"    // ← bump
}
compose.desktop.application {
    nativeDistributions {
        packageVersion = "1.28.0"  // ← match versionName above
    }
}
```

### 2. `site/app-version.json`
```json
{
  "versionCode": 29,         // ← must match build.gradle.kts
  "versionName": "1.28",     // ← must match build.gradle.kts
  "apkUrl": "...",
  "apkBytes": 123456789,     // ← update after building
  "releaseNotes": [ "..." ],
  "forceUpdate": false,
  "apkSha256": "..."         // ← update after building
}
```

### 3. `composeApp/src/desktopMain/kotlin/.../platform/PlatformAppVersion.desktop.kt`
```kotlin
actual object PlatformAppVersion {
    actual val versionCode: Int = 29      // ← match
    actual val versionName: String = "1.28"  // ← match
}
```

## What you DON'T need to touch anymore

**`AppReleaseConfig.kt`** — it now reads from `PlatformAppVersion` automatically.  
**`AppUpdate.kt`** — the comparison (`versionCode > CURRENT_VERSION_CODE`) works without changes.

## After building

Update these fields in `site/app-version.json`:
- `apkBytes` — file size of the built APK
- `apkSha256` — run `sha256sum site/downloads/novelapp-android.apk`
- `releaseNotes` — what changed

## How the update check works

1. App fetches `https://novelapp1.onrender.com/app-version.json`
2. Compares `server.versionCode` vs `PlatformAppVersion.versionCode`
3. If server > app → shows "Update available" with a download button
4. If server == app → shows "You are up to date" (green check)

## Checklist

- [ ] Bump `versionCode` (+1 from previous)
- [ ] Bump `versionName` in build.gradle.kts
- [ ] Bump `packageVersion` for Desktop
- [ ] Update `PlatformAppVersion.desktop.kt`
- [ ] Update `site/app-version.json` (versionCode, versionName, release notes)
- [ ] Build the APK
- [ ] Update `apkBytes` + `apkSha256` in site/app-version.json
- [ ] Deploy site/app-version.json to Render
