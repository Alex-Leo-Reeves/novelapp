# NovelApp Update Failure — Root Cause Analysis

## Summary

When you release a new version to GitHub Releases and the app downloads to 100% but fails with "Update failed", the root cause is on the Render-hosted server (`server/index.js`). Two hardcoded references to the old GitHub Release URL (`v1.38`) override the correct URL from `site/app-version.json`, causing the app to:

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
        versionCode: 25,
        versionName: "1.24",
        apkUrl: `${PUBLIC_APP_URL}/downloads/novelapp-android.apk`,
        ...
    };

    if (fs.existsSync(appVersionPath)) {
        const parsed = JSON.parse(fs.readFileSync(appVersionPath, "utf8"));
        payload = {...payload, ...parsed };  // Merges versionCode, versionName, apkUrl, etc.
    }

    // If APK is not on disk, REPLACES apkUrl with hardcoded value ⚠️
    if (fs.existsSync(apkPath) && fs.statSync(apkPath).isFile()) {
        payload.apkBytes = stat.size;
        payload.apkSha256 = crypto.createHash("sha256").update(buffer).digest("hex");
    } else {
        payload.apkUrl = APK_EXTERNAL_URL;   // ← HARDCODED v1.38 OVERRIDE
    }

    return payload;
}
```

**Result**: The app receives:
- ✅ `versionCode: 39` (from `site/app-version.json`)
- ✅ `versionName: "1.39"` (from `site/app-version.json`)
- ❌ `apkUrl: "https://github.com/.../v1.38/novelapp-android.apk"` (overwritten by server)
- ✅ `apkBytes: 192497865` (from JSON, correct for v1.39)
- ✅ `apkSha256: "016dd8..."` (from JSON, correct for v1.39)

### Step 3 — App sees an update is available
In `AppUpdateManifest.isAvailable` (AppUpdate.kt:24):

```kotlin
val isAvailable: Boolean
    get() = versionCode > CURRENT_VERSION_CODE     // 39 > current → true
            && versionName != CURRENT_VERSION_NAME // "1.39" != current → true
```

Update shows as available ✅

### Step 4 — User clicks "Download"
In `YouScreen.kt`, the `onDownload` callback passes `state.manifest.apkUrl` to `linkOpener.open()`.

`AndroidExternalLinkOpener.open()` detects `.apk` extension and calls `installApkUpdate("https://github.com/.../v1.38/novelapp-android.apk")`.

### Step 5 — Download validates against wrong manifest
In `downloadAndInstallApk()`:
1. Connects to `.../v1.38/novelapp-android.apk`
2. Downloads the **v1.38 APK** (different size and SHA-256 than v1.39)
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

## The Fix

There are exactly **two hardcoded references** that need to change, both in `server/index.js`:

### Fix 1: Remove the APK URL override in `buildAppVersionPayload()` (line ~175)
Remove the `else` branch so the `apkUrl` from `site/app-version.json` is used directly:

```javascript
// Change:
if (fs.existsSync(apkPath) && fs.statSync(apkPath).isFile()) {
    payload.apkBytes = stat.size;
    payload.apkSha256 = digest;
} else {
    payload.apkUrl = APK_EXTERNAL_URL;  // ← DELETE THIS LINE
}
```

### Fix 2: Remove the hardcoded redirect in `serveStatic()` (line ~1930)
Change the redirect URL to use the value from `site/app-version.json` instead of a hardcoded string, or remove the redirect entirely.

## Why `bump-version.sh` doesn't fix this

The bump script (`scripts/bump-version.sh`) updates:
- `composeApp/build.gradle.kts` ✅
- `PlatformAppVersion.desktop.kt` ✅
- `site/app-version.json` ✅
- `iosApp/project.yml` ✅
- `tvApp/build.gradle.kts` ✅
- `package.json` ✅

It does **NOT** update:
- ❌ `APK_EXTERNAL_URL` constant in `server/index.js` (line ~101)
- ❌ The hardcoded redirect URL in `serveStatic()` (line ~1930)

So every release, the server sends the correct version name and code but points the download at the old v1.38 file.

## Why the user sees "Update failed"

The download reaches 100% **of the OLD APK** (say 180MB for v1.38). The progress bar shows `(downloadedBytes * 100 / expectedBytes)`. Since v1.39 expects 192MB, the bar reaches ~94%. But after the download finishes, the Android code checks:

```kotlin
if (expectedBytes > 0L && downloadedBytes != expectedBytes) {
    error("download was incomplete (expected $expectedBytes bytes, got $downloadedBytes)")
}
```

And it reports "Update failed". The user sees the toast message at 100% because v1.38 completed downloading, but the validation immediately rejects it.

## Checklist for the next release

After bumping the version and deploying the updated `site/app-version.json` to Render, also verify:

1. ✅ Update `site/app-version.json` with correct `apkUrl` pointing to the new GitHub Release
2. ✅ Ensure `apkBytes` and `apkSha256` match the actual uploaded file
3. ❌ Remove the hardcoded `APK_EXTERNAL_URL` in `server/index.js` so it doesn't override the manifest
4. ❌ Remove the hardcoded redirect in `serveStatic()` in `server/index.js`

Until Fix 1 and Fix 2 are applied, **every release** will fail after downloading because the server always points to v1.38 regardless of what the manifest says.