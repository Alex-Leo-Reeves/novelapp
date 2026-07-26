# NovelApp Update Failure — Root Cause Analysis

## Summary

When a user releases a new version via GitHub Releases and the app downloads to 100% but fails with "Update failed", the root cause is on the Render-hosted server (`server/index.js`). Two hardcoded references to the old GitHub Release URL (`v1.38`) override the correct URL from `site/app-version.json`, causing the app to:

1. Think an update is available (correct versionCode/Name from manifest)  
2. Download the **wrong APK** (old version from hardcoded URL)  
3. Validate against the **new version's** expected byte count and SHA-256  
4. Fail because the downloaded file doesn't match the expected checksum

## The Bug Chain (Step by Step)

### Step 1 — Manifest is requested
- App fetches `https://novelapp1.onrender.com/app-version.json`
- Server calls `buildAppVersionPayload()` (line ~151 in `server/index.js`)

### Step 2 — Server overrides the download URL with a hardcoded old value ⚠️
```javascript
const APK_EXTERNAL_URL = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.38/novelapp-android.apk";
```
At line ~101. This is hardcoded to `v1.38`.

In `buildAppVersionPayload()` (line ~154):
```javascript
function buildAppVersionPayload() {
    const appVersionPath = path.join(SITE_DIR, "app-version.json");
    const apkPath = path.join(SITE_DIR, "downloads", "novelapp-android.apk");
    let payload = {
        versionCode: 25,                     // fallback
        versionName: "1.24",                  // fallback
        apkUrl: `${PUBLIC_APP_URL}/downloads/novelapp-android.apk`,
        ...
    };

    if (fs.existsSync(appVersionPath)) {
        const parsed = JSON.parse(fs.readFileSync(appVersionPath, "utf8"));
        payload = {...payload, ...parsed };  // Merges versionCode, versionName, apkUrl, apkBytes, apkSha256 from JSON
    }

    // If APK is not on disk (Render without LFS), REPLACES apkUrl with hardcoded value ⚠️
    if (fs.existsSync(apkPath) && fs.statSync(apkPath).isFile()) {
        payload.apkBytes = stat.size;        // Uses local file's real size
        payload.apkSha256 = crypto.createHash("sha256").update(buffer).digest("hex");
    } else {
        payload.apkUrl = APK_EXTERNAL_URL;   // ← HARDCODED v1.38 OVERRIDE
    }

    return payload;
}
```

**Result**: The app receives:
- `versionCode: 39` ✅ (from `site/app-version.json`)
- `versionName: "1.39"` ✅ (from `site/app-version.json`)  
- `apkUrl: "https://github.com/.../v1.38/novelapp-android.apk"` ❌ (overwritten by server)
- `apkBytes: 192497865` ✅ (from JSON, correct for v1.39)
- `apkSha256: "016dd8..."` ✅ (from JSON, correct for v1.39)

### Step 3 — App sees an update is available
In `AppUpdateManifest.isAvailable` (AppUpdate.kt:24):
```kotlin
val isAvailable: Boolean
    get() = versionCode > CURRENT_VERSION_CODE  // 39 > current → true
            && versionName != CURRENT_VERSION_NAME   // "1.39" != current → true
```
Update shows as available ✅

### Step 4 — User clicks "Download" 
In `YouScreen.kt`, the `onDownload` callback passes `state.manifest.apkUrl` to `linkOpener.open()`.

`AndroidExternalLinkOpener.open()` detects `.apk` extension and calls `installApkUpdate("https://github.com/.../v1.38/novelapp-android.apk")`.

### Step 5 — Download executes and validates against wrong manifest
In `downloadAndInstallApk()`:
1. Connects to `.../v1.38/novelapp-android.apk`
2. Downloads the **v1.38 APK** (v1.38 has different size and SHA-256 than v1.39)
3. Fetches manifest **again** via `fetchUpdateManifest()` → gets same wrong URL
4. Checks `expectedBytes` = `192497865` (v1.39 size) vs actual downloaded bytes ← **MISMATCH**
5. Fails with: `"download was incomplete (expected 192497865 bytes, got <v1.38 size>)"`

### Secondary Bug — Static file redirect also hardcoded ⚠️
In `serveStatic()` (line ~1930):
```javascript
if (pathname === "/downloads/novelapp-android.apk") {
    const apkPath = path.join(SITE_DIR, "downloads", "novelapp-android.apk");
    if (!fs.existsSync(apkPath)) {
      const redirectUrl = "https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.38/novelapp-android.apk";
      //                                                                   ^^^^ hardcoded
      response.writeHead(302, { location: redirectUrl });
      response.end();
      return;
    }
}
```
Even if the app uses the original `PUBLIC_APP_URL/downloads/novelapp-android.apk` URL, the server redirects to the wrong version.

## Why `bump-version.sh` doesn't fix this

The bump script (`scripts/bump-version.sh`) updates:
- `composeApp/build.gradle.kts`
- `PlatformAppVersion.desktop.kt`
- `site/app-version.json`
- `iosApp/project.yml`
- `tvApp/build.gradle.kts`
- `package.json`

It does **NOT** update:
- `APK_EXTERNAL_URL` constant in `server/index.js` (line ~101)
- The hardcoded redirect URL in `serveStatic()` (line ~1930)

## Why it says "Update failed" after reaching 100%

The download reaches 100% **of the old APK file**. The progress bar shows `(downloadedBytes * 100 / expectedBytes)`. If v1.38 is 180MB and v1.39 expects 192MB, the bar reaches ~94% before the byte count check fails. But the user sees the success message because the download completes — the failure happens during **post-download validation**, not during the download itself.

## The Fix

Two things need to change in `server/index.js`:

1. **Remove the hardcoded `APK_EXTERNAL_URL` constant and the `serveStatic` redirect entirely**, and instead use the `apkUrl` from `site/app-version.json` directly.

2. **Or**: Update both hardcoded URLs during every release (but this is fragile — the bump script should do it).

## Three Files That Need to Change

| File | What to Change |
|------|---------------|
| `server/index.js` line ~101 | Remove or parameterize `APK_EXTERNAL_URL` |
| `server/index.js` line ~154 in `buildAppVersionPayload()` | Don't overwrite `payload.apkUrl` when APK not on disk; keep whatever is in `site/app-version.json` |
| `server/index.js` line ~1930 in `serveStatic()` | Remove the hardcoded redirect, or use the same URL from `site/app-version.json` |
| `scripts/bump-version.sh` | Add a step to update the `site/app-version.json` `apkUrl` field (it already does this for versionCode/Name, but should verify the apkUrl is correct) |

## The Cleanest Fix

In `buildAppVersionPayload()`, simply remove the `else` branch that overwrites `payload.apkUrl`:

```javascript
function buildAppVersionPayload() {
    ...
    // Never overwrite apkUrl — let site/app-version.json be the single source of truth
    // The old code that set payload.apkUrl = APK_EXTERNAL_URL when file wasn't on disk
    // is what caused this bug. Remove that else branch entirely.
    return payload;
}
```

And remove the hardcoded redirect in `serveStatic()`. The `site/app-version.json` already contains the correct GitHub Release URL for the current version — trust it.
