# Wake phrase by example: openWakeWord features + DTW (research, 2026-11-07)

Replaces the sherpa-onnx keyword spotter of [wakeword-sherpa-onnx.md](wakeword-sherpa-onnx.md).
sherpa-onnx stays, but only for the CAM++ voice match.

**Why:** the phoneme KWS (zh-en 3M) caught 2 of ~9 real "Hey Friday" takes (Indian English, Galaxy
S23 mic), even at threshold 0.08, so enrollment got stuck. It works on clean TTS, but not on this
user's voice. The new detector learns the phrase from the user's own takes ("personal hotword", as in
Snowboy or Voice Match), so any typed phrase works and accent doesn't matter.

## Design

```
mic 16 kHz, 80 ms chunks
 ├─ SpeechEmbeddingStream: melspectrogram.tflite → x/10+2 → 76×32 window, stride 8 → embedding_model.tflite
 │    (one 96-dim speech embedding per 80 ms; a line-by-line port of openWakeWord's AudioFeatures streaming)
 ├─ Endpointer (energy, 20 ms frames): utterance = 0.4–2.5 s of speech, then 0.3 s of quiet
 └─ UtteranceCutter: frames whose 0.775 s window ends in [speechStart + 0.2 s, speechEnd + 0.25 s]
        → PhraseMatcher: DTW against each enrolled take, min cost ≤ per-user threshold → hit
        → WakeGate / CAM++ voice match (unchanged) → voice turn
```

- **Features:** openWakeWord's two shared feature models (see Licences), run with LiteRT.
  - `SpeechEmbeddingStream` reproduces `AudioFeatures._streaming_features` exactly. That includes:
    - int16-scale input;
    - each 1280-sample chunk plus the previous 480 samples gives 8 mel frames (5 for the first
      chunk);
    - the mel buffer starts as 76 rows of 1.0;
    - the `x/10 + 2` transform;
    - one embedding per chunk.
  - The first 9 embeddings after a reset still see the 1.0 rows (warm-up) and are never used.
- **Matcher:** DTW anchored at both utterance ends.
  - Local cost is `1 − cos` of L2-normalised frames.
  - Steps are symmetric2: diagonal weight 2, horizontal and vertical weight 1.
  - The total is divided by `n + m`.
  - Lengths that differ by more than 2× don't match.
  - Templates are the 4 enrolled takes, about 12–17 frames each.
  - CPU: one embedding per 80 ms, plus a few 15×15 DTWs per utterance.
- **Why anchored, not subsequence DTW:** the first version did a subsequence DTW over a rolling
  2.5 s window, with free start/end and path-length normalisation. It separated the user's takes from
  other phrases badly. On the eval below:
  - AUC was 0.85 against 0.98 for anchored;
  - the hardest negatives scored *lower* than the user's own takes;
  - free start let a template's silence/onset edges align with the tails of other utterances
    ("…day", the gap inside "Hey Siri").

  Cutting utterances with the same endpointer at enrollment and at runtime, then anchoring both
  ends, fixed this.
- **Cost of anchoring:** the user has to pause (≥ 0.3 s) after the phrase. That is natural here,
  because the voice turn opens its own mic after the hand-off anyway. A phrase run straight into a
  command ("Hey Friday what's the time") is not detected.
- **Refractory:** utterances starting within 1 s of a hit are ignored. WakeGate's 2 s cooldown
  after a voice session is unchanged.
- **Enrollment:**
  - Tapping Record opens the mic immediately. LiteRT and CAM++ load on background threads in
    parallel.
  - The endpointer cuts each take (clip = 1 s pre-roll + speech + 0.3 s).
  - Take checks:

    | Condition | Action |
    |---|---|
    | Shorter than 0.15 s | Ignored (click) |
    | Shorter than 0.4 s or longer than 2.5 s | Rejected |
    | Peak below −35 dBFS | Rejected ("too quiet") |
    | More than 2 % of samples clipped | Rejected ("too loud, move the phone a bit away") |
    | More than 0.2 % clipped | Accepted, with a warning |

  - A live level bar shows "too loud" whenever the mic clips. The user's recording peaked at
    0 dBFS.
  - After 4 takes, the app computes leave-one-out (LOO) costs: each take against the other three
    templates.
  - **Consistency check:** a take is flagged when its LOO cost is above 0.11 and at least 1.4× the
    median of the others. The user is asked to redo just that take, up to 2 times. After that the
    take is dropped if 3 remain.
  - **Threshold:** `clamp(max LOO + 0.02, 0.08, 0.11)`, stored per user. The band comes from the
    eval:
    - the user's good takes score 0.05–0.11 against each other;
    - the hardest negative (TTS "Hey fry day") scores 0.115.
  - CAM++ voice profile: one embedding per kept take, computed on the same 2.5 s window the runtime
    uses (SpeechTrim). Threshold rule unchanged.
- **Storage:** `filesDir/wakeword/enrollment.bin`, format 2. It holds:
  - magic and version;
  - the phrase;
  - the threshold;
  - the raw template frames;
  - the VoiceProfile.

  The old `phrase.txt` + `profile.bin` (keyword-line) enrollment loads as "not enrolled". So
  `WakeWord.start` returns false and the service stops at once. The old files are deleted on the next
  save.

## Runtime choice: LiteRT 1.4.2 (`com.google.ai.edge.litert:litert`)

| | LiteRT 1.4.2 (Interpreter API) | ONNX Runtime Android 1.31.0 |
|---|---|---|
| Native lib (arm64) | `libtensorflowlite_jni.so` 4.5 MB | `libonnxruntime.so` 34.2 MB |
| All 4 ABIs (app has no abiFilters) | 20.0 MB | 140 MB |
| Conflicts | none: litertlm ships only `liblitertlm_jni.so` (static), and nothing else in the APK has `libtensorflowlite_jni.so` | none by name (sherpa links its ORT statically), but a second ORT copy |
| JVM unit tests | no host build | `com.microsoft.onnxruntime:onnxruntime` |

- **Choice:** LiteRT, with the `.tflite` models. ORT would add about 7× more native code.
- **Parity testing:** the code is split behind `FeatureModels`. The unit tests run the *ONNX* copies
  through ONNX Runtime JVM (`testImplementation` only, models in `pa-wakeword/testmodels/`, never
  packaged).
- **TFLite vs ONNX:** checked in Python with `ai-edge-litert` 2.3.0 against onnxruntime. Max abs
  difference:

  | Model | Max abs difference | Value range |
  |---|---|---|
  | melspectrogram | 1.5e-5 | 16–100 |
  | embedding | 4e-5 | norm 150 |

- **APK size:** debug APK 418.5 → 430.8 MB (+12.3 MB).
  - +20.0 MB `libtensorflowlite_jni.so` (4 ABIs).
  - +2.4 MB `.tflite` models.
  - −8.8 MB KWS model and `en.phone`.
  - Only the arm64 `.so` (4.5 MB) is ever loaded on the phone. Without the other ABIs, the change
    would be about −1.9 MB.
  - Optional saving: excluding the non-arm64 `libtensorflowlite_jni.so` in `app` saves 15.5 MB. The
    wake phrase is arm64-only anyway, because of sherpa.

## Licences (verified 2026-11-07)

- **`embedding_model.{tflite,onnx}`:** Google's `speech_embedding` (TF Hub,
  https://tfhub.dev/google/speech_embedding/1, Apache-2.0), re-implemented by openWakeWord. The README
  says: "This model is provided by Google as a TFHub module under an Apache-2.0 license."
- **`melspectrogram.{tflite,onnx}`:** a fixed-parameter export of torchaudio's MelSpectrogram (no
  learned weights), part of the openWakeWord code. Repository code is Apache-2.0.
- **Pretrained wake-word models are not used:** openWakeWord's README puts "all of the included
  pre-trained models" (hey_jarvis, alexa, …) under CC BY-NC-SA 4.0, because their training data had
  restrictive licences. Neither feature model is one of those classifiers.
- **Residual ambiguity:** the README's licence section doesn't name the two feature models
  explicitly. The Apache-2.0 reading rests on the TF Hub statement above.
- **Downloads:** release `v0.5.1` of github.com/dscripka/openWakeWord, pinned by size and SHA-256 in
  `scripts/fetch-wakeword-assets.sh`:

  | File | Size | SHA-256 |
  |---|---|---|
  | `melspectrogram.tflite` | 1092516 | 96fa0adc…bf163e8 |
  | `embedding_model.tflite` | 1330312 | c0aea21e…a59d8 |
  | `melspectrogram.onnx` (tests) | 1087958 | ba2b0e0f…b176f |
  | `embedding_model.onnx` (tests) | 1326578 | 70d16429…75c1f |

- **CAM++** (voice match): unchanged, Apache-2.0.

## Tests (`./gradlew :pa-wakeword:testDebugUnitTest`)

- **`FeatureParityTest`:** Kotlin `SpeechEmbeddingStream` (ORT JVM backend) against embeddings that
  openWakeWord's own Python `AudioFeatures` streaming produced for the same audio. The fixture is
  synthetic TTS "Hey Friday", 30 frames. Max abs error 3.4e-5 on values up to 65, for 1280-, 1600-
  and 333-sample chunks.
- **`PhraseMatcherTest`:** DTW properties (anchoring, tempo, length band, weighted mean), plus
  equality with the Python port to 1e-4 on TTS fixtures.
- **`EndpointerTest`:**
  - start and end timing;
  - a gap inside a phrase;
  - too short and too long;
  - the absolute floor;
  - digital silence;
  - chunk-size independence;
  - same segment as the Python port.
- **`PhraseCalibrationTest`:** frame-range rule, threshold rule and clamping, outlier rule, LOO.
- **`EnrollmentLogicTest`:**
  - phrase validation (any words);
  - take capture with pre-roll;
  - quiet, loud, long and click handling;
  - building an enrollment from 4 takes and detecting the phrase again through the live
    `QbeDetector`;
  - redoing, then dropping, an odd take;
  - failing without a voice.
- **`WakeWordStoreTest`:**
  - round trip;
  - an old keyword-line enrollment is treated as not enrolled and deleted on save;
  - corrupt, truncated, wrong-version and bad-threshold files are rejected.
- **`OfflineEvalTest`:** skipped unless `PA_WAKE_EVAL_DIR` is set (see below).

## Offline eval

**Data:**
- `user.wav`: the user's real S23 recording, 26.5 s, with clipping.
- **Negatives:**
  - 151 short clips: 18 phrases × 8 macOS voices, including Indian-English Rishi/Aman/Veena;
  - the phrases include "Friday", "Hey Freddy", "Hey fry day", "Happy Friday", "Hey Siri", "Hey
    Jarvis" and "The weather on Friday is nice";
  - plus the 7 earlier `neg_*` clips.
- "Hey Friday" in 9 other voices.
- 2.4 min of continuous TTS speech, which yields only 8 isolated utterances.
- All TTS clips get a −65 dBFS noise floor instead of digital silence.

**Method:** the user recording goes through the production code:
- `TakeRecorder` accepted 9 takes and rejected 1 "too quiet" and 1 "too short";
- every 4-take subset (126 enrollments) is enrolled as the app does;
- the held-out takes are then matched in the continuous recording.

**Results:** all negative and other-voice counts are for the phrase matcher alone, before voice
match.

| Threshold rule | Recall (all held-out takes) | Negative clips | Long speech | Other-voice "Hey Friday" |
|---|---|---|---|---|
| fixed 0.08 | 61.0 % | 0 / 19026 | 0 / 1008 | 0 / 1134 |
| fixed 0.10 | 72.7 % | 0 / 19026 | 0 / 1008 | 0 / 1134 |
| **max LOO + 0.02 in [0.08, 0.11] (shipped)** | **74.4 %** (469/630) | **0 / 19026** | **0 / 1008** | **0 / 1134** |
| fixed 0.12 | 75.4 % | 147 / 19026 | 0 / 1008 | 112 / 1134 |

**Bad takes:** recall over all takes includes three bad ones, all flagged by the consistency check:
- take 5: a clipped, short "Hey Fri-";
- take 6: a 2.4 s slow take;
- take 7: heavily clipped at 0 dBFS.

These score 0.12–0.2 or can't be compared. When the enrollment holds only takes the consistency
check accepts, and the held-out take is one too, **recall is 30/30**.

**Score distributions:**

| Set | Costs |
|---|---|
| User's good takes (held out) | 0.053–0.119, median 0.07 |
| Negatives (min per clip) | p0 0.115, p1 0.123, p5 0.151, median 0.21 |
| Other-voice "Hey Friday" | 0.115–0.23 |

**Hardest negatives:**
- TTS "Hey fry day": 0.115;
- "Hey Freddy": 0.116;
- "Friday" alone: 0.125.

At the 0.11 cap, a sound-alike said *by the owner* could get through. Voice match only stops other
voices.

**Not covered:** no negative audio was recorded in the user's voice or room, so real false-accept
rates per hour are unmeasured.

**Running the eval:** the Python port of the endpointer, the matcher and the fixture generator were
used to cross-check the Kotlin code; they lived in the session scratchpad. To rerun:

```
PA_WAKE_EVAL_DIR=<dir with user.wav, neg/, pos/, long/> ./gradlew :pa-wakeword:testDebugUnitTest --tests '*OfflineEvalTest*' -i
```

## To check on the device

- **CPU:** cost of LiteRT on the S23, one mel and one embedding per 80 ms, `numThreads=1`. Expect
  a few ms per frame.
- **Recall on the user's voice in normal use:** different distance, background TV or music. The
  endpointer needs a ≥ 0.3 s pause after the phrase, and background speech can merge with the phrase
  into one long segment.
- **Calibration:** whether the 0.08–0.11 band suits real sessions. The cost of each utterance is
  logged (`PaWakeWord`) and shown live in Test mode.
- **Enrollment UX:**
  - the mic opens at once;
  - the level bar and hints work;
  - the redo flow works;
  - an old enrollment shows "Not enrolled" and the service does not start.
