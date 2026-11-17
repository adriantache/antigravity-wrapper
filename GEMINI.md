# Antigravity Wrapper - Agent Instructions & Guidelines

## Versioning & Release Rule (CRITICAL)
Whenever committing and pushing changes intended for release:
1. **Always Check Git Tags**: Fetch remote tags (`git fetch --tags`) and check the highest released tag (`git tag --sort=-v:refname | Select-Object -First 5`).
2. **Always Bump Versions**:
   - `app/build.gradle.kts`: `versionCode` and `versionName`
   - Pattern: Version `1.0.0` -> `versionCode = 10000`, `versionName = "1.0.0"`.
3. **Run Unit Tests**: Ensure `./gradlew testDebugUnitTest` (or `./gradlew test`) passes before committing and pushing.
4. **Include Version Bumps in Commits**: Never push release-bound code changes without bumping `versionName` and `versionCode`.
