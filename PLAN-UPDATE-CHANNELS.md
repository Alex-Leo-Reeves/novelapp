# Update Channels — Locked URLs & Per-Platform Integrity

## Permanent release-channel URLs (NEVER change — replace the binary instead)

| Platform | URL | Where defined |
|---|---|---|
| Android APK | `https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.39/novelapp-android.apk` | AppReleaseConfig.ANDROID_DOWNLOAD_URL / site/app-version.json apkUrl |
| Android TV APK | `https://github.com/Alex-Leo-Reeves/novelapp/releases/download/v1.40/novelapp-androidtv.apk` | AppReleaseConfig.ANDROID_TV_DOWNLOAD_URL / site/app-version.json tvApkUrl |
| Windows EXE | `https://novelapp1.onrender.com/downloads/novelapp-windows.exe` | AppReleaseConfig.DESKTOP_DOWNLOAD_URL / site/app-version.json desktopUrl |
| iOS IPA | `https://novelapp1.onrender.com/downloads/novelapp-ios.ipa` | AppReleaseConfig.IOS_DOWNLOAD_URL / site/app-version.json ipaUrl |

## Rules
- iOS: **NO auto-update/auto-install**. Tapping Update opens the ipaUrl (manual download). iOS cannot self-install an IPA.
- Android: in-app download + SHA-256 + size verify + package-installer.
- Android TV: NEW — in-app download + SHA-256 + size verify + package-installer (this task).
- Desktop: NEW — in-app download + SHA-256 + size verify + launch installer (this task).
- Hash/size fields are OPTIONAL per platform (`""` / `0` = skip integrity check until provided). After building, fill `apkSha256/apkBytes`, `tvApkSha256/tvApkBytes`, `desktopSha256/desktopBytes`, `ipaSha256/ipaBytes`.

## Files
- [x] Read all update code (AppUpdate.kt, AppReleaseConfig.kt, openers, TvSharedPlatform.kt, server/index.js, site/app-version.json)
- [ ] commonMain AppReleaseConfig.kt — DO-NOT-CHANGE comment
- [ ] commonMain AppUpdate.kt — per-platform urls/sha/bytes + target helpers + manifest-first fetch
- [ ] App.kt — dialog uses target-specific URL
- [ ] AndroidExternalLinkOpener.kt — comment only
- [ ] DesktopExternalLinkOpener.kt — download+verify+launch installer
- [ ] IosExternalLinkOpener.kt — comment only (no auto-install)
- [ ] TvSharedPlatform.kt — add AppUpdateTarget + canonical URLs (fix v1.4 typo → v1.40)
- [ ] NEW TvUpdateInstaller.kt — TV APK download+verify+install
- [ ] tvApp AndroidManifest + file_paths.xml + build.gradle (core-ktx)
- [ ] TvApp.kt — update dialog + progress dialog
- [ ] site/app-version.json — new fields + placeholders + instructions
- [ ] server/index.js — fix TV URL, add defaults
- [ ] HOW-TO-BUMP.md — document per-platform hash/size
- [ ] site/index.html — Windows link + version copy
- [ ] Compile composeApp + tvApp, verify
