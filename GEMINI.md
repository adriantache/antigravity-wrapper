# Antigravity Wrapper - Agent Instructions & Guidelines

## Versioning & Release Rule (CRITICAL)
Whenever committing and pushing changes intended for release:
1. **Always Check Git Tags**: Fetch remote tags (`git fetch --tags`) and check the highest released tag (`git tag --sort=-v:refname | Select-Object -First 5`).
2. **Always Bump Versions**:
   - `app/build.gradle.kts`: `versionCode` and `versionName`
   - Pattern: Version `1.0.0` -> `versionCode = 10000`, `versionName = "1.0.0"` (e.g. `1.2.5` -> `10205`).
3. **Run Unit Tests**: Ensure `./gradlew testDebugUnitTest` (or `./gradlew test`) passes before committing and pushing.
   - Note on Windows: if `JAVA_HOME` is unset or invalid, use Android Studio's bundled JBR:
     `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew testDebugUnitTest`
4. **Include Version Bumps in Commits**: Never push release-bound code changes without bumping `versionName` and `versionCode`.
5. **Tag & Push to Trigger GitHub Release**:
   - Use bare semver tags without a `v` prefix (e.g. `1.2.5`, matching existing tags).
   - Commit the changes (including the version bump) on `master`, create the tag, and push both:
     ```powershell
     git tag <versionName>
     git push origin master
     git push origin <versionName>
     ```
   - Pushing a matching semver tag automatically triggers `.github/workflows/android.yml` to run `./gradlew test assembleRelease`, sign the release APK, and publish a GitHub Release with auto-generated release notes.
