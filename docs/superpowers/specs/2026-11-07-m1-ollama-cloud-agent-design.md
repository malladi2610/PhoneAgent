# PhoneAgent — Milestone 1: Ollama Cloud agent that controls the phone

- **Date:** 2026-11-07
- **Status:** Draft, awaiting review
- **Repo:** `github.com/malladi2610/PhoneAgent` (fork of `google-ai-edge/gallery` at `3972f06`)
- **Branch:** `m1-ollama-cloud-agent`
- **Target device:** Samsung Galaxy S23 (Snapdragon 8 Gen 2, 8 GB RAM)

## 1. Context

PhoneAgent is a personal agent harness for Android, built on a fork of Google AI Edge Gallery.
The long-term vision is a persistent agent that can be reached by voice and from the lock screen,
with a plugin system modelled on DeepSeek Harness ("everything is a plugin", tool hooks, session log).

**Goal for this stage:** a **local + cloud** setup.
- A powerful cloud model (Ollama Cloud) plans and reasons.
- A small local model, accelerated by Hexagon and **fine-tuned for this phone**, executes the
  sensitive actions.
- A **fine-tuning loop** keeps improving the local model from real usage.

**Milestone 1 (this spec):** the phone talks **directly to Ollama Cloud**, with no Mac in between,
using tool calling. The model controls the phone through a set of phone tools. Every tool call is
logged in a form that will later feed the fine-tuning loop.

### Roadmap (each item gets its own spec)

| # | Milestone |
|---|---|
| **1** | **Ollama Cloud runtime + phone tools + ActionLog (this spec)** |
| 2 | Local model on Hexagon (LiteRT-LM / QNN). Cloud model delegates sensitive tools (contacts, SMS, calls, messaging) to it |
| 3 | Fine-tuning loop over ActionLog data |
| 4 | Accessibility Service: drive any app's UI (fully automatic WhatsApp send, toggles Android blocks) |
| 5 | Voice + lock screen (default-assistant role) |
| 6 | Persistent agent (foreground service, triggers, memory) and harness plugin layer |

## 2. Decisions made

1. **Fork Gallery.** We build on the current upstream and merge upstream changes by hand. `upstream`
   is fetch-only (its push URL is disabled).
2. **Add a new runtime inside Gallery** (approach 1). Ollama Cloud is added as a new `LlmModelHelper`
   implementation. Gallery's agent loop, Agent Chat UI, sessions, skills and MCP are reused.
   Rejected alternatives:
   - A separate agent loop inside Gallery: it duplicates existing code.
   - A standalone app: weeks before anything runs end to end.
3. **Direct phone → `https://ollama.com` with an API key.** The Mac-over-LAN product option was
   explicitly rejected. Development also uses the real key, so there's no base-URL override.
4. **Tools in M1:** device status, device settings, apps & time, communication, and WhatsApp (§5.3).
5. **Confirmation policy: ask before outward actions.**
   - Instant outward actions (`call`, `send_sms`) show a confirm card.
   - Intents that open a pre-filled screen (WhatsApp, email, contact, calendar) rely on that screen
     as the confirmation: the user taps Send or Save there.
   - Everything else runs without asking.
   - The defaults are fixed in code. A per-tool settings UI is out of scope.
6. **Privacy is accepted for M1.** Contact lookups and message text are sent to Ollama Cloud.
   Milestone 2 moves these tools behind the local model.
7. **Own app identity.** The app ID is `com.malladi2610.phoneagent` and the label is "PhoneAgent",
   so it installs next to the official Gallery (`com.google.ai.edge.gallery`, already on the S23) (§8).

## 3. Non-goals for M1

- On-device or NPU inference, and cloud→local delegation (M2).
- Fine-tuning (M3); M1 only produces the log.
- Accessibility automation, and toggling Wi-Fi or Bluetooth directly (M4).
- Voice, wake word, lock screen (M5).
- Background or persistent agent, plugin hooks layer (M6).
- Streaming token output, a per-tool permission settings screen, an in-app log export.
- Changes to Gallery's existing Mobile Actions (FunctionGemma) task.

## 4. Architecture

```
Agent Chat screen (existing)
   │  user request
   ▼
DefaultAgentRuntimeExecutor (existing agent loop)
   │  tools = existing Agent Chat tools + PhoneTools
   ▼
OllamaCloudModelHelper : LlmModelHelper        [new]
   │  POST {baseUrl}/api/chat  {model, messages, tools, stream:false}
   │  tool_calls ──► ToolManager.execute(name, args)        (LiteRT-LM, public API)
   │                     │
   │                     ▼
   │                 PhoneTools                               [new]
   │                     │  call / send_sms ──► ConfirmToolCallAction ──► confirm card
   │  role:"tool" results ◄┘                  (loop, max 8 tool steps)
   ▼
final assistant text → chat
ActionLog (JSONL per tool call)                               [new]
```

All new code lives in the package `com.google.ai.edge.gallery.phoneagent`
(`Android/src/app/src/main/java/com/google/ai/edge/gallery/phoneagent/`).

## 5. Components

### 5.1 `OllamaClient`
- A thin Ktor client. Gallery already depends on `ktor-client-android` 3.4.3.
- `chat(request): ChatResponse` posts to `{baseUrl}/api/chat` with `stream:false`.
  `listModels(): List<String>` calls `{baseUrl}/api/tags`.
- Sends `Authorization: Bearer <key>` when a key is set.
- Timeout: 60 s per request.
- Serialization is kotlinx.serialization data classes that mirror the verified wire format (§6).

### 5.2 `OllamaCloudModelHelper : LlmModelHelper`
- **Selection.** `Model.runtimeHelper` (`runtime/ModelHelperExt.kt`) returns this helper for models
  whose runtime type is the new `RuntimeType.OLLAMA_CLOUD` (`data/ModelEnums.kt`).
- **`initialize`:**
  - Builds `ToolManager(tools)` from the passed `ToolProvider`s.
  - Converts `toolManager.getToolsDescription()` to Ollama's
    `{"type":"function","function":{name, description, parameters}}` format.
  - Stores the system instruction, prepended with a short phone preamble (device model, local time,
    time zone).
  - Stores `initialMessages` in a per-model conversation held in `model.instance`.
  - Calls `onDone("")`.
- **`runInference`** runs the tool loop:
  1. Append the user message.
  2. Call `chat`.
  3. If the reply has `tool_calls`, execute each one in order through `ToolManager.execute`,
     append `{"role":"tool","tool_name","tool_call_id","content": <result JSON>}`, and go back
     to step 2.
  4. Otherwise deliver `message.content` with `resultListener(text, done=true, thinking)`.
  5. Stop after **8 tool steps** with a message saying the step limit was reached.
- **`stopResponse`** cancels the in-flight coroutine job.
- **`resetConversation`** clears history, then re-seeds the system instruction and `initialMessages`.
- **`cleanUp`** drops the conversation.
- `getTokenCount()` on the conversation instance returns the last `prompt_eval_count + eval_count`.
- `countTokens` keeps its default (`null`).
- Images and audio are ignored in M1.

### 5.3 `PhoneTools`

All tools are LiteRT-LM `@Tool`-annotated Kotlin functions, wrapped as Gallery `ToolDefinition`s and
added to Agent Chat's tool list in `customtasks/agentchat/AgentTools.kt`. Each one returns a JSON
object:
- `{"status":"ok", ...}`
- `{"status":"error","reason":"..."}`
- `{"status":"permission_needed","permission":"...","action":"opened_settings"}`
- `{"status":"denied_by_user"}`

| Group | Tool | Android mechanism | Permission | Confirm |
|---|---|---|---|---|
| Status | `get_device_status()` | BatteryManager, ConnectivityManager, AudioManager, NotificationManager, Settings.System | `ACCESS_NETWORK_STATE` | no |
| Settings | `set_flashlight(on)` | `CameraManager.setTorchMode` | none | no |
| | `set_volume(stream, percent)` | `AudioManager.setStreamVolume`; stream ∈ media/ring/alarm/notification; percent mapped to the stream's index range | none (ring/notification while DND is on needs DND access) | no |
| | `set_brightness(percent)` | `Settings.System.SCREEN_BRIGHTNESS` + manual mode | `WRITE_SETTINGS` (special) | no |
| | `set_do_not_disturb(on)` | `NotificationManager.setInterruptionFilter` | notification-policy access (special) | no |
| | `open_settings_panel(panel)` | panel ∈ wifi/bluetooth/internet/nfc/volume, via `Settings.Panel.*` or `Settings.ACTION_*` | none | no |
| Apps & time | `open_app(name)` | Launcher activities via `<queries>` MAIN/LAUNCHER; case-insensitive fuzzy label match; returns candidates if ambiguous | none | no |
| | `set_alarm(hour, minute, label?, days?)` | `AlarmClock.ACTION_SET_ALARM`, `EXTRA_SKIP_UI` | `SET_ALARM` (normal) | no |
| | `start_timer(seconds, label?)` | `AlarmClock.ACTION_SET_TIMER`, `EXTRA_SKIP_UI` | `SET_ALARM` | no |
| Communication | `find_contact(name)` | `ContactsContract` query; top 5 matches as name + phone numbers only | `READ_CONTACTS` (runtime) | no |
| | `call(number)` | `Intent.ACTION_CALL` | `CALL_PHONE` (runtime) | **yes** |
| | `send_sms(number, text)` | `SmsManager.sendTextMessage` (multipart if long) | `SEND_SMS` (runtime) | **yes** |
| | `send_whatsapp(number, text)` | `ACTION_VIEW https://wa.me/<digits>?text=<urlencoded>`, package `com.whatsapp`; error if not installed | none | pre-filled screen |
| | `send_email(to, subject, body)` | `ACTION_SENDTO mailto:` | none | pre-filled screen |
| | `create_contact(name, phone?, email?)` | `ContactsContract.Intents.Insert` | none | pre-filled screen |
| | `create_calendar_event(title, start, end?)` | `CalendarContract` insert intent | none | pre-filled screen |
| | `show_location_on_map(query)` | `geo:0,0?q=` intent | none | no |

**How the permission tools behave:**
- **Runtime permissions** (Contacts, Phone, SMS): if the permission isn't granted, the tool returns
  `permission_needed` and asks the UI, through the tool action channel, to show the system
  permission prompt. The model then tells the user to retry.
- **Special permissions** (brightness, DND): the tool opens the matching settings page and returns
  `permission_needed`.

Simple versions of Gallery's Mobile Actions tools (torch, contact, email, map, calendar,
Wi-Fi settings) are re-implemented here, so the upstream `mobileactions` package stays untouched.

### 5.4 Confirm card
- A new `ConfirmToolCallAction(toolName, summary)` is a `ToolAction` with a `CompletableDeferred`
  result. It mirrors the existing `AskMcpToolCallPermissionAction` / `McpToolCallPermissionDialog`
  pattern in `tools/RunMcpTool.kt`.
- `AgentChatScreen` shows a dialog with a human-readable summary, for example
  *"Send SMS to +49 151 … : 'Running 10 min late'"*, and **Allow** / **Deny** buttons.
- If denied, the tool returns `denied_by_user`.

### 5.5 Settings: API key and base URL
- **Ollama API key:**
  - Entered on a small "Ollama Cloud" settings sheet that reuses `SecretEditorDialog`.
  - Stored encrypted with Android Keystore-backed storage.
  - Never logged and never shown again in full.
- **Model list:**
  - After the key is saved, the app calls `listModels()`.
  - Every returned model is registered as a `Model` with `RuntimeType.OLLAMA_CLOUD` under the Agent
    Chat task, shown as "Ollama Cloud · <name>". Nothing is downloaded.
  - Registration is done in code by `phoneagent/OllamaModelSource`. Ollama models are **not** added
    to the allowlist JSON, which Gallery fetches from Google's GitHub at startup.
  - The single hook point is in `ui/modelmanager/ModelManagerViewModel.kt`, after the allowlist loads.
- **Base URL:** fixed to `https://ollama.com`, defined as one constant in `OllamaClient`.
  There's no dev override in M1; development uses the real API key (§9).

### 5.6 `ActionLog`
- One JSON line per tool call, appended to `filesDir/action_log/YYYY-MM-DD.jsonl`.
- Fields:
  - `ts`, `chat_id`, `user_request` (the user message that started this turn), `model`
  - `step`, `tool`, `args`
  - `result_status`, `result` (truncated to 2 KB)
  - `confirmation` (`allowed` / `denied` / `n/a`), `latency_ms`
- Never contains the API key.
- Read it with:
  `adb shell run-as com.malladi2610.phoneagent cat files/action_log/<date>.jsonl`

## 6. Wire format (verified 2026-11-07 against Ollama 0.34.2, `deepseek-v4.1-flash:cloud`)

The fixtures are in `docs/superpowers/specs/fixtures/ollama-chat-{req1,resp1,req2,resp2}.json`.

- **Request:**
  - `{"model", "stream": false, "messages": [...], "tools": [{"type":"function","function":{"name","description","parameters": <JSON Schema>}}]}`
- **Tool-call response:**
  - `message.tool_calls[] = {"id": "call_…", "function": {"index", "name", "arguments": <JSON object>}}`
  - `message.content` may contain text alongside the tool calls.
  - Arguments arrive as an **object**, not a string.
  - Several calls can arrive in one reply.
- **Tool result message:**
  - `{"role":"tool","tool_name":"…","tool_call_id":"…","content":"<JSON string>"}`
- **Final response:**
  - `message.content` with no `tool_calls`, plus `done:true`, `prompt_eval_count`, `eval_count`.
- **Measured latency:** 0.8 s for the tool-call turn, 0.2 s for the final turn.
- **Direct `https://ollama.com/api/chat` without a key:** HTTP 401 `{"error":"Unauthorized"}`.
- **Model names:**
  - The local server uses a `:cloud` suffix (`deepseek-v4.1-flash:cloud`).
  - The response reports `deepseek-v4.1-flash`.
  - The app always uses names exactly as returned by `{baseUrl}/api/tags`, so no mapping is needed.
  - Direct-cloud naming is confirmed in the first on-device run with a real key.

## 7. Error handling

| Situation | Behaviour |
|---|---|
| No key, or 401 | Chat message: "Add a valid Ollama API key", with an action that opens the settings sheet |
| 429 | "Ollama Cloud rate limit hit — try again in a moment"; no automatic retry |
| Network down or 60 s timeout | "Can't reach Ollama Cloud"; the user's message stays in the input so it can be resent |
| 5xx | One automatic retry after 1 s, then an error message |
| Unknown tool name, or arguments that don't fit the schema | An error result goes back to the model as the tool result; counts as a step |
| Tool throws | Caught; returns `{"status":"error","reason":<message>}` |
| 8-step limit reached | Stops; the chat shows the partial outcome and says the step limit was hit |
| User presses Stop | Cancels the HTTP call; nothing further runs; already-executed tools stay logged |

## 8. Upstream merge hygiene

The only changes to existing upstream files are small, additive hooks, listed in `UPSTREAM.md` at
the repo root along with the merge procedure (`git fetch upstream && git merge upstream/main`):

1. `data/ModelEnums.kt`: add `OLLAMA_CLOUD` to `RuntimeType`.
2. `runtime/ModelHelperExt.kt`: route `OLLAMA_CLOUD` to `OllamaCloudModelHelper`.
3. `customtasks/agentchat/AgentTools.kt`: append the PhoneTools to `getAvailableTools()`.
4. `customtasks/agentchat/AgentChatScreen.kt`: handle `ConfirmToolCallAction` and the
   permission-request action.
5. `ui/modelmanager/ModelManagerViewModel.kt`: call `OllamaModelSource` after the allowlist loads.
6. `AndroidManifest.xml`: add the permissions and `<queries>` entries.
7. `app/build.gradle.kts`: change `applicationId` from `com.google.aiedge.gallery` to
   `com.malladi2610.phoneagent` and the app label to "PhoneAgent", so the app installs alongside
   the official Gallery already on the S23. The Kotlin `namespace` stays `com.google.ai.edge.gallery`,
   which keeps upstream merges clean. The Hugging Face OAuth redirect scheme (`appAuthRedirectScheme`,
   `common/ProjectConfig.kt`) is tied to Google's registered client and is not needed for Ollama
   models; HF downloads of local models may need our own HF OAuth app in M2.

The planning stage may adjust this list. Any new touch point must be added to `UPSTREAM.md`.

## 9. Development environment and loop

- **Mac:** M4 Pro, 24 GB RAM, Temurin JDK 25, Gradle 9.2.1 (wrapper), AGP 9.0.1, compileSdk 37,
  `adb` installed, Ollama 0.34.2 running and signed in with cloud models available.
- **Android SDK:** not installed yet. Needed: command-line tools, platform 37, build-tools.
  **No emulator** in M1.
- **Test device: the S23 over wireless `adb`.** It's connected and already paired with this Mac.
  - Model SM-S911B (`dm1q`), Android 17 / SDK 37, One UI 9.0, SoC SM8550, ~7 GB RAM.
  - WhatsApp, Spotify and Samsung Clock are installed. `libcdsprpc.so` (FastRPC to Hexagon) is
    present, which matters for M2.
  - The `adb` port changes after a reboot or Wi-Fi change; rediscover it with `adb mdns services`.
  - A USB cable is the fallback.
  - Loop: `./gradlew :app:installDebug`, launch, test, read logcat and the ActionLog.
- **Ollama during development:** the real `https://ollama.com` + API key path from day one, which is
  exactly what M1 must prove. The Mac's local Ollama (signed in, `:cloud` models) is used only for
  JVM-side experiments and for capturing fixtures.
- **Call/SMS testing** uses the user's own number, so a bug can't contact anyone else.
- **Unit tests** (JVM, `./gradlew :app:testDebugUnitTest`):
  - `OllamaClient` serialization against the §6 fixtures, using Ktor `MockEngine`.
  - The tool loop with fake tools: multiple calls per turn, step limit, unknown tool, tool exception,
    user denial, stop/cancel, 401/429/5xx handling.
  - Conversion of the LiteRT-LM tool description to the Ollama tool schema.
  - Pure tool logic: fuzzy app matching, `wa.me` URL building (strip `+`, spaces and dashes;
    URL-encode text), percent → stream-index mapping.
  - ActionLog line format and the key-redaction guarantee.

## 10. Acceptance criteria (M1 is done when all of these pass on the S23)

1. With an Ollama API key entered, Agent Chat lists the Ollama Cloud models, and one can be selected.
2. "Turn on the torch and set media volume to 30%" → both happen, with no confirmation.
3. "What's my battery level?" → the correct value, from `get_device_status`.
4. "Set an alarm for 6:30 tomorrow and turn on Do Not Disturb" → the alarm appears in Samsung Clock
   and DND is on (after the one-time DND access grant).
5. "Set brightness to 20%" → works after the one-time `WRITE_SETTINGS` grant. Before the grant, the
   model explains what to enable.
6. "Open Spotify" (or another installed app) → the app opens.
7. "Start a 5 minute timer" → a timer is running.
8. "Text <contact> 'running late'" → `find_contact` → confirm card → the SMS is sent only after Allow.
   Deny sends nothing. (Tested with a contact entry for the user's own number.)
9. "Call <contact>" → confirm card → the call starts only after Allow. (Same own-number contact.)
10. "WhatsApp <contact> 'see you at 7'" → WhatsApp opens on that chat with the text pre-filled.
11. Airplane mode → a clear "can't reach Ollama Cloud" message; no crash.
12. Every tool call from 2–10 appears in that day's ActionLog with the correct fields and no API key.
13. All unit tests pass.

## 11. Risks

| Risk | Mitigation |
|---|---|
| The `ToolManager` / `getToolsDescription()` API in LiteRT-LM **0.18.1** (Gallery's pinned version) may differ from LiteRT-LM HEAD, which is what was inspected | First plan task: inspect the 0.18.1 artifact. Fallback: build Ollama schemas directly from our `ToolDefinition`s |
| The tool description JSON from LiteRT-LM isn't plain JSON Schema | The conversion function is unit-tested; we control the PhoneTools schemas |
| `DefaultAgentRuntimeExecutor` / `LlmSessionManager` assume LiteRT-specific behaviour (compaction, token counts) | Implement `LlmConversationInstance` minimally; disable summarization compaction for `OLLAMA_CLOUD` if needed (noted in `UPSTREAM.md`) |
| The confirm card blocks a tool thread while waiting | Same pattern as the existing MCP permission flow; the tool loop runs on `Dispatchers.IO` |
| **Mac disk: 18 GB free**; SDK + Gradle caches + build outputs take about 6–8 GB (no emulator) | Install only platform 37 + build-tools; check `df` after the first build |
| Wireless `adb` drops (reboot, Wi-Fi change) | `adb mdns services` + `adb connect`; USB as fallback |
| Samsung-specific intent behaviour (Clock, Contacts) | Covered by on-device acceptance tests 4, 7 and 8 |
| Ollama Cloud rate limits or plan limits | Clear 429 message; the dev loop uses the Mac's signed-in Ollama |
