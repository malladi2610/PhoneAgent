# Sarvam speech APIs: verified reference (2026-11-07)

This was checked against docs.sarvam.ai (`.md` API reference pages) and the official SDK packages
(PyPI `sarvamai` 0.1.43, npm `sarvamai` 1.1.15), plus unauthenticated calls to see the error shapes.
**[UNVERIFIED]** marks the points that couldn't be confirmed.

## Auth and base URL
- REST: `https://api.sarvam.ai`. WebSocket: `wss://api.sarvam.ai`.
- Header: `api-subscription-key: <KEY>`. On WebSockets, send it as the `Api-Subscription-Key`
  handshake header; OkHttp supports this.
- **A bad or missing key returns HTTP 403** (not 401):
  `{"error":{"message":"Invalid or missing authentication credentials","code":"invalid_api_key_error","request_id":"..."}}`
- Other status codes:

  | Code | Meaning |
  |---|---|
  | 400, 422 | Bad format, or audio longer than 30 s |
  | 429 | `rate_limit_exceeded_error` |
  | 402 | `insufficient_quota_error`: out of credits, don't retry |
  | 500, 503 | Server error: retry with backoff |

## STT, REST (fallback path)
```
POST https://api.sarvam.ai/speech-to-text      multipart/form-data
file=@utt.wav  model=saaras:v4 (default) | saaras:v3
mode=transcribe|translate|verbatim|translit|codemix
language_code=unknown (auto-detect) | en-IN | hi-IN | te-IN | ...
input_audio_codec=pcm_s16le   (headerless PCM only, 16 kHz)
```
- Max 30 s per request. WAV at 16 kHz is recommended.
- Response:
  `{"request_id","transcript","language_code","language_probability"?}`.
  `language_probability` is only present when the language is `unknown`.

## STT, Realtime WebSocket (primary path for voice turns)
```
wss://api.sarvam.ai/speech-to-text-realtime/ws?language_code=auto&model=saaras:v3-realtime
   &stream_type=fast&mode=transcribe&endpointing=vad&encoding=linear16&sample_rate=16000
   &threshold=0.3&silence_duration_ms=500&min_speech_duration_ms=250&prefix_padding_ms=300
Header: Api-Subscription-Key: <KEY>
```
- Realtime uses `auto` for auto-detect (REST uses `unknown`).
- `sample_rate` must be 8000 or 16000; any other value closes with code 4000.
- **Client → server** (JSON text frames, audio = base64 of raw PCM16 mono with no WAV header,
  about 100 ms chunks = 3200 bytes at 16 kHz):
  ```json
  {"event":"audio_input","audio":"<base64 PCM>"}
  {"event":"speech_start"} {"event":"speech_end"} {"event":"flush"}   // endpointing=manual only
  {"event":"end"}    // finalize, then session.end, then close
  {"event":"ping"}
  ```
- **Server → client** (built from the schema; not a captured message):
  ```json
  {"event":"session.begin","request_id":"...","config":{...}}
  {"event":"vad.speech_start","utterance_idx":0,"confidence":0.91}
  {"event":"transcript.partial","utterance_idx":0,"text":"...","language":"hi-IN"}
  {"event":"vad.speech_end","utterance_idx":0}
  {"event":"transcript.final","utterance_idx":0,"text":"...","language":"en-IN","language_confidence":0.97}
  {"event":"session.end","request_id":"...","total_duration_s":5.2}
  {"event":"error","code":"invalid_config","is_fatal":false,"message":"..."}
  ```
- Without a key, the handshake upgrades (101), then the server sends
  `{"event":"error","code":"invalid_subscription_key",...,"is_fatal":true,"status_code":401}` and
  closes with code 1003.
- **Close codes:**

  | Code | Meaning |
  |---|---|
  | 1003 | Rate limit, quota, or bad key |
  | 1008 | Idle timeout (send pings) |
  | 1011 | Server error |
  | 4000 | Bad parameter, or account not enabled **[UNVERIFIED for new accounts]** |

## TTS, REST
```json
POST /text-to-speech
{"text":"...","language_code":"en-IN","model":"bulbul:v3","speaker":"ishita","pace":1.0,
 "speech_sample_rate":24000,"output_audio_codec":"wav"}
→ {"request_id":"...","audios":["<base64 WAV>"]}
```
- `text` can be up to 2500 characters.
- `pace` ranges 0.5–2.0.
- Codecs: wav, mp3, aac, flac, linear16.

## TTS, WebSocket (streaming)
`wss://api.sarvam.ai/text-to-speech/ws?model=bulbul:v3&send_completion_event=true`, with the
`Api-Subscription-Key` header. Always pass `model`; the WebSocket's default is v2.

```json
{"type":"config","data":{"language_code":"en-IN","speaker":"ishita","pace":1.0,"speech_sample_rate":"24000",
  "output_audio_codec":"mp3","output_audio_bitrate":"128k","min_buffer_size":50,"max_chunk_length":150}}
{"type":"text","data":{"text":"..."}}
{"type":"flush"}
{"type":"ping"}
```
- The config message must be sent first.
- Server messages:
  `{"type":"audio","data":{"content_type":"audio/mp3","audio":"<b64>"}}`,
  `{"type":"event","data":{"event_type":"final"}}`, and `{"type":"error",...}`.
- There's no cancel message. For barge-in, close the socket.
- The WebSocket codec list is contradictory, so use mp3 **[UNVERIFIED for linear16]**.

## Voices (bulbul:v3)
- v3 voices aren't tied to a language.
- en-IN recommendations: **ishita** (F), **ratan** (M).
- hi-IN recommendations: priya or suhani (F), shubh or ashutosh (M).
- Male voices: shubh (default), aditya, rahul, rohan, amit, dev, ratan, manan, sumit, kabir, …
- Female voices: ritu, priya, neha, pooja, simran, kavya, ishita, shreya, roopa, tanya, shruti, suhani, …
- `bulbul:v4-flash` costs about a third as much, but uses persona IDs (for example `simran_en_customer`)
  instead of these names.

## Languages
- STT: auto-detect plus 22 Indian languages, including en-IN, hi-IN and te-IN.
- TTS: bn, en, gu, hi, kn, ml, mr, od, pa, ta, te (all `-IN`).
- **No German or other European languages.**

## Pricing (₹, excluding GST, pay-as-you-go) and limits

| Item | Price / limit |
|---|---|
| STT | ₹0.49/min, billed per second |
| TTS bulbul:v3 | ₹3.00 per 1k chars |
| TTS v4-flash | ₹1.00 per 1k chars |
| STT REST | 60 requests/min |
| STT WebSocket | 20 concurrent |
| TTS REST | 60 requests/min |
| TTS WebSocket | 60 concurrent |
| Free credit for new verified users | ₹100, never expires |

## Latency
- `api.sarvam.ai` is hosted on AWS ap-south-1 (Mumbai).
- From Munich: TCP about 140 ms, TLS about 285 ms.
- Keep connections warm, and open the STT socket the moment the wake word fires.

## SDKs
- Python and JS only. There's no Android SDK; the protocol maps directly onto OkHttp `WebSocket`.
- Reference files: in PyPI `sarvamai-0.1.43`, `sarvamai/speech_to_text_realtime_streaming/socket_client.py`
  and `sarvamai/text_to_speech_streaming/socket_client.py`.
