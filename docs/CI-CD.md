# CI/CD for YK Manager

All pipelines run on GitHub Actions from `.github/workflows/`.

| Workflow | File | Trigger | Output |
|---|---|---|---|
| Android CI | `android-ci.yml` | push to `main`, pull requests, manual | debug APK, unit-test report (as workflow artifacts and a check) |
| Release build | `release.yml` | tag `v*`, manual | signed AAB + APK + SHA-256 sums attached to a GitHub Release; optional Play upload |
| Security scans | `security.yml` | push, PR, Mondays 12:30 UTC | OSV-Scanner SCA, Semgrep SAST, Android Lint report |
| Dependabot | `../dependabot.yml` | weekly | dependency and Actions update PRs (CI runs on each) |

## One-time setup: signing secrets

The release pipeline signs with the same upload keystore used locally (`keystore.jks`, alias `ykmanager`). Add these **repository secrets** under Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `ykmanager` |
| `KEY_PASSWORD` | key password |

With the GitHub CLI:

```bash
gh secret set KEYSTORE_BASE64   --body "$(base64 -w0 keystore.jks)"
gh secret set KEYSTORE_PASSWORD --body "<password>"
gh secret set KEY_ALIAS         --body ykmanager
gh secret set KEY_PASSWORD      --body "<password>"
```

`app/build.gradle.kts` reads `keystore.properties` locally and falls back to the `YK_KEYSTORE_FILE`, `YK_KEYSTORE_PASSWORD`, `YK_KEY_ALIAS`, `YK_KEY_PASSWORD` environment variables that the workflow exports. If neither exists the release variant is signed with the debug key; the release workflow fails a tagged build in that case (a manual dry run only warns).

## Release cadence: batch by area

Ship one release per area of work (for example "OTP slots over NFC" or "passkey provider"), not one per fix. Between releases, fixes land on `main` and are listed under `## Unreleased` at the top of `docs/RELEASE-NOTES.md`; every push to `main` still produces a debug APK artifact from **Android CI** for testing.

Cut the release when all of these hold:

- [ ] The fixes for the area are merged and listed under `## Unreleased`.
- [ ] Unit tests pass in CI, including tests for any parsing or decoding the fixes touched.
- [ ] The CI debug APK was tried on a physical YubiKey over each transport the changes affect (NFC tap, USB), using the steps from the bug report.
- [ ] No other area's work is half-finished on `main`; if it is, finish it or keep it behind its current behaviour.

A fix that only shows up on hardware after release goes into the next batch unless it causes data loss or locks users out; then ship it on its own as a patch release.

## Cutting a release

1. Rename `## Unreleased` in `docs/RELEASE-NOTES.md` to `## vX.Y (versionCode N)` (it becomes the release body) and add a fresh, empty `## Unreleased` above it.
2. Bump `versionCode` (must increase on every Play upload) and `versionName` in `app/build.gradle.kts`.
3. Open a pull request with those two changes (for example "Release v2.28"). `main` is protected (see below), so it can only be merged once the required checks pass. Squash-merge it, then tag the merged commit and push the tag:

   ```bash
   git checkout main && git pull
   git tag v2.28
   git push origin v2.28
   ```

4. The **Release build** workflow verifies the tag equals `versionName`, builds, checks the signature and `applicationId`, and publishes a GitHub Release `v2.28` with:
   - `YKManager-v2.28-playstore.aab` – upload this to Play Console
   - `YKManager-v2.28-release.apk` – side-loadable APK
   - `YKManager-v2.28-SHA256SUMS.txt`

   Tags containing a hyphen (e.g. `v2.28-rc1`) are published as pre-releases.

A manual run (Actions → Release build → Run workflow) from any branch builds the same artifacts without creating a release unless *publish_release* is ticked.

## Branch protection on `main`

`main` only changes through pull requests. Branch protection requires:

- a pull request for every change; no direct pushes, also for admins
- passing checks: **Build & unit test**, **lint**, **semgrep** and **sca / osv-scan**
- linear history (use squash or rebase merges); no force pushes or branch deletion

No review approval is required, so a solo maintainer can merge their own pull request once the checks are green. Branches don't need to be up to date with `main` before merging. Tags are not affected, so pushing a release tag still triggers the **Release build** workflow.

## Optional: automatic Google Play upload

1. In Play Console create a service account with *Release manager* access to the app and download its JSON key.
2. Add the secret `PLAY_SERVICE_ACCOUNT_JSON` (full JSON) — ideally in an environment named `google-play` so you can require manual approval.
3. Either set the repository **variable** `PLAY_TRACK` (`internal`, `alpha`, `beta` or `production`) to upload on every tag, or pick the track in the manual run form.

Uploads are created in **draft** status so nothing goes live without a review in Play Console. The first upload of a brand-new app still has to be done by hand in Play Console; after that the API works.

## Build environment

- `ubuntu-latest` runner with Temurin JDK 17 and the pre-installed Android SDK (AGP downloads platform 37 / build-tools on demand).
- Gradle wrapper 9.9.1 with Android Gradle plugin 9.5.1, validated by `gradle/actions/setup-gradle` (`validate-wrappers: true`); build cache is written only from `main`.
- The release runtime classpath is locked (`app/gradle.lockfile`), so CI builds exactly what was scanned. Refresh it after changing app dependencies with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks`.
- Actions versions: `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`, `actions/download-artifact@v8` (kept current by Dependabot).
- Build-tool dependencies: GitHub's dependency graph also lists libraries the Android Gradle plugin pulls in (reported against `settings.gradle.kts`). Dependabot can't update those, so the root `build.gradle.kts` raises vulnerable ones to patched versions with `buildscript` constraints; check them with `./gradlew buildEnvironment`.
