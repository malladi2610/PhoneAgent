package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.core.agent.AgentError
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Server → client events of the Sarvam STT Realtime WebSocket (docs/research/sarvam-api.md). */
sealed interface SttEvent {
  data object SessionBegin : SttEvent

  data object SpeechStart : SttEvent

  data object SpeechEnd : SttEvent

  data class Partial(val text: String, val language: String?) : SttEvent

  /** `language` is only present when the socket was opened with `language_code=auto`. */
  data class Final(val text: String, val language: String?) : SttEvent

  data object SessionEnd : SttEvent

  data class Error(val code: String, val message: String, val isFatal: Boolean, val statusCode: Int?) :
    SttEvent

  /** pong, config.updated, or anything unknown/unparseable; ignored by the client. */
  data class Other(val event: String?) : SttEvent
}

/** Wire formats for Sarvam STT Realtime (WebSocket) and TTS (REST). Pure; JVM-testable. */
object SarvamWire {
  const val SERVICE = "Sarvam"
  const val REST_BASE = "https://api.sarvam.ai"
  const val WS_BASE = "wss://api.sarvam.ai"
  const val REST_KEY_HEADER = "api-subscription-key"
  const val WS_KEY_HEADER = "Api-Subscription-Key"

  const val STT_SAMPLE_RATE = 16_000
  /** 100 ms of 16 kHz mono PCM16. */
  const val STT_CHUNK_BYTES = 3_200
  const val TTS_SAMPLE_RATE = 24_000

  const val END_FRAME = """{"event":"end"}"""
  const val PING_FRAME = """{"event":"ping"}"""

  /** Languages bulbul:v3 can speak. Note TTS uses `od-IN` for Odia where STT Realtime uses `or-IN`. */
  val TTS_LANGUAGES =
    setOf("bn-IN", "en-IN", "gu-IN", "hi-IN", "kn-IN", "ml-IN", "mr-IN", "od-IN", "pa-IN", "ta-IN", "te-IN")

  private val json = Json { ignoreUnknownKeys = true }

  fun realtimeUrl(wsBase: String = WS_BASE, language: String): String {
    val params =
      linkedMapOf(
        "language_code" to language,
        "model" to "saaras:v3-realtime",
        "stream_type" to "fast",
        "mode" to "transcribe",
        "endpointing" to "vad",
        "encoding" to "linear16",
        "sample_rate" to STT_SAMPLE_RATE.toString(),
        "silence_duration_ms" to "700",
      )
    val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    return "${wsBase.trimEnd('/')}/speech-to-text-realtime/ws?$query"
  }

  fun audioInputFrame(pcm: ByteArray, offset: Int, length: Int): String {
    val b64 = Base64.getEncoder().encodeToString(pcm.copyOfRange(offset, offset + length))
    return """{"event":"audio_input","audio":"$b64"}"""
  }

  fun parseSttEvent(text: String): SttEvent {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return SttEvent.Other(null)
    val event = obj.str("event")
    return when (event) {
      "session.begin" -> SttEvent.SessionBegin
      "vad.speech_start" -> SttEvent.SpeechStart
      "vad.speech_end" -> SttEvent.SpeechEnd
      "transcript.partial" -> SttEvent.Partial(obj.str("text").orEmpty(), obj.str("language"))
      "transcript.final" -> SttEvent.Final(obj.str("text").orEmpty(), obj.str("language"))
      "session.end" -> SttEvent.SessionEnd
      "error" ->
        SttEvent.Error(
          code = obj.str("code").orEmpty(),
          message = obj.str("message").orEmpty(),
          isFatal = obj["is_fatal"]?.jsonPrimitive?.booleanOrNull ?: false,
          statusCode = obj["status_code"]?.jsonPrimitive?.intOrNull,
        )
      else -> SttEvent.Other(event)
    }
  }

  fun ttsRequestJson(text: String, language: String, speaker: String): String =
    buildJsonObject {
        put("text", text)
        put("language_code", language)
        put("model", "bulbul:v3")
        put("speaker", speaker)
        put("pace", 1.0)
        put("speech_sample_rate", TTS_SAMPLE_RATE)
        put("output_audio_codec", "wav")
      }
      .toString()

  /** Returns the first WAV in `audios` (we always send a single text). */
  fun decodeTtsAudio(body: String): ByteArray {
    val audios = json.parseToJsonElement(body).jsonObject["audios"]?.jsonArray
    val first = audios?.firstOrNull()?.jsonPrimitive?.contentOrNull
      ?: throw AgentError.Server(SERVICE, 200)
    return Base64.getDecoder().decode(first)
  }

  /** Maps an HTTP status (REST, or `status_code` on a WS error event) and/or error code. */
  fun errorFor(status: Int?, code: String?): AgentError =
    when {
      status == 401 || status == 403 || code == "invalid_api_key_error" || code == "invalid_subscription_key" ->
        AgentError.Unauthorized(SERVICE)
      status == 402 || code == "insufficient_quota_error" -> AgentError.QuotaExceeded(SERVICE)
      status == 429 || code == "rate_limit_exceeded_error" -> AgentError.RateLimited(SERVICE)
      else -> AgentError.Server(SERVICE, status ?: 0)
    }

  /** The detected STT language if TTS can speak it, else [fallback]. */
  fun ttsLanguageFor(detected: String?, fallback: String): String {
    val mapped = if (detected == "or-IN") "od-IN" else detected
    return if (mapped != null && mapped in TTS_LANGUAGES) mapped else fallback
  }

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
