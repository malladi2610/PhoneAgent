# Tracking upstream (google-ai-edge/gallery)

PhoneAgent is a fork of https://github.com/google-ai-edge/gallery. Upstream changes are merged by hand.

## Merge procedure
```bash
git fetch upstream
git switch main && git merge upstream/main     # resolve conflicts using the touch-point list below
cd Android/src && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
The `upstream` remote can only fetch: its push URL is set to `no-push-to-upstream`.

## Where our code lives (never conflicts)
- `Android/src/pa-*/`: PhoneAgent modules
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/phoneagent/`: app-side glue
- `docs/superpowers/`, `docs/research/`, `UPSTREAM.md`
- `scripts/fetch-wakeword-assets.sh`: fetches the sherpa-onnx AAR and wake-word models (pinned sha256, gitignored); run it before building

## Touch points in upstream files (keep each one small; add new ones here)
| File | Change |
|---|---|
| `Android/src/app/build.gradle.kts` | `applicationId = "com.malladi2610.phoneagent"` |
| `Android/src/app/src/main/res/values/strings.xml` | `app_name` = "PhoneAgent" |
| `Android/src/settings.gradle.kts` | `pa` version catalog (`gradle/pa.versions.toml`) + `include(":pa-core", ":pa-tools", ":pa-voice", ":pa-wakeword")` |
| `Android/src/app/build.gradle.kts` | `implementation(project(":pa-*"))` for the four modules |
| `Android/src/settings.gradle.kts` | `exclusiveContent` repository `pa-wakeword/localrepo` for group `com.k2fsa.sherpa.onnx` (sherpa-onnx AAR, created by `scripts/fetch-wakeword-assets.sh`) |
