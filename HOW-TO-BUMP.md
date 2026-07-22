# How to bump the app version

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
  "versionCode": 30,         // ← must match build.gradle.kts
  "versionName": "1.29",     // ← must match build.gradle.kts
  "apkUrl": "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.29/novelapp-android.apk",
  "apkBytes": 123456789,     // ← update after building
  "apkSha256": "...",        // ← update after building
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

**`AppReleaseConfig.kt`** — it now reads from `PlatformAppVersion` automatically.  
**`AppUpdate.kt`** — the comparison (`versionCode > CURRENT_VERSION_CODE`) works without changes.

## IMPORTANT: apkBytes must match the actual download

When you change the APK download URL (e.g. from Render to GitHub Releases), the manifest's
`apkBytes` must match the *actual* file that will be downloaded at that URL. The download
code does a strict byte-count check (unless a SHA-256 hash is also provided).

**How to get the correct values after building:**

```bash
# Get the actual file size and SHA-256 of the built APK
ls -l novelapp-android.apk
sha256sum novelapp-android.apk

# Then upload it as a GitHub Release and confirm the download at the URL works:
curl -sL "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/vX.Y/novelapp-android.apk" | tee /tmp/check.apk | sha256sum
stat --format=%s /tmp/check.apk
```

Update both `apkBytes` and `apkSha256` in `site/app-version.json` with these values.

## Known pitfalls

- **`apkBytes` mismatch** — If this doesn't match what the actual download returns, the app
  will download to 100% then fail with "download was incomplete". The SHA-256 hash is the
  real integrity check, but when the byte count is present it's checked first. If the byte
  count doesn't match, the error message is misleading — the download is fine, the manifest
  is just stale. Always re-verify `apkBytes` against the actual file served at the download
  URL before releasing.
- **Android 8+ "Install unknown apps" permission** — The app will prompt the user to enable
  this. If they deny it, updates will silently fail. The error message now tells them.

## After building

Update these fields in `site/app-version.json`:
- `apkUrl` — update the version tag in the URL
- `apkBytes` — file size of the built APK
- `apkSha256` — run `sha256sum` on the built APK
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
- [ ] Update `site/app-version.json` (versionCode, versionName, apkUrl, release notes)
- [ ] Build the APK
- [ ] Upload APK as GitHub Release
- [ ] Update `apkBytes` + `apkSha256` in site/app-version.json (match the *actual* download)
- [ ] Deploy site/app-version.json to Render
