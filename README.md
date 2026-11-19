# Antigravity Wrapper

An Android WebView wrapper app for [Antigravity](https://antigravity.google), built with Kotlin and Jetpack Compose.

## Features

- **Native Android Experience**: Edge-to-edge dark UI (`#101010` system bars), pull-to-refresh, and a top loading progress indicator.
- **Smart URL & Deep-Link Routing**:
  - Handles `https://antigravity.google`, `*.antigravity.google`, and `antigravity://` deep links.
  - Keeps Antigravity pages, Google authentication flows, and your configured custom server URL inside the WebView while opening external links in your default browser or app.
- **Web-to-Native Notifications**: Bridges web `Notification` and `ServiceWorkerRegistration.showNotification` calls to native Android notifications with tap-to-open deep linking.
- **Floating Control Pill**: A draggable, auto-collapsing floating pill for quick **Refresh** and **Session Settings** access.
- **Session Settings**:
  - Connect to the default `https://antigravity.google` or any custom remote Antigravity URL, with recent session history (up to 10 URLs).
  - **Desktop Mode** toggle (switches between mobile and desktop user agents).
  - **Keep Screen On** toggle.
  - **Pull to Refresh** toggle.
  - **Double-Back to Exit** toggle (also dispatches `Escape` to the web UI to dismiss popups/dropdowns).
  - Clear WebView cache and cookies.
- **Auto-Reconnect**: Monitors network connectivity and automatically reloads the session when internet access is restored.
- **File Uploads**: Supports Android file picker integration for WebView file inputs.

## Tech Stack & Requirements

- **Language**: Kotlin (`2.4.21`)
- **UI**: Jetpack Compose & Material 3
- **Android SDK**: `minSdk 35`, `targetSdk 37`, `compileSdk 37`
- **Gradle**: Android Gradle Plugin `9.5.0`, Java 11

## Local Development

### Prerequisites

- Android Studio (or Android SDK with platform 37 installed)
- JDK 11+

### Build & Test

Run unit tests:

```powershell
.\gradlew testDebugUnitTest
```

Build a debug APK:

```powershell
.\gradlew assembleDebug
```

The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```text
app/src/main/java/com/adriantache/antigravitywrapper/
├── MainActivity.kt                  # App entry point, edge-to-edge setup, back handling, connectivity listener
├── data/
│   └── PreferencesManager.kt        # SharedPreferences wrapper for URL, history, and toggles
├── ui/
│   ├── components/
│   │   ├── AntigravityWebView.kt    # Configured WebView, JS notification polyfill, file chooser
│   │   ├── ConnectionErrorOverlay.kt# Offline / load error overlay with retry
│   │   ├── FloatingControlPill.kt   # Draggable quick-action pill
│   │   ├── SettingsDialog.kt        # Session URL, recent history, and preference toggles
│   │   └── WebNotificationBridge.kt # @JavascriptInterface for native notifications
│   └── theme/                       # Material 3 theme, colors, and typography
└── util/
    ├── NotificationHelper.kt        # Notification channel, intent extraction, URL normalization
    └── UrlPolicy.kt                 # Internal vs. external URL navigation policy
```

## Publishing a Release Remotely (GitHub Actions)

This repository uses `.github/workflows/android.yml` to run tests and build signed release APKs for GitHub Releases.

### 1. One-Time Keystore & GitHub Secrets Setup

Create a release keystore if you don't already have one:

```powershell
keytool -genkeypair -v -keystore keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias antigravitywrapper
```

Base64-encode `keystore.jks` (single line, no wrapping):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore.jks")) | Set-Clipboard
```

Add these 4 repository secrets in GitHub (**Settings → Secrets and variables → Actions → New repository secret**):

| Secret Name | Value |
| --- | --- |
| `KEYSTORE_BASE_64` | Base64 contents of `keystore.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEYSTORE_ALIAS` | Key alias (e.g. `antigravitywrapper`) |
| `RELEASE_KEY_PASSWORD` | Key password |

> Keep `keystore.jks` and `local.properties` out of git (both are in `.gitignore`).

### 2. Triggering a Release Remotely

Bump `versionCode` and `versionName` in `app/build.gradle.kts` (following `1.0.0` → `10000`), commit, and push to `master`/`main`. Then publish using either option:

- **Option A — GitHub Releases UI (or GitHub mobile app)**:
  Go to **Releases → Draft a new release**, create a tag (e.g. `v1.0.0`), and click **Publish release**. GitHub Actions will run tests, build the signed release APK, and attach it to the release.
- **Option B — Actions "Run workflow"**:
  Go to **Actions → Android CI → Run workflow**, enter the tag (e.g. `v1.0.0`), and run it. GitHub Actions will run tests, build the signed release APK, create the GitHub Release with generated release notes, and attach the APK.
