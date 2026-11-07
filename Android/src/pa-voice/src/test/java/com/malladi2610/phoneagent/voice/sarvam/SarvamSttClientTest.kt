package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.voice.SttResult
import com.malladi2610.phoneagent.voice.audio.PcmSource
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SarvamSttClientTest {
  private lateinit var server: MockWebServer
  private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
  private val key = "sk_test_SECRET123"
  private val mic = FakeMic()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun client(
    keyValue: String? = key,
    noSpeechMs: Long = 6_000,
    maxUtteranceMs: Long = 20_000,
    finalGraceMs: Long = 3_000,
  ) =
    SarvamSttClient(
      http = http,
      keyProvider = { keyValue },
      mic = mic,
      wsBase = "ws://${server.hostName}:${server.port}",
      noSpeechTimeoutMs = noSpeechMs,
      maxUtteranceMs = maxUtteranceMs,
      finalGraceMs = finalGraceMs,
    )

  /** Server side of the socket: records client frames, reacts via [onFrame]. */
  private inner class ScriptedServer(val onFrame: ScriptedServer.(WebSocket, String) -> Unit = { _, _ -> }) :
    WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    val audioFrames = AtomicInteger()

    override fun onOpen(webSocket: WebSocket, response: Response) {
      webSocket.send("""{"event":"session.begin","request_id":"r1","config":{}}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      frames += text
      if (text.contains("\"audio_input\"")) audioFrames.incrementAndGet()
      onFrame(webSocket, text)
    }

    /** Echo the client's close frame, like a real server. */
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      webSocket.close(1000, null)
    }
  }

  private fun expectError(block: suspend () -> Unit): AgentError {
    try {
      runBlocking { block() }
    } catch (e: AgentError) {
      return e
    }
    fail("expected AgentError")
    error("unreachable")
  }

  @Test
  fun streamsBase64Chunks_sendsEndAfterSpeechEnd_returnsFinal() {
    val script = ScriptedServer { ws, text ->
      when {
        text.contains("audio_input") && audioFrames.get() == 3 ->
          ws.send("""{"event":"vad.speech_start","utterance_idx":0,"confidence":0.9}""")
        text.contains("audio_input") && audioFrames.get() == 5 -> {
          ws.send("""{"event":"transcript.partial","utterance_idx":0,"text":"turn on","language":"en-IN"}""")
          ws.send("""{"event":"vad.speech_end","utterance_idx":0}""")
        }
        text == SarvamWire.END_FRAME -> {
          ws.send(
            """{"event":"transcript.final","utterance_idx":0,"text":"turn on the torch","language":"en-IN","language_confidence":0.97}"""
          )
          ws.send("""{"event":"session.end","request_id":"r1"}""")
          ws.close(1000, null)
        }
      }
    }
    server.enqueue(MockResponse().withWebSocketUpgrade(script))

    val result = runBlocking { client().listenOnce("auto") }

    assertEquals(SttResult("turn on the torch", "en-IN"), result)
    val req = server.takeRequest()
    assertEquals(key, req.getHeader("Api-Subscription-Key"))
    assertTrue(req.path!!.startsWith("/speech-to-text-realtime/ws?"))
    assertTrue(req.path!!.contains("language_code=auto"))
    assertTrue(req.path!!.contains("model=saaras%3Av3-realtime") || req.path!!.contains("model=saaras:v3-realtime"))
    val first = Json.parseToJsonElement(script.frames.first()).jsonObject
    assertEquals("audio_input", first["event"]!!.jsonPrimitive.content)
    assertEquals(3200, Base64.getDecoder().decode(first["audio"]!!.jsonPrimitive.content).size)
    assertEquals(1, script.frames.count { it == SarvamWire.END_FRAME })
    assertTrue(mic.stopped.get())
  }

  @Test
  fun fixedLanguage_reportsThatLanguage() {
    server.enqueue(
      MockResponse()
        .withWebSocketUpgrade(
          ScriptedServer { ws, text ->
            if (text.contains("audio_input") && audioFrames.get() == 2) {
              ws.send("""{"event":"vad.speech_start","utterance_idx":0}""")
              ws.send("""{"event":"transcript.final","utterance_idx":0,"text":"namaste"}""")
            }
          }
        )
    )

    val result = runBlocking { client().listenOnce("hi-IN") }

    assertEquals(SttResult("namaste", "hi-IN"), result)
    assertTrue(server.takeRequest().path!!.contains("language_code=hi-IN"))
  }

  @Test
  fun noSpeech_timesOutToEmptyResult() {
    server.enqueue(MockResponse().withWebSocketUpgrade(ScriptedServer()))

    val started = System.nanoTime()
    val result = runBlocking { client(noSpeechMs = 300).listenOnce("auto") }

    assertEquals("", result.text)
    assertNull(result.language)
    assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    assertTrue(mic.stopped.get())
  }

  @Test
  fun maxUtterance_capSendsEnd_thenReturnsFinal() {
    val script = ScriptedServer { ws, text ->
      if (text.contains("audio_input") && audioFrames.get() == 1) {
        ws.send("""{"event":"vad.speech_start","utterance_idx":0}""")
      }
      if (text == SarvamWire.END_FRAME) {
        ws.send("""{"event":"transcript.final","utterance_idx":0,"text":"a very long request","language":"en-IN"}""")
      }
    }
    server.enqueue(MockResponse().withWebSocketUpgrade(script))

    val result = runBlocking { client(maxUtteranceMs = 400).listenOnce("auto") }

    assertEquals("a very long request", result.text)
    assertEquals(1, script.frames.count { it == SarvamWire.END_FRAME })
  }

  @Test
  fun finalNeverArrives_fallsBackToLastPartial() {
    server.enqueue(
      MockResponse()
        .withWebSocketUpgrade(
          ScriptedServer { ws, text ->
            if (text.contains("audio_input") && audioFrames.get() == 1) {
              ws.send("""{"event":"vad.speech_start","utterance_idx":0}""")
              ws.send("""{"event":"transcript.partial","utterance_idx":0,"text":"what's my battery","language":"en-IN"}""")
              ws.send("""{"event":"vad.speech_end","utterance_idx":0}""")
            }
          }
        )
    )

    val result = runBlocking { client(finalGraceMs = 300).listenOnce("auto") }

    assertEquals(SttResult("what's my battery", "en-IN"), result)
  }

  @Test
  fun fatalErrorEvent_badKey_mapsToUnauthorized() {
    server.enqueue(
      MockResponse()
        .withWebSocketUpgrade(
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              webSocket.send(
                """{"event":"error","code":"invalid_subscription_key","message":"Invalid API key","is_fatal":true,"status_code":401}"""
              )
              webSocket.close(1003, "invalid_subscription_key")
            }
          }
        )
    )

    val e = expectError { client().listenOnce("auto") }

    assertTrue(e is AgentError.Unauthorized)
    assertFalse(e.message!!.contains(key))
    assertTrue(mic.stopped.get() || !mic.started.get())
  }

  @Test
  fun handshake403_mapsToUnauthorized() {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"invalid_api_key_error"}}"""))

    val e = expectError { client().listenOnce("auto") }

    assertTrue(e is AgentError.Unauthorized)
  }

  @Test
  fun missingKey_throwsMissingKey_withoutConnectingOrRecording() {
    val e = expectError { client(keyValue = null).listenOnce("auto") }

    assertTrue(e is AgentError.MissingKey)
    assertEquals(0, server.requestCount)
    assertFalse(mic.started.get())
  }

  @Test
  fun connectionFailure_mapsToNetwork() {
    server.shutdown()

    val e = expectError { client().listenOnce("auto") }

    assertTrue(e is AgentError.Network)
  }

  private class FakeMic : PcmSource {
    val started = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)

    override fun chunks(): Flow<ByteArray> = flow {
      started.set(true)
      try {
        var i = 0
        while (true) {
          emit(ByteArray(SarvamWire.STT_CHUNK_BYTES) { (i + it).toByte() })
          i++
          delay(20)
        }
      } finally {
        stopped.set(true)
      }
    }
  }
}
