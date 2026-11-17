# Antigravity Wrapper

An Android WebView wrapper app for Antigravity.

## Publishing a Release Remotely (GitHub Actions)

This repository uses `.github/workflows/android.yml` to run tests and build signed release APKs for GitHub Releases.

### 1. One-time Keystore & GitHub Secrets Setup

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
