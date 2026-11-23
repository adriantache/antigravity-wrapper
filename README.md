# Antigravity Wrapper

An Android WebView wrapper app for [Antigravity](https://antigravity.google.com/remote?path=%2Fview%2Fremote-control), built with Kotlin and Jetpack Compose.

## How to Use

### 1. Getting the URL

Out of the box, Antigravity Wrapper connects to **`https://antigravity.google.com/remote?path=%2Fview%2Fremote-control`**, the official Antigravity remote control web interface.

- **Official Web Interface (Recommended)**:
  1. Launch the app—it loads `https://antigravity.google.com/remote?path=%2Fview%2Fremote-control` by default.
  2. Sign in with your Google account to access your remote Antigravity sessions.
  > **Note on Google Sign-In:** Android `WebView` maintains its own isolated cookie and session storage, separate from Chrome and your device's system Google accounts. Even if you are already signed in to Google in your browser or on your phone, you will need to log in to your Google account once inside the app (and again if you use **Clear WebView Cache & Cookies**).
- **Custom / Local / Tunneled URL**:
  If you are connecting to a specific session link or a self-hosted/tunneled instance:
  1. Copy the URL from your Antigravity desktop/web session (for example, a `https://antigravity.google.com/...` session link, a Tailscale/tunnel URL, or a LAN address like `http://192.168.x.x:<port>`).
  2. In the app, tap the **floating control pill** (`⋮`) and select the **Settings** (gear) icon.
  3. Paste the URL into **Remote Antigravity URL** and tap **Save & Connect**.
     - If you omit `http://` or `https://`, the app automatically prepends `http://`.
     - You can switch back anytime using **Reset to Default URL** or pick from **Recent Sessions**.
- **Deep Links**:
  The app also registers for `https://antigravity.google.com`, `*.antigravity.google.com`, `https://antigravity.google`, `*.antigravity.google`, and `antigravity://` links, so opening those URLs from notifications or other apps can launch directly into Antigravity Wrapper.

### 2. Controls & Navigation

- **Floating Control Pill (`⋮`)**:
  - **Drag** anywhere on the screen to reposition it.
  - **Tap `⋮`** to expand quick actions (**Refresh** and **Settings**). It auto-collapses after 5 seconds.
- **Back Gesture**:
  - Navigates backward through WebView history when available.
  - At the root page (with **Intercept Back Gestures** enabled), pressing Back sends an `Escape` key event to the web UI to dismiss popups/menus and requires a second press within 2 seconds to exit.
- **Pull to Refresh**: Swipe down from the top of the page to reload.

### 3. Session Settings

Open **Settings** from the floating control pill to configure:

- **Remote Antigravity URL** & **Recent Sessions** (stores up to 10 recent URLs)
- **Intercept Back Gestures**: Send `Escape` to the web session and require double-back to exit (enabled by default)
- **Pull to Refresh**: Swipe down at the top of the page to reload (enabled by default)
- **Keep Screen On**: Prevent display timeout while viewing a session (enabled by default)
- **Desktop User-Agent**: Request the desktop web layout instead of mobile
- **Clear WebView Cache & Cookies**: Reset cached site data and sign-in cookies

## Features

- **Native Android Experience**: Edge-to-edge dark UI (`#101010` system bars), pull-to-refresh, and a top loading progress indicator.
- **Smart URL Routing**: Keeps Antigravity pages, Google authentication flows, and your configured server URL inside the WebView while opening external links in your default browser or app.
- **Web-to-Native Notifications**: Bridges web `Notification` and `ServiceWorkerRegistration.showNotification` calls to native Android notifications with tap-to-open deep linking.
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
