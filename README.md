# NovelApp

NovelApp is a Kotlin Multiplatform entertainment reader built with Compose Multiplatform. The app combines novel discovery, manga reading, anime playback, favorites, themed reading, and AI-assisted narration/OCR flows behind a shared Kotlin UI.

## Project Status

This repository is an early application build. Android is the most complete target. iOS scaffolding exists through Kotlin Multiplatform and the `iosApp` shell, but iOS secret injection and release hardening still need to be completed before a production launch.

## Features

- Novel, manga, and anime discovery flows
- Detail screens with chapter/episode navigation
- Full-screen novel reader and manga viewer
- Anime video player support through Android Media3/ExoPlayer
- Favorites screen
- Opening animation with developer contact
- Local sign-in/create-account gate with saved user details
- You tab with account details, contact links, and update checking
- Theme switching for reading modes
- Gemini-powered narration controller
- Android OCR support for manga text recognition through ML Kit
- Shared KMP business/UI code with Android and iOS platform actuals

## Tech Stack

- Kotlin Multiplatform
- Compose Multiplatform
- Android Gradle Plugin
- Ktor client
- Kotlin serialization
- Coil image loading
- Room and bundled SQLite
- JSoup scraping/parsing helpers
- ML Kit text recognition on Android
- Media3 ExoPlayer on Android
- Google Secrets Gradle Plugin for Android build secrets

## Repository Layout

```text
.
├── composeApp/                 # Main Kotlin Multiplatform app module
│   └── src/
│       ├── commonMain/         # Shared UI, data, audio, and app state
│       ├── androidMain/        # Android activity, platform APIs, OCR, player
│       └── iosMain/            # iOS platform actuals and entry bridge
├── iosApp/                     # iOS Swift app shell
├── gradle/                     # Gradle version catalog and wrapper files
├── site/                       # Render static website and update manifest
├── render.yaml                 # Render Blueprint for the website
├── local.defaults.properties   # Safe placeholder secret defaults
├── local.properties            # Local machine settings and real secrets; ignored
└── README.md
```

## Prerequisites

- Android Studio with Android SDK installed
- JDK 17 or newer
- Git
- Xcode, only if building/running the iOS target

The Gradle wrapper is included, so a separate Gradle install is not required.

## Secret Configuration

Real credentials must stay out of Git. Put them in the ignored root `local.properties` file.

Example:

```properties
sdk.dir=/path/to/Android/Sdk

GEMINI_API_KEY=your_gemini_api_key
RAPID_API_KEY=your_rapidapi_key
RAPID_API_HOST=webnovel.p.rapidapi.com

MANGADEX_CLIENT_ID=your_mangadex_client_id
MANGADEX_CLIENT_SECRET=your_mangadex_client_secret
MANGADEX_USERNAME=your_mangadex_username
MANGADEX_PASSWORD=your_mangadex_password
```

`local.defaults.properties` contains mock placeholders so the project can sync without publishing secrets. Do not put production keys in that file.

Android reads secrets from generated `BuildConfig` values through `BuildKonfig.android.kt`. iOS currently uses mock constants in `BuildKonfig.ios.kt`; replace that with a secure iOS configuration strategy before shipping iOS builds.

## Run Locally

Clone the repository:

```bash
git clone https://github.com/Alex-Leo-Reeves/novelapp.git
cd novelapp
```

Create `local.properties` with your SDK path and API credentials.

Build a debug APK:

```bash
./gradlew :composeApp:assembleDebug
```

Install the debug APK on a connected Android device or emulator:

```bash
./gradlew :composeApp:installDebug
```

Run a basic Gradle build check:

```bash
./gradlew build
```

## Android Build Outputs

Debug APK:

```text
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Release APK task:

```bash
./gradlew :composeApp:assembleRelease
```

Release APK output:

```text
composeApp/build/outputs/apk/release/
```

Play Store bundle task:

```bash
./gradlew :composeApp:bundleRelease
```

Play Store bundle output:

```text
composeApp/build/outputs/bundle/release/composeApp-release.aab
```

## Website and In-App Updates

The `site/` folder contains a Render-ready static website for the app.

Important files:

```text
site/index.html
site/styles.css
site/app-version.json
site/downloads/README.md
render.yaml
```

The app checks this update manifest:

```text
https://novelapp-site.onrender.com/app-version.json
```

The website and app expect the latest APK at:

```text
https://novelapp-site.onrender.com/downloads/novelapp-latest.apk
```

Before deploying a real public release, build and sign the APK, then place it here:

```text
site/downloads/novelapp-latest.apk
```

Then update `site/app-version.json` with a higher `versionCode`, the visible `versionName`, release notes, and the final APK URL.

Deploy on Render by connecting this Git repository and using the root `render.yaml` Blueprint. The static site publishes from `./site`.

## Release Signing

Before selling or publishing the Android app, configure release signing. Generate a private keystore and keep it outside Git:

```bash
keytool -genkeypair \
  -v \
  -keystore novelapp-release.jks \
  -alias novelapp \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Store the keystore path and passwords in a private local file or CI secret store. Never commit `.jks`, `.keystore`, passwords, API keys, or store credentials.

The current Gradle file does not define a production signing config yet. Add one before distributing release APKs/AABs.

## Versioning

Android version values are in `composeApp/build.gradle.kts`:

```kotlin
versionCode = 1
versionName = "1.0"
```

For every paid release or store upload:

- Increase `versionCode`
- Update `versionName` when the customer-facing version changes
- Tag the release in Git, for example `v1.0.0`

## Commercial Release Checklist

Before selling the app, complete this checklist:

- Replace mock keys with production-safe secret injection
- Configure Android release signing
- Build and test a release APK/AAB, not only debug builds
- Change the package name/application ID if needed for your brand
- Add production app icon, name, splash assets, and store screenshots
- Add privacy policy and terms of use
- Review third-party API terms, rate limits, and paid usage costs
- Verify you have legal rights to distribute or monetize any content sources used by the app
- Remove or replace sources that scrape, stream, or redistribute copyrighted content without permission
- Add crash reporting and analytics only after privacy disclosure is ready
- Test on multiple Android versions and screen sizes
- Decide monetization: paid download, subscription, ads, one-time unlock, or account-based licensing
- Add refund/support contact details for customers

## Important Commercial Note

This app connects to third-party novel, manga, anime, and AI services. Selling an app that scrapes, streams, republishes, or monetizes third-party copyrighted content can violate platform rules, copyright law, or provider terms. For a commercial launch, use licensed content, user-provided content, official APIs with permission, or sources that explicitly allow commercial use.

## iOS Notes

The iOS shell is located in `iosApp/`. The shared Kotlin framework is named `ComposeApp`.

For a production iOS release:

- Replace mock iOS secrets with secure configuration
- Configure signing in Xcode
- Add the correct bundle identifier and app display name
- Test on simulator and physical device
- Prepare App Store privacy nutrition labels and review notes

## Development Tips

- Keep real credentials in `local.properties`
- Keep `local.defaults.properties` safe and generic
- Use `./gradlew :composeApp:assembleDebug` for fast Android checks
- Use `./gradlew build` before committing larger changes
- Do not commit generated build folders, local IDE metadata, keystores, or API secrets

## License

No license has been selected yet. Add a license before selling, sharing, or accepting contributions.
