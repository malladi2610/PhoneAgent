package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.core.agent.AgentError
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SarvamWireTest {

  @Test
  fun audioInputFrame_isBase64Pcm() {
    val pcm = ByteArray(3200) { (it % 251).toByte() }

    val frame = Json.parseToJsonElement(SarvamWire.audioInputFrame(pcm, 0, pcm.size)).jsonObject

    assertEquals("audio_input", frame["event"]!!.jsonPrimitive.content)
    assertArrayEquals(pcm, Base64.getDecoder().decode(frame["audio"]!!.jsonPrimitive.content))
    assertEquals(setOf("event", "audio"), frame.keys)
  }

  @Test
  fun audioInputFrame_respectsOffsetAndLength() {
    val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

    val frame = Json.parseToJsonElement(SarvamWire.audioInputFrame(pcm, 2, 3)).jsonObject

    assertArrayEquals(
      byteArrayOf(3, 4, 5),
      Base64.getDecoder().decode(frame["audio"]!!.jsonPrimitive.content),
    )
  }

  @Test
  fun endFrame_isEndEvent() {
    assertEquals("end", Json.parseToJsonElement(SarvamWire.END_FRAME).jsonObject["event"]!!.jsonPrimitive.content)
  }

  @Test
  fun parsesTranscriptFinal_withLanguage() {
    val event =
      SarvamWire.parseSttEvent(
        """{"event":"transcript.final","utterance_idx":0,"text":"turn on the torch","language":"en-IN","language_confidence":0.97}"""
      )

    assertEquals(SttEvent.Final(text = "turn on the torch", language = "en-IN"), event)
  }

  @Test
  fun parsesTranscriptFinal_withoutLanguage() {
    val event =
      SarvamWire.parseSttEvent("""{"event":"transcript.final","utterance_idx":2,"text":"hello"}""")

    assertEquals(SttEvent.Final(text = "hello", language = null), event)
  }

  @Test
  fun parsesTranscriptPartial() {
    val event =
      SarvamWire.parseSttEvent(
        """{"event":"transcript.partial","utterance_idx":0,"text":"turn on","language":"hi-IN"}"""
      )

    assertEquals(SttEvent.Partial(text = "turn on", language = "hi-IN"), event)
  }

  @Test
  fun parsesErrorEvent_fatal() {
    val event =
      SarvamWire.parseSttEvent(
        """{"event":"error","code":"invalid_subscription_key","message":"Invalid key","is_fatal":true,"status_code":401}"""
      )

    assertEquals(
      SttEvent.Error(code = "invalid_subscription_key", message = "Invalid key", isFatal = true, statusCode = 401),
      event,
    )
  }

  @Test
  fun parsesErrorEvent_nonFatal_withoutStatus() {
    val event =
      SarvamWire.parseSttEvent(
        """{"event":"error","code":"invalid_config","is_fatal":false,"message":"bad"}"""
      )

    assertEquals(SttEvent.Error("invalid_config", "bad", isFatal = false, statusCode = null), event)
  }

  @Test
  fun parsesVadEvents() {
    assertEquals(
      SttEvent.SpeechStart,
      SarvamWire.parseSttEvent("""{"event":"vad.speech_start","utterance_idx":0,"confidence":0.91}"""),
    )
    assertEquals(
      SttEvent.SpeechEnd,
      SarvamWire.parseSttEvent("""{"event":"vad.speech_end","utterance_idx":0}"""),
    )
  }

  @Test
  fun parsesSessionEvents_andIgnoresUnknown() {
    assertEquals(
      SttEvent.SessionBegin,
      SarvamWire.parseSttEvent("""{"event":"session.begin","request_id":"r1","config":{"turn_detection":"vad"}}"""),
    )
    assertEquals(
      SttEvent.SessionEnd,
      SarvamWire.parseSttEvent("""{"event":"session.end","request_id":"r1","total_duration_s":5.2}"""),
    )
    assertEquals(SttEvent.Other("pong"), SarvamWire.parseSttEvent("""{"event":"pong"}"""))
    assertEquals(SttEvent.Other(null), SarvamWire.parseSttEvent("not json"))
  }

  @Test
  fun realtimeUrl_carriesDesignParameters() {
    val url = SarvamWire.realtimeUrl("wss://api.sarvam.ai", language = "auto").replace("wss://", "https://").toHttpUrl()

    assertEquals("/speech-to-text-realtime/ws", url.encodedPath)
    assertEquals("auto", url.queryParameter("language_code"))
    assertEquals("saaras:v3-realtime", url.queryParameter("model"))
    assertEquals("fast", url.queryParameter("stream_type"))
    assertEquals("transcribe", url.queryParameter("mode"))
    assertEquals("vad", url.queryParameter("endpointing"))
    assertEquals("linear16", url.queryParameter("encoding"))
    assertEquals("16000", url.queryParameter("sample_rate"))
    assertEquals("700", url.queryParameter("silence_duration_ms"))
  }

  @Test
  fun ttsRequest_isBulbulV3Wav24k() {
    val body =
      Json.parseToJsonElement(SarvamWire.ttsRequestJson("Hi there", language = "en-IN", speaker = "ishita"))
        .jsonObject

    assertEquals("Hi there", body["text"]!!.jsonPrimitive.content)
    assertEquals("en-IN", body["language_code"]!!.jsonPrimitive.content)
    assertEquals("bulbul:v3", body["model"]!!.jsonPrimitive.content)
    assertEquals("ishita", body["speaker"]!!.jsonPrimitive.content)
    assertEquals(24000, body["speech_sample_rate"]!!.jsonPrimitive.content.toInt())
    assertEquals("wav", body["output_audio_codec"]!!.jsonPrimitive.content)
  }

  @Test
  fun ttsResponse_decodesFirstAudio() {
    val wav = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
    val b64 = Base64.getEncoder().encodeToString(wav)

    val audio =
      SarvamWire.decodeTtsAudio("""{"request_id":"abc","audios":["$b64","AAAA"]}""")

    assertArrayEquals(wav, audio)
  }

  @Test
  fun errorFor_mapsStatusAndCodes() {
    assertTrue(SarvamWire.errorFor(403, null) is AgentError.Unauthorized)
    assertTrue(SarvamWire.errorFor(401, "invalid_subscription_key") is AgentError.Unauthorized)
    assertTrue(SarvamWire.errorFor(null, "invalid_api_key_error") is AgentError.Unauthorized)
    assertTrue(SarvamWire.errorFor(402, null) is AgentError.QuotaExceeded)
    assertTrue(SarvamWire.errorFor(null, "insufficient_quota_error") is AgentError.QuotaExceeded)
    assertTrue(SarvamWire.errorFor(429, null) is AgentError.RateLimited)
    assertTrue(SarvamWire.errorFor(503, null) is AgentError.Server)
    assertTrue(SarvamWire.errorFor(403, null).userMessage.contains("Sarvam"))
  }

  @Test
  fun ttsLanguageFor_prefersDetectedWhenSupported() {
    assertEquals("hi-IN", SarvamWire.ttsLanguageFor("hi-IN", fallback = "en-IN"))
    assertEquals("od-IN", SarvamWire.ttsLanguageFor("or-IN", fallback = "en-IN"))
    assertEquals("en-IN", SarvamWire.ttsLanguageFor("ur-IN", fallback = "en-IN"))
    assertEquals("en-IN", SarvamWire.ttsLanguageFor(null, fallback = "en-IN"))
  }
}
