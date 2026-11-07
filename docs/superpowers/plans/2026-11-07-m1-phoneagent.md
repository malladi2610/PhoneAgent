# PhoneAgent M1 (1a agent core + 1b voice/lock screen + 1c wake phrase): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a PhoneAgent app on the S23 that:
- takes typed commands (Agent Chat) and spoken commands (wake phrase, side button, lock screen),
- runs them through an Ollama Cloud model with phone tools,
- answers aloud through Sarvam.

**Architecture:** four Gradle modules built in parallel against the shared contracts in `pa-core`
(commit `7264bd4`), plus a small amount of app-side glue:
- **Track A:** Ollama engine, secrets, settings, Gallery runtime hook
- **Track B:** phone tools, safety gate, ActionLog, chat bridge
- **Track C:** Sarvam voice and the default-assistant role
- **Track D:** sherpa-onnx wake phrase with voice match

The integrator merges the tracks, adds `PhoneAgentGraph`, builds, and installs.

**Tech stack:**
- Kotlin 2.2.21, AGP 9.0.1 (built-in Kotlin), compileSdk 37 / minSdk 31, JVM 11
- OkHttp 4.12.0 (HTTP + WebSocket), kotlinx.serialization 1.7.3, coroutines 1.10.2, Compose (BOM 2026.02.00)
- sherpa-onnx 2.0.1, LiteRT-LM 0.18.1 (Gallery's)

**Spec and references:**
- `docs/superpowers/specs/2026-11-07-m1-ollama-cloud-agent-design.md` (1a)
- §"1b/1c design" below
- `docs/research/sarvam-api.md`
- `docs/research/wakeword-sherpa-onnx.md`
- Ollama wire fixtures in `docs/superpowers/specs/fixtures/`

## Global Constraints
- **Package roots:**
  - `com.malladi2610.phoneagent.{core,tools,voice,wakeword}` for the modules
  - `com.google.ai.edge.gallery.phoneagent` for app glue
- **Contracts are fixed.** Implement the interfaces in `Android/src/pa-core/src/main/java/com/malladi2610/phoneagent/core/**`
  as written. If a contract truly must change, make the smallest additive change and record it in
  your final report.
- **Don't modify** upstream Gallery files except the touch points listed for your track. Add every
  touch point you make to the table in `UPSTREAM.md`.
- **Dependencies:** add new ones to `Android/src/gradle/pa.versions.toml` (the `pa.` catalog). Never
  edit `gradle/libs.versions.toml`.
- **Secrets:** never log, print or persist an API key outside `SecretStore`, including logcat and
  the ActionLog.
- **Ollama:** base URL is `https://ollama.com`; auth is `Authorization: Bearer <key>`; requests use
  `stream:false`; max **8** tool steps per turn; 60 s request timeout; one retry after 1 s on 5xx;
  no retry on 429.
- **Sarvam:** base URLs are `https://api.sarvam.ai` and `wss://api.sarvam.ai`; the header is
  `api-subscription-key` (WebSocket: `Api-Subscription-Key`); a bad key returns **403**.
- **Build environment:**
  - Each worktree needs `Android/src/local.properties` containing
    `sdk.dir=/opt/homebrew/share/android-commandlinetools`, and
    `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
  - Build only your own module(s). The full `:app` build is the integrator's job; disk is tight
    (about 13 GB free).
- **No device access:** tracks must not run `adb` or install anything on the phone. Device testing is
  the integrator's job.
- **Commits:** commit on your worktree branch. Never push. Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01S9TibYQXZ1hgKoMgsr1NAP
  ```

## 1b/1c design (decisions; no separate spec)

**Voice turn (1b):**
- **Recording:** on trigger, play a short earcon, then record 16 kHz mono PCM16 (`VOICE_RECOGNITION`
  source).
- **Speech-to-text:** Sarvam Realtime WebSocket.
  - Parameters: `saaras:v3-realtime`, `stream_type=fast`, `endpointing=vad`, `silence_duration_ms=700`,
    `language_code` = `PaSettings.sttLanguage` (default `auto`).
  - Stop at `transcript.final`. If there's no speech within 6 s, treat it as empty. Cap utterances
    at 20 s.
- **Agent:** `AgentEngine.runTurn` with `PhoneAgentServices.get().phoneTools` and
  `model = PaSettings.voiceModel`.
  - A voice system prompt asks for 1–2 short spoken sentences with no markdown.
  - Within one session the conversation persists for 2 minutes, so follow-ups work
    ("and turn it off").
- **Text-to-speech:** Sarvam REST `/text-to-speech`, model `bulbul:v3`, `speaker = PaSettings.ttsSpeaker`
  (`ishita`), WAV at 24 kHz, played with AudioTrack.
  - TTS language: the detected STT language if TTS supports it, otherwise `PaSettings.ttsLanguage`
    (`en-IN`).
- **Surface:** `VOICE_LOCKED` if `KeyguardManager.isKeyguardLocked`, otherwise `VOICE_UNLOCKED`.
- **`VoiceToolUi`:**
  - `confirm`: speak the summary plus "Shall I go ahead?", listen once, and classify yes/no.
    Yes = yes / yeah / yep / sure / ok / okay / go ahead / do it / send it / haan / ha / ji.
    Anything else = no.
  - `requestUnlock`: speak "Please unlock your phone to continue", then wait up to 30 s for
    `ACTION_USER_PRESENT`.
  - `requestRuntimePermission`: return false; the tool then reports `permission_needed`, and the
    spoken reply tells the user to open PhoneAgent setup.
- **Errors:** `AgentError.userMessage` is spoken.
  - Sarvam 403 → "Your Sarvam key was rejected. Add a valid key in PhoneAgent setup."
  - No voice model → "Choose a model in PhoneAgent setup."
- **Mic coordination:** while a turn runs, `PhoneAgentBus.setVoiceSessionActive(true)`; set it back
  to false in `finally`.
- **Default assistant:** `PaVoiceInteractionService`, `PaVoiceInteractionSessionService` and
  `PaVoiceInteractionSession`.
  - The session's overlay (plain Views) shows Listening… / your words / the answer, works over the
    lock screen, and closes 3 s after speaking finishes.
  - The side-button long press opens it once the user sets the Samsung side button to
    "Digital assistant".
- **`VoiceTurnLauncher` impl:**
  - If our voice-interaction service is the bound default assistant, call `showSession` (visible
    window; this also permits activity launches from tools).
  - Otherwise, run the turn headless with a heads-up notification.

**Wake phrase (1c):**
- **Engine:** sherpa-onnx KWS model `zh-en-3M-2025-12-20` chunk-16, with the phrase encoded
  on-device from the `en.phone` lexicon. Default phrase "Hey Jarvis"; the user can type any phrase
  whose words are all in the lexicon.
- **Voice match:** CAM++ (`3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`).
  - Enrollment takes 5 takes; a take counts only if the KWS fires.
  - Drop leave-one-out outliers.
  - Threshold `T = clamp(min(0.6, minLOO − 0.05), 0.35, 0.7)`.
- **`WakeWordService`:** foreground service, type microphone, notification "Listening for '<phrase>'"
  with a Pause action.
  - Pipeline: AudioRecord in 100 ms chunks → KWS → 2.5 s ring buffer → on hit, embed + `verify`.
  - On pass: release the mic, then call `PhoneAgentBus.voiceTurnLauncher?.startVoiceTurn(WAKE_PHRASE)`.
  - Resume once `voiceSessionActive` goes back to false; 2 s cooldown.
  - Pause while `voiceSessionActive` is true.
  - After a reboot, post a "Tap to resume listening" notification. Android forbids starting a
    microphone foreground service from `BOOT_COMPLETED`.
- **Assets:** sherpa models and the AAR are fetched by `scripts/fetch-wakeword-assets.sh`, pinned by
  sha256 and gitignored.

## Review Focus
1. **Phone locked, wake phrase, "text Mom I'm late".** Nothing is sent until the phone is unlocked
   **and** the user says yes. Test: Track B runner test
   `lockedOutwardInstant_requiresUnlockThenConfirm_neverRunsOnDenial`.
2. **API key missing or rejected** (Ollama 401/403, Sarvam 403). The user gets a spoken or shown
   message, never a crash or silent failure. Tests: Track A
   `OllamaClientTest.unauthorized_mapsToAgentErrorUnauthorized`; Track C
   `VoiceTurnControllerTest.agentError_isSpoken`.
3. **A model that emits malformed or unknown tool calls.** The engine must return an error result to
   the model, not throw, and stop at 8 steps. Tests: Track A `unknownTool_returnsErrorResultToModel`
   and `stepLimit_stopsAtEight`.
4. **The wake phrase fires during an active voice turn, or two triggers race.** There must be only
   one turn at a time. Tests: Track C `secondTrigger_whileActive_isIgnored`; Track D
   `detector_pausedWhileVoiceSessionActive`.
5. **A phrase with words not in the lexicon** (names, slang). Enrollment must reject it with a clear
   message rather than crash or produce an empty keyword. Test: Track D
   `encoder_unknownWord_returnsError`.

---

## Task A: Ollama engine, secrets, settings screen, Gallery runtime hook

**Files:**
- Create in `pa-core/.../core/`:
  - `ollama/OllamaWire.kt`: @Serializable DTOs mirroring the fixtures
  - `ollama/OllamaClient.kt`
  - `ollama/OllamaAgentEngine.kt`
  - `ollama/LiteRtToolSpecs.kt`
  - `secrets/KeystoreSecretStore.kt`
- Create in `app/src/main/java/com/google/ai/edge/gallery/phoneagent/`:
  - `OllamaCloudModelHelper.kt`
  - `LiteRtToolRegistry.kt`
  - `OllamaModelSource.kt`
  - `OllamaModelDownloader.kt`
  - `settings/PhoneAgentSettingsActivity.kt`
- Modify (touch points): `data/ModelEnums.kt` (add `OLLAMA_CLOUD`), `runtime/ModelHelperExt.kt`,
  `ui/modelmanager/ModelManagerViewModel.kt` (downloader map plus the hook after
  `registerAllowlistSpeechModels`), `di/AppModule.kt` (`@IntoMap` downloader),
  `app/src/main/AndroidManifest.xml` (settings activity with its own LAUNCHER entry, label
  "PhoneAgent setup"), `UPSTREAM.md`.
- Tests:
  - `pa-core/src/test/.../ollama/{OllamaWireTest,OllamaClientTest,OllamaAgentEngineTest,LiteRtToolSpecsTest}.kt`
  - `app/src/test/.../phoneagent/LiteRtDescriptionShapeTest.kt`

**Interfaces:**
- **Consumes:** `AgentEngine`, `TurnRequest`, `ToolRegistry`, `ToolContext`, `ChatTurnInfo`,
  `SecretStore`, `PaSettings`, `AgentError` (pa-core).
- **Produces:**
  - `class OllamaClient(http: OkHttpClient, keyProvider: () -> String?, baseUrl: String = "https://ollama.com")`
    - `suspend fun chat(model: String, messages: List<ChatMessage>, tools: List<ToolSpec>): OllamaChatResult`
    - `suspend fun listModels(): List<String>`
  - `data class OllamaChatResult(val message: ChatMessage, val promptEvalCount: Int, val evalCount: Int)`
  - `class OllamaAgentEngine(client: OllamaClient, maxSteps: Int = 8) : AgentEngine`
  - `class KeystoreSecretStore(context: Context) : SecretStore`, using AES-GCM with an Android
    Keystore key; ciphertext stored in SharedPreferences `phoneagent_secrets`.
  - `object LiteRtToolSpecs { fun parse(descriptionJson: String): List<ToolSpec> }`. Risk is always
    `SAFE` here, because gating happens inside the phone-tool bridge.
  - `OllamaCloudModelHelper : LlmModelHelper`, runtime type `OLLAMA_CLOUD`.

- [ ] **A1. Verify the LiteRT description shape.**
  Write `LiteRtDescriptionShapeTest`: build `ToolManager(listOf(tool(SampleToolSet())))` and assert on
  the JSON of `getToolsDescription()`. Use the observed shape (record it in a comment) for
  `LiteRtToolSpecs.parse`.
  Run: `./gradlew :app:testDebugUnitTest --tests '*LiteRtDescriptionShapeTest'`.
  If the JVM test can't load LiteRT classes, instead parse the shape from LiteRT-LM's `Tool.kt`
  `getToolDescription` (ReflectionTool) and note that.
- [ ] **A2. Failing tests for the wire format and client.**
  - `OllamaWireTest`: decoding `ollama-chat-resp1.json` gives 2 tool calls (`set_flashlight {on:true}`,
    `set_volume {stream:"media",percent:30}`), ids `call_rpmyhjsh` and `call_w2puekrw`. Encoding the
    request has the same structure as `ollama-chat-req1.json`, and tool messages carry `tool_name`
    and `tool_call_id`.
  - `OllamaClientTest` (MockWebServer):
    - `sendsBearerKeyAndStreamFalse`
    - `unauthorized_mapsToAgentErrorUnauthorized` (both 401 and 403)
    - `rateLimited_noRetry` (exactly 1 request)
    - `serverError_retriesOnceThenThrowsServer` (exactly 2 requests)
    - `missingKey_throwsMissingKey_withoutRequest`
    - `ioFailure_mapsToNetwork`
    - `listModels_parsesTags`
- [ ] **A3. Implement `OllamaWire` and `OllamaClient`.** Run `./gradlew :pa-core:testDebugUnitTest`;
  all should pass.
- [ ] **A4. Failing tests for the engine** (`OllamaAgentEngineTest`, with a fake client via an
  internal interface or MockWebServer, and a fake `ToolRegistry`):
  - `toolCallsExecutedInOrder_thenFinalText`, using the req/resp fixtures: 2 executions, text
    "Done — …", `toolCallCount = 2`, `StopReason.COMPLETED`
  - `unknownTool_returnsErrorResultToModel`, where the registry returns `ToolOutcome.Error` and the
    next request contains that JSON as a `tool` message
  - `stepLimit_stopsAtEight`, where the model always returns tool calls: exactly 8 tool steps,
    `StopReason.STEP_LIMIT`
  - `cancellation_stopsTurn`
  - `emitsToolStartedAndFinishedEvents`
  - `conversationKeepsHistoryAcrossTurns`
- [ ] **A5. Implement `OllamaAgentEngine`.**
  - It passes `ToolContext(surface, turnId, userText, model, step, ui)` to `registry.execute`.
  - Each result is appended as `ChatMessage(TOOL, content = outcome.toJson().toString(), toolName, toolCallId)`.
  - It sets `conversation.lastTokenCount`.
  - Run the tests; they should pass.
- [ ] **A6. `LiteRtToolSpecs` and `LiteRtToolRegistry`.**
  - `LiteRtToolSpecs.parse` gets a test using the shape recorded in A1.
  - `LiteRtToolRegistry(toolManager: com.google.ai.edge.litertlm.ToolManager) : ToolRegistry` sets
    `ChatTurnInfo` (turnId, userRequest, model, step) before each
    `toolManager.execute(name, gsonArgs)`.
  - It converts Gson to kotlinx via JSON strings, and turns an exception into `ToolOutcome.Error`.
- [ ] **A7. Wire the runtime into Gallery.**
  - **`OLLAMA_CLOUD` enum:** `downloadsViaRepository=false`, `autoDownloadsOnStartup=true`,
    `supportsTopLevelDownload=false`, `supportsDelete=false`, `installsLanguagePacks=false`,
    `@SerializedName("ollama_cloud")`.
  - **`OllamaModelDownloader`:** reports downloaded/succeeded with no work (mirror
    `AICoreModelDownloader`'s contract).
  - **`OllamaCloudModelHelper`:**
    - `initialize` builds a `ToolManager` from the passed `ToolProvider`s, keeps a `Conversation`
      in `model.instance` (system instruction plus a phone preamble with device, local time and
      time zone), and calls `onDone("")`.
    - `runInference` runs `OllamaAgentEngine.runTurn` with `Surface.CHAT`, `LiteRtToolRegistry` and a
      no-op `ToolUi` (gating happens in the bridge), on its own coroutine job.
      `resultListener(text, true, null)` on success; `onError(AgentError.userMessage)` on failure.
    - `stopResponse` cancels the job.
    - `resetConversation` re-seeds the conversation.
    - `cleanUp` drops it.
  - **`OllamaModelSource.addTo(tasks, context)`:** reads the key; if one is present, calls
    `listModels()` and adds a `Model` per name to the `BuiltInTaskId.LLM_AGENT_CHAT` task.
    - Fields: `displayName = "Ollama Cloud · <name>"`, `backendSpec = BackendSpec(runtimeType = OLLAMA_CLOUD)`,
      `isLlm = true`, no download URL.
    - Errors are logged without the key and add nothing.
  - **Audit the 22 `isAiCore` sites** and any file-existence or download checks so `OLLAMA_CLOUD`
    models show as ready and launchable. List each site you changed in `UPSTREAM.md`.
  - Run `./gradlew :app:compileDebugKotlin`; it should succeed.
- [ ] **A8. `PhoneAgentSettingsActivity`** (Compose, separate launcher icon "PhoneAgent setup"):
  - Ollama API key: masked input, Save, and "Test" (calls `listModels`, shows the count or the
    `AgentError.userMessage`).
  - Voice model picker from `listModels()`, saved to `PaSettings.voiceModel`.
  - Sarvam API key: masked input and Save.
  - Buttons that open other tracks' screens by class name (`Intent().setClassName(context, …)`;
    if the activity is missing, show "not installed"):
    - "Phone permissions" → `com.malladi2610.phoneagent.tools.PermissionsActivity`
    - "Voice & assistant" → `com.malladi2610.phoneagent.voice.VoiceSetupActivity`
    - "Wake phrase" → `com.malladi2610.phoneagent.wakeword.WakeEnrollmentActivity`
  - On a key save, ask the model manager to reload the allowlist (or document that the app needs a
    restart).
- [ ] **A9. Finish.** Run `./gradlew :pa-core:testDebugUnitTest :app:compileDebugKotlin`, update
  `UPSTREAM.md`, and commit.

## Task B: Phone tools, safety gate, ActionLog, Agent Chat bridge

**Files:**
- Create in `pa-tools/.../tools/`:
  - `PhoneTools.kt` (`fun createPhoneTools(context: Context): List<PhoneTool>`)
  - `StatusTools.kt`, `SettingsTools.kt`, `AppsTimeTools.kt`, `CommunicationTools.kt`
  - `PhoneToolRunner.kt`, `JsonlActionLog.kt`, `PermissionsActivity.kt`
  - `util/{AppMatcher,WhatsAppLink,VolumeMath,PhoneNumbers,ArgValidator}.kt`
- Modify `pa-tools/src/main/AndroidManifest.xml`:
  - permissions: `ACCESS_NETWORK_STATE`, `READ_CONTACTS`, `CALL_PHONE`, `SEND_SMS`,
    `com.android.alarm.permission.SET_ALARM`, `WRITE_SETTINGS`, `ACCESS_NOTIFICATION_POLICY`
  - `<queries>`: MAIN/LAUNCHER, package `com.whatsapp`, `mailto:`, `geo:`, `tel:`
  - the `PermissionsActivity` declaration
- Create in app: `app/.../phoneagent/{PhoneToolsGalleryBridge.kt,GalleryChatToolUi.kt}`.
- Modify (touch points): `customtasks/agentchat/AgentTools.kt`: include the bridge in
  `getAvailableTools()` so it receives `onAttach`, and override `getLiteRtToolProviders()` to append
  `bridge.providers()`. If you add a confirm action: `tools/ToolAction.kt` and
  `customtasks/agentchat/AgentChatScreen.kt`. Also `UPSTREAM.md`.
- Tests: `pa-tools/src/test/.../{PhoneToolRunnerTest,JsonlActionLogTest,AppMatcherTest,WhatsAppLinkTest,VolumeMathTest,PhoneNumbersTest,ArgValidatorTest}.kt`

**Interfaces:**
- **Consumes:** `PhoneTool`, `ToolSpec`, `ToolRisk`, `ToolOutcome`, `ToolContext`, `ToolUi`,
  `ToolRegistry`, `gateFor`, `Gate`, `ActionLog`, `ActionLogEntry`, `ChatTurnInfo`, `Surface` (pa-core).
- **Produces:**
  - `class PhoneToolRunner(tools: List<PhoneTool>, log: ActionLog, clock: () -> Long = System::currentTimeMillis) : ToolRegistry`
  - `class JsonlActionLog(dir: File, redact: (String) -> String) : ActionLog`, writing
    `dir/YYYY-MM-DD.jsonl` with snake_case keys exactly as in spec §5.6 plus `surface`
  - `fun createPhoneTools(context: Context): List<PhoneTool>`
  - In app: `class PhoneToolsGalleryBridge(runner: ToolRegistry) : ToolDefinition` with
    `fun providers(): List<com.google.ai.edge.litertlm.ToolProvider>`

**Tools.** Names, parameters and risks are exactly as in spec §5.3.
- `SAFE`: `get_device_status`, `set_flashlight(on)`, `set_volume(stream, percent)`,
  `set_brightness(percent)`, `set_do_not_disturb(on)`, `open_settings_panel(panel)`,
  `open_app(name)`, `set_alarm(hour, minute, label?, days?)`, `start_timer(seconds, label?)`,
  `show_location_on_map(query)`
- `SENSITIVE_READ`: `find_contact(name)`
- `OUTWARD_PREFILLED`: `send_whatsapp(number, text)`, `send_email(to, subject, body)`,
  `create_contact(name, phone?, email?)`, `create_calendar_event(title, start, end?)`.
  `start` and `end` are ISO-8601 local date-times.
- `OUTWARD_INSTANT`: `call(number)`, `send_sms(number, text)`
- **Activity launches** use `FLAG_ACTIVITY_NEW_TASK`. Catch the exception, and if the launch is
  blocked return `ToolOutcome.Error("couldn't open <x> from the background")`.

- [ ] **B1. Failing tests for `PhoneToolRunnerTest`** (fake tools that record runs, fake `ToolUi`,
  in-memory `ActionLog`):
  - `safe_runsWithoutPrompt`
  - `outwardInstant_unlocked_confirmAllowed_runs_logsAllowed`
  - `outwardInstant_unlocked_confirmDenied_returnsDenied_doesNotRun_logsDenied`
  - `lockedOutwardInstant_requiresUnlockThenConfirm_neverRunsOnDenial` (unlock=false →
    `NeedsUnlock`, confirm not asked; unlock=true + confirm=false → `DeniedByUser`)
  - `lockedSensitiveRead_unlockRefused_returnsNeedsUnlock`
  - `unknownTool_returnsError`
  - `missingRequiredArg_returnsError_withoutRunning`
  - `toolThrows_returnsError`
  - `everyCall_writesOneLogEntry_withLatencyAndStatus`
- [ ] **B2. Implement `PhoneToolRunner` and `ArgValidator`** (required keys plus primitive types
  against the spec's JSON Schema). Run `./gradlew :pa-tools:testDebugUnitTest`; tests should pass.
- [ ] **B3. Failing tests, then implementation, for `JsonlActionLog`:**
  - `writesOneJsonLinePerEntry_snakeCaseKeys`
  - `truncatesResultTo2048Chars`
  - `redactsSecrets`: `redact` is applied to the whole line, and a known key value must not appear
  - `rollsFilePerDay`
- [ ] **B4. Failing tests, then implementation, for the pure helpers:**
  - `AppMatcher.match(query, labels)`: exact > prefix > contains > fuzzy (edit distance ≤ 2);
    "insta" → Instagram; ambiguous → `Candidates` list.
  - `WhatsAppLink.build("+49 151-234 5678", "see you at 7")` →
    `https://wa.me/491512345678?text=see%20you%20at%207`.
  - `VolumeMath.percentToIndex(30, min=0, max=15)` → 5 (rounded), clamped 0..100.
  - `PhoneNumbers.normalize` keeps a leading `+` and digits only.
- [ ] **B5. Implement the 17 tools** in the four group files with Android APIs (spec §5.3).
  - Runtime permissions: check them, and if missing call `ctx.ui.requestRuntimePermission(p)`; if
    still denied, return `PermissionNeeded(p, false)`.
  - Special permissions (WRITE_SETTINGS, DND access): open the settings page and return
    `PermissionNeeded(p, true)`.
  - `confirmSummary` for `call` / `send_sms` reads like "Send SMS to <number>: '<text>'" and
    "Call <number>".
  - Run `./gradlew :pa-tools:assembleDebug`.
- [ ] **B6. `PermissionsActivity`** (plain Compose-free Views, or a simple Activity): lists each
  runtime permission and its status with a Grant button, plus buttons for "Modify system settings"
  and "Do Not Disturb access".
- [ ] **B7. App bridge.**
  - `PhoneToolsGalleryBridge`: each `PhoneTool` → `tool(object : OpenApiTool { … })`, where
    `getToolDescriptionJsonString()` returns `{name, description, parameters}`.
  - `execute(json)` runs `runBlocking { runner.execute(name, args, ToolContext(Surface.CHAT, ChatTurnInfo.turnId, ChatTurnInfo.userRequest, ChatTurnInfo.model, ChatTurnInfo.step, GalleryChatToolUi(executionContext?.actionChannel))) }`
    and returns `outcome.toJson().toString()`.
  - `GalleryChatToolUi`:
    - `requestRuntimePermission` → `RequestPermissionToolAction` (existing)
    - `confirm` → reuse `AskMcpToolCallPermissionAction(toolName = summary, argument = "")` if its
      dialog reads acceptably (check `McpToolCallPermissionDialog`); otherwise add
      `ConfirmToolCallAction` + `ToolActionName.CONFIRM_PHONE_TOOL` and handle it in
      `AgentChatScreen` with an Allow/Deny dialog
    - `requestUnlock` → true
  - Hook `AgentToolsImpl`. Run `./gradlew :app:compileDebugKotlin`; it should succeed.
- [ ] **B8. Finish.** Run `./gradlew :pa-tools:testDebugUnitTest :app:compileDebugKotlin`, update
  `UPSTREAM.md`, and commit.

## Task C: Sarvam voice, default assistant, lock-screen voice turn

**Files:**
- Create in `pa-voice/.../voice/`:
  - `sarvam/{SarvamSttClient,SarvamTtsClient,SarvamWire}.kt`
  - `audio/{MicRecorder,WavPlayer,Earcon}.kt`
  - `VoiceTurnController.kt`, `VoiceToolUi.kt`, `YesNo.kt`, `PaVoiceLauncher.kt`
  - `assistant/{PaVoiceInteractionService,PaVoiceInteractionSessionService,PaVoiceInteractionSession}.kt`
  - `VoiceSetupActivity.kt`, `PaVoice.kt` (`object PaVoice { fun install(context: Context) }`, which
    sets `PhoneAgentBus.voiceTurnLauncher`)
- Resources and manifest:
  - `pa-voice/src/main/res/xml/pa_voice_interaction.xml`
  - `pa-voice/src/main/AndroidManifest.xml`: `RECORD_AUDIO`, `INTERNET`, `POST_NOTIFICATIONS`,
    the voice-interaction service with `BIND_VOICE_INTERACTION` and meta-data, the session service,
    and `VoiceSetupActivity`
- Tests: `pa-voice/src/test/.../{SarvamWireTest,SarvamTtsClientTest,YesNoTest,VoiceTurnControllerTest}.kt`

**Interfaces:**
- **Consumes:** `PhoneAgentServices`, `AgentEngine`, `TurnRequest`, `Conversation`, `AgentError`,
  `ToolUi`, `Surface`, `PhoneAgentBus`, `VoiceTurnLauncher`, `VoiceTrigger`, `PaSettings`,
  `SecretKey.SARVAM_API_KEY` (pa-core).
- **Produces:**
  - `PaVoice.install(context)`
  - `interface SpeechInput { suspend fun listenOnce(language: String): SttResult }` with
    `data class SttResult(val text: String, val language: String?)`. The real implementation is
    `SarvamSttClient`.
  - `interface SpeechOutput { suspend fun speak(text: String, language: String) }`. The real
    implementation is `SarvamTtsClient` + `WavPlayer`.
  - `class VoiceTurnController(input, output, servicesProvider, keyguard: () -> Boolean, ui: (VoiceUiState) -> Unit)`
    with `suspend fun runTurn(trigger: VoiceTrigger)`.
  - `sealed interface VoiceUiState { Listening; Heard(text); Thinking; Speaking(text); Done; Error(msg) }`

- [ ] **C1. Failing tests, then implementation, for `SarvamWire`:**
  - `audioInputFrame_isBase64Pcm`
  - `parsesTranscriptFinal_withLanguage`
  - `parsesErrorEvent_fatal`
  - `parsesVadEvents`
  - `ttsResponse_decodesFirstAudio`
  - All shapes come from `docs/research/sarvam-api.md`.
- [ ] **C2. `SarvamTtsClientTest`** (MockWebServer):
  - `sendsSubscriptionKeyHeader_andBulbulV3`
  - `forbidden_mapsToUnauthorized("Sarvam")`
  - `quota402_mapsToQuotaExceeded`
  - Then implement `SarvamTtsClient`.
- [ ] **C3. `SarvamSttClient`** (OkHttp WebSocket with the URL and query parameters from the design
  above; 100 ms chunks of 3200 bytes; sends `{"event":"end"}` after `vad.speech_end`; returns at
  `transcript.final`; times out to an empty result after 6 s without speech; caps at 20 s). Plus
  `MicRecorder`. Test the frame building and event state machine with a fake socket if practical.
- [ ] **C4. `YesNoTest`:** each yes-word from the design above → true; "no", "cancel", "stop",
  "nahi", "don't", "" and "maybe later" → false. Then implement.
- [ ] **C5. `VoiceTurnControllerTest`** (fakes for input, output and engine):
  - `happyPath_listensRunsAgentSpeaksReply`
  - `emptyTranscript_speaksDidntCatchThat_noAgentCall`
  - `agentError_isSpoken`
  - `missingVoiceModel_speaksChooseModel`
  - `busFlag_trueDuringTurn_falseAfter_evenOnError`
  - `secondTrigger_whileActive_isIgnored`
  - `locked_usesVoiceLockedSurface`
  - `followUpWithinTwoMinutes_reusesConversation`
  - Then implement `VoiceTurnController` and `VoiceToolUi` (behaviour as described in the design
    above).
- [ ] **C6. Assistant role.**
  - `PaVoiceInteractionService` keeps a static `current` instance while it's connected.
  - The session overlay is built from plain Views showing `VoiceUiState`, and runs the turn when
    shown.
  - `PaVoiceLauncher.startVoiceTurn`: if a session can be shown, `current?.showSession(bundle, 0)`;
    otherwise run the turn headless in a coroutine with an ongoing notification.
  - `VoiceSetupActivity`:
    - request `RECORD_AUDIO`
    - "Make PhoneAgent your digital assistant" (check `RoleManager.isRoleHeld(ROLE_ASSISTANT)`; open
      the default-assistant settings screen, `Settings.ACTION_VOICE_INPUT_SETTINGS` or the
      manage-default-apps settings — verify which works on API 37)
    - text with Samsung steps: Settings → Advanced features → Side button → Press and hold →
      Digital assistant
    - STT language and TTS speaker pickers (PaSettings)
    - a "Test voice" button
  - Run `./gradlew :pa-voice:assembleDebug :pa-voice:testDebugUnitTest`.
- [ ] **C7. Finish.** Commit.

## Task D: On-device wake phrase with voice match

**Files:**
- Create `scripts/fetch-wakeword-assets.sh` (repo root).
  - It downloads the AAR and models listed in `docs/research/wakeword-sherpa-onnx.md` and verifies
    sha256 values (compute them once and pin them in the script).
  - It unpacks into `Android/src/pa-wakeword/src/main/assets/wakeword/`: KWS encoder, decoder,
    joiner, `tokens.txt`, `en.phone`, a minimal `keywords.txt`, the CAM++ `.onnx`, and
    `silero_vad.onnx` (VAD only if you use it).
  - It is idempotent.
  - The scratchpad already holds downloaded copies at
    `/private/tmp/claude-501/-Users-subhash-Projects/c2667b92-d801-449e-b3e6-f54eaf27e99e/scratchpad/wakeword-research/`.
    The script may copy from there when the checksums match.
- Add `.gitignore` entries for the assets and AAR.
- sherpa-onnx dependency:
  - Prefer JitPack (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1`; check the exact coordinates
    against the research doc and JitPack), adding a `maven("https://jitpack.io")` repository with
    a `content { includeGroupByRegex("com\\.github\\.k2-fsa.*") }` filter to
    `settings.gradle.kts` → `dependencyResolutionManagement.repositories` (touch point).
  - If that fails, install the static AAR into a local Maven repo layout under
    `Android/src/pa-wakeword/localrepo/` (gitignored, created by the script).
  - Add the R8 keep rule in `pa-wakeword/consumer-rules.pro`.
  - Use `abiFilters` arm64-v8a if the module needs it.
- Create in `pa-wakeword/.../wakeword/`:
  - `WakePhraseEncoder.kt`, `Lexicon.kt`, `VoiceProfile.kt`, `RingBuffer.kt`, `WakeWordEngine.kt`
    (sherpa KWS + speaker extractor wrapper), `WakeWordService.kt`, `BootReceiver.kt`
  - `WakeWord.kt` (`object WakeWord { fun start(context); fun stop(context); fun isEnrolled(context): Boolean }`)
  - `WakeEnrollmentActivity.kt` (Compose)
- Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the
  service with `foregroundServiceType="microphone"`; the receiver and the activity.
- Tests: `pa-wakeword/src/test/.../{WakePhraseEncoderTest,VoiceProfileTest,RingBufferTest,WakeGateTest}.kt`

**Interfaces:**
- **Consumes:** `PhoneAgentBus` (`voiceSessionActive`, `voiceTurnLauncher`), `VoiceTrigger.WAKE_PHRASE`,
  `PaSettings.wakeWordEnabled` (pa-core).
- **Produces:**
  - `object WakeWord`
  - `com.malladi2610.phoneagent.wakeword.WakeEnrollmentActivity`
  - `class WakePhraseEncoder(lexicon: Lexicon)` with
    `fun encode(phrase: String): Result<String>`, producing a keyword line like
    `HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS`
  - `class VoiceProfile(embeddings: List<FloatArray>)` with `threshold(): Float`,
    `fun withoutOutliers(): VoiceProfile`, `save(file)`, `load(file)`
  - `class WakeGate`: a pure state machine with `onKeyword()`, `onVerify(score)`,
    `onVoiceSessionActive(active)`, `onTick(nowMs)`, emitting `Trigger` / `Ignore`; it holds the
    cooldown and pause logic so it can be unit-tested

- [ ] **D1. Fetch script plus dependency.** Run `scripts/fetch-wakeword-assets.sh` twice: the
  second run reports "up to date". Run `./gradlew :pa-wakeword:assembleDebug`; it should succeed
  with the sherpa classes on the classpath.
- [ ] **D2. Failing tests, then implementation, for `WakePhraseEncoderTest`** (a small fake lexicon
  in the test, using the real `en.phone` line format):
  - `encodesHeyJarvis` gives `HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS`
  - `caseAndPunctuationInsensitive`
  - `encoder_unknownWord_returnsError`, whose message names the word
  - `multiplePronunciations_usesFirst`
  - `emptyPhrase_returnsError`
- [ ] **D3. Failing tests, then implementation, for `VoiceProfileTest`:**
  - `threshold_isMinLooMinus005_clampedTo035_070`
  - `withoutOutliers_dropsLowestLooWhenBelowMedianMinus015`
  - `saveLoad_roundTrip`
  - `cosine_ofIdenticalNormalizedVectors_isOne`
- [ ] **D4. Failing tests, then implementation, for `RingBufferTest`** (keeps the last N samples;
  snapshot order is oldest → newest) and `WakeGateTest`:
  - `verifiedKeyword_triggers`
  - `rejectedScore_ignored`
  - `detector_pausedWhileVoiceSessionActive`
  - `cooldown2s_afterTrigger`
  - `resumesWhenSessionEnds`
- [ ] **D5. `WakeWordEngine` and `WakeWordService`** (pipeline as in the design above;
  `numThreads=1`; chunk-16 model).
  - The service only starts if the app is enrolled, `RECORD_AUDIO` is granted and
    `PaSettings.wakeWordEnabled` is true.
  - The notification has a Pause action.
  - `BootReceiver` posts the "Tap to resume listening" notification, whose PendingIntent starts
    the service from the foreground.
- [ ] **D6. `WakeEnrollmentActivity`:**
  - Phrase field (default "Hey Jarvis") with lexicon validation.
  - Record 5 takes (each must trigger the KWS) with a progress indicator.
  - Save `filesDir/wakeword/{profile.bin,phrase.txt}`.
  - A "Test" mode showing live keyword hits and voice-match scores against the threshold.
  - An Enable toggle that sets `PaSettings.wakeWordEnabled` and starts or stops the service.
  - A "Battery: set to Unrestricted" button (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- [ ] **D7. Finish.** Run `./gradlew :pa-wakeword:testDebugUnitTest :pa-wakeword:assembleDebug`,
  update `UPSTREAM.md` (settings repository), and commit.

## Task I: Integration (integrator, after A–D)
- [ ] **I1.** Merge the track branches into `m1-ollama-cloud-agent` and resolve conflicts (expected
  in `UPSTREAM.md` and possibly `AgentTools.kt`).
- [ ] **I2.** Create `app/.../phoneagent/PhoneAgentGraph.kt` with `fun install(app: Application)`.
  - It builds `KeystoreSecretStore`, `PaSettings`,
    `JsonlActionLog(filesDir/action_log, redact)`, where `redact` replaces the current values of
    `OLLAMA_API_KEY` and `SARVAM_API_KEY` with `[REDACTED]`,
    `PhoneToolRunner(createPhoneTools(app), log)`, `OkHttpClient`,
    `OllamaClient { secrets.get(OLLAMA_API_KEY) }` and `OllamaAgentEngine`.
  - It calls `PhoneAgentServices.install(...)` and `PaVoice.install(app)`.
  - It starts `WakeWord` if enabled.
  - Hook: one line in `GalleryApplication.onCreate` (touch point).
- [ ] **I3.** Run `./scripts/fetch-wakeword-assets.sh`, then
  `./gradlew :app:assembleDebug :pa-core:testDebugUnitTest :pa-tools:testDebugUnitTest :pa-voice:testDebugUnitTest :pa-wakeword:testDebugUnitTest :app:testDebugUnitTest`.
  Everything should pass.
- [ ] **I4.** Run `./gradlew :app:installDebug` on the S23, then smoke-test: launch, open setup,
  check that there are no crashes in `logcat -b crash`.
- [ ] **I5.** With the user and their keys: spec §10 acceptance tests 1–13, plus voice and
  lock-screen tests:
  - side button → "what's my battery" (spoken answer)
  - wake phrase while locked → "turn on the torch" (works)
  - wake phrase while locked → "text <own number> hi" (asks to unlock, then confirms)
  - enrollment with another person's voice → doesn't wake
