# On-device wake phrase + voice match: sherpa-onnx (research, 2026-11-07)

**Choice: sherpa-onnx v2.0.1** (k2-fsa, Apache-2.0, released 2026-10-26).

**Rejected alternatives:**
- openWakeWord: every phrase needs Python training, its models are CC BY-NC-SA, and its
  "verifier" isn't speaker verification.
- Rustpotter: no Android build, stale since 2023.
- microWakeWord: built for ESP32, and training is hard.
- Porcupine: closed source.

## Integration
- **AAR:** the static-link build bundles ONNX Runtime inside, so it can't clash with another ORT.
  - Download: https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar
  - arm64 adds a single `libsherpa-onnx-jni.so` of 24.2 MB.
  - Every arm64 `.so` is 16 KB page-aligned.
  - Use `abiFilters += "arm64-v8a"`.
  - Add the R8 rule `-keep class com.k2fsa.sherpa.onnx.** { *; }`.
- **Kotlin API:** already inside the AAR's `classes.jar` (package `com.k2fsa.sherpa.onnx`):
  `KeywordSpotter`, `KeywordSpotterConfig`, `OnlineModelConfig`, `OnlineTransducerModelConfig`,
  `FeatureConfig`, `SpeakerEmbeddingExtractor(Config)`, `SpeakerEmbeddingManager`, `Vad`.
  Reference code in the sherpa-onnx repo:
  - `android/SherpaOnnxKws/.../MainActivity.kt`
  - `android/SherpaOnnxSpeakerIdentification/.../screens/Home.kt`
  - `sherpa-onnx/kotlin-api/{KeywordSpotter,Speaker,Vad}.kt`

## Models (16 kHz mono float PCM in [-1, 1])
**Wake phrase (KWS):** `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` (release tag `kws-models`).
- Files: encoder int8 4.6 MB, decoder 0.76 MB, joiner int8 87 KB, `tokens.txt`, and the `en.phone`
  lexicon (3.3 MB, 126k words).
- English is modelled as **ARPAbet phones**, so **any phrase can be encoded on the device** by looking
  each word up in the lexicon. No Python is needed.
  - Example: `HH EY1 JH AA1 R V AH0 S @HEY_JARVIS`.
  - Line format: `<phones> :<score> #<threshold> @<PHRASE_WITH_UNDERSCORES>`.
- Chunk-8 (160 ms latency) and chunk-16 (320 ms) variants exist. Use chunk-16 for lower CPU.
- No English accuracy numbers are published, so tune on the device.
- **Fallback:** `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile` (BPE).
  - Measured: recall 86%, 0.025 false alarms per hour.
  - But tokens must be precomputed with the Python `sentencepiece` tool.

**Voice match:** `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`
- 29.6 MB, Apache-2.0, EER 0.73% on VoxCeleb1-O.
- Release tag `speaker-recongition-models` (the repo's own spelling).

**VAD:** `silero_vad.onnx` (644 KB), release tag `asr-models`.

## API usage
- **KWS loop:**
  1. Read 100 ms of `ShortArray` from AudioRecord and divide by 32768f.
  2. `stream.acceptWaveform(samples, 16000)`.
  3. `while (kws.isReady(stream)) { kws.decode(stream); if (kws.getResult(stream).keyword.isNotBlank()) kws.reset(stream) }`.
- **Keywords file:** the `KeywordSpotter` constructor needs a valid `keywordsFile`. Custom keywords
  can be passed later via `kws.createStream(keywordsString)`; join several with `/`.
- **Speaker embedding:**
  `s = extractor.createStream(); s.acceptWaveform(pcm, 16000); s.inputFinished(); if (extractor.isReady(s)) emb = extractor.compute(s)`.
- **Verification:**
  - `manager.add(name, Array<FloatArray>)` stores a profile; it sums the embeddings and L2-normalizes them.
  - `manager.verify(name, emb, threshold)` checks a new embedding. Scores are cosine similarity.
  - The repo's demos use a threshold of 0.5; the Python default is 0.6.
- **KWS tuning defaults:** `keywordsThreshold` 0.25, `keywordsScore` 1.0. Both can be set per keyword.

## Design (enrollment)
1. **Enrollment:** the user says the phrase 5 times.
   - Accept a take only if the KWS fires on it.
   - Trim to the phrase ±0.3 s.
   - Compute an embedding per take and drop outliers with leave-one-out cosine.
   - Store the `FloatArray`s in app-private storage.
2. **Runtime:** keep a 2.5 s ring buffer.
   - When the KWS fires, compute an embedding for the window and run `verify` against threshold T.
   - T = min(0.6, min leave-one-out enrollment score − 0.05), clamped to [0.35, 0.7].
3. **Tuning:** keep the KWS slightly permissive and let voice match gate it.
4. **Framing:** short phrases give weaker embeddings. Present voice match as a way to reduce false
   wakes, not as security.

## Hexagon NPU path (M2)
- sherpa-onnx's QNN backend (which supports SM8550) covers **ASR models only**; KWS and speaker
  embedding have no QNN branch.
- `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0` could run the speaker model on HTP.
- Porting the zipformer KWS to QNN is unexplored.
- Always-on CPU cost isn't published. **Measure it on the S23.**
