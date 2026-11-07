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
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/GalleryApplication.kt` | (Integration) one line in `onCreate`: `PhoneAgentGraph.install(this)` |
| `Android/src/app/build.gradle.kts` | (Integration) `androidResources { noCompress += listOf("onnx") }` for wake-word models |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTools.kt` | Track B: `phoneToolsBridge` (lazy `PhoneToolsGalleryBridge.create(context)`) appended to `getAvailableTools()` so it gets `onAttach`; `getLiteRtToolProviders()` override = other tools via `tool(it)` + `phoneToolsBridge.providers()` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/tools/ToolAction.kt` | Track B: `ToolActionName.CONFIRM_PHONE_TOOL` + `ConfirmToolCallAction(toolName, summary, result: CompletableDeferred<Boolean>)` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt` | Track B: `currentPhoneConfirmAction` state, `is ConfirmToolCallAction` branch in the action loop, and `PhoneToolConfirmDialog` (Allow/Deny) render block after the MCP dialog |
| `Android/src/settings.gradle.kts` | `exclusiveContent` repository `pa-wakeword/localrepo` for group `com.k2fsa.sherpa.onnx` (sherpa-onnx AAR, created by `scripts/fetch-wakeword-assets.sh`) |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelEnums.kt` | (Track A) `RuntimeType.OLLAMA_CLOUD` (`@SerializedName("ollama_cloud")`; no repository download, auto-"downloads" on startup, no top-level download/delete, no language packs) |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/ModelHelperExt.kt` | (Track A) `isOllamaCloud` → `OllamaCloudModelHelper` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt` | (Track A) `RuntimeType.OLLAMA_CLOUD to OllamaModelDownloader()` in the secondary constructor's downloader map; `OllamaModelSource.addTo(curTasks, context)` right after `registerAllowlistSpeechModels(...)` in `loadModelAllowlist` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt` | (Track A) `@IntoMap @RuntimeTypeKey(RuntimeType.OLLAMA_CLOUD)` provider for `OllamaModelDownloader` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/sessions/DefaultLlmSessionManager.kt` | (Track A, `isAiCore` site ×2) context compaction skipped for `model.isOllamaCloud` too (`compactContextInternal`, `isTokenLimitReached`): Gallery's compactors assume LiteRT token counts/limits and would truncate a cloud conversation |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Tasks.kt` | (Track A, `isAiCore` site) `isContextCompactSupported` is false for Ollama models (same reason) |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPicker.kt` | (Track A, `isAiCore` site) hide the size/download-status row for Ollama models, as for AICore (they have no file, so it would read "0 B") |
| `Android/src/app/src/main/AndroidManifest.xml` | (Track A) `phoneagent.settings.PhoneAgentSettingsActivity`, own LAUNCHER entry labelled "PhoneAgent setup", own `taskAffinity` |

### `isAiCore` audit (Track A)
Ollama Cloud models use runtime flags identical to AICore's (`downloadsViaRepository=false`,
`autoDownloadsOnStartup=true`, …), and `OllamaModelDownloader` reports `SUCCEEDED` at startup, so
the download/readiness UI (`DownloadAndTryButton`, `ModelItem`, `ModelRadioItem`,
`ModelNameAndStatus`, `TaskModelSelectionViewModel`, `ModelDownloadPlanner`) treats them as ready
without code changes. Of the `isAiCore` call sites:
- **Changed:** `ModelHelperExt` (routing, as a separate `isOllamaCloud` branch),
  `DefaultLlmSessionManager` ×2 and `Tasks.isContextCompactSupported` (compaction off),
  `ModelPicker` (no size row). `Model.isOllamaCloud` is an extension in
  `phoneagent/OllamaModelSource.kt`, so upstream files only gain an import.
- **Unchanged (not reachable or already correct):** `LlmChatTaskModule` ×2, `LlmChatScreen`,
  `XrLanguageTeacher*` ×3 (Ollama models are only registered for Agent Chat);
  `LlmChatViewModel.observeLiveMetrics` (our instance isn't an `LlmModelInstance`, so the tracker
  is null and nothing is observed); `ChatPanel` live metrics (feature flag is off);
  `MessageInputText` ×3 (image limits; Ollama models set `supportImage=false`);
  `ModelAllowlist` ×3 (allowlist JSON only; Ollama models never come from it);
  `MlKitAsrAdvancedGate`/`MlKitAsrService` (AICore version checks, unrelated).
