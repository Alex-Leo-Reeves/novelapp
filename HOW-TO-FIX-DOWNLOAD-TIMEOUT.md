# Fix APK Download Timeout on Bad Networks

## Problem

When updating NovelApp over a weak or unstable network, the download fails with a "download timeout" error after a short period.

## Root Cause

In `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/platform/AndroidExternalLinkOpener.kt`, the APK download uses `HttpURLConnection` with a **30-second `readTimeout`**:

```kotlin
val connection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15000
    readTimeout = 30000   // ← this is the problem
    // ...
}
```

`readTimeout` is the maximum time the connection will wait **between consecutive data packets**. On a bad network, gaps between chunks regularly exceed 30s, which kills the download.

This is the **only** limit in the code — no total-download-size cap, no retry budget.

## What To Change

### 1. Increase the read timeout (essential)

Change `readTimeout` from `30000` to `120000` (2 minutes) to handle slow/stuttering connections.

**File:** `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/platform/AndroidExternalLinkOpener.kt`

```kotlin
// Line ~263 (inside installApkUpdate)
val connection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 15000
    readTimeout = 120000   // ← changed from 30000
    instanceFollowRedirects = true
    setRequestProperty("User-Agent", "NovelApp/${AppReleaseConfig.CURRENT_VERSION_NAME}")
}
```

### 2. Add automatic retry (recommended)

Wrap the download loop with a retry mechanism so a single timeout doesn't force the user to tap "Update" again manually.

**Same file, same method.** Replace the `runCatching { ... }` block with a retry wrapper:

```kotlin
// Around line ~67 — replace:
// Thread {
//     runCatching {
//         val updateDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
//         ...
//     }.onFailure { error -> ... }
// }.start()

// With:
Thread {
    var lastError: Throwable? = null
    for (attempt in 1..3) {
        val result = runCatching {
            tryDownloadAndInstallApk(url)  // extract the current block into this function
        }
        if (result.isSuccess) {
            result.getOrThrow()
            return@Thread
        }
        lastError = result.exceptionOrNull()
        if (attempt < 3) {
            val delay = attempt * 5000L  // 5s, 10s between retries
            AppUpdateProgressBus.update(
                AppUpdateProgressState(
                    isActive = true,
                    phase = AppUpdatePhase.Downloading,
                    message = "Retrying download (attempt ${attempt + 1}/3)..."
                )
            )
            Thread.sleep(delay)
        }
    }
    // All retries exhausted — show error
    AppUpdateProgressBus.update(
        AppUpdateProgressState(
            isActive = true,
            phase = AppUpdatePhase.Error,
            message = "Update download failed: ${lastError?.message ?: "try again"}",
            canDismiss = true,
            isError = true
        )
    )
    toast("Update download failed: ${lastError?.message ?: "try again"}", Toast.LENGTH_LONG)
}.start()
```

To keep the file clean, extract the download logic into a helper:

```kotlin
private fun tryDownloadAndInstallApk(url: String): File {
    val updateDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
    val apkFile = File(updateDir, "novelapp-update.apk")
    val tmpFile = File(updateDir, "novelapp-update.apk.part")
    // ... rest of the current download, verify, and blocking logic ...
    return apkFile
}
```

### 3. Optional: Show timeout-specific error message

In the `.onFailure` block (or the retry exhaustion block), distinguish timeout from other failures:

```kotlin
val msg = when (lastError) {
    is java.net.SocketTimeoutException -> "Download timed out — your network may be too slow. Try again on a stronger connection."
    else -> lastError?.message ?: "try again"
}
```

## Files To Edit

| File | Change |
|------|--------|
| `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/platform/AndroidExternalLinkOpener.kt` | Lines ~263: change `readTimeout = 30000` → `readTimeout = 120000` |
| Same file | Add retry loop (3 attempts with 5s/10s backoff) |
| Same file | Optionally extract `tryDownloadAndInstallApk()` helper |

## Testing

1. Build an APK with the changes
2. Install and tap "Update" on a weak network (or simulate slow connection: `adb shell cmd netfwd add slow 5g 50kbps`)
3. Verify the download completes or retries instead of showing an immediate error
