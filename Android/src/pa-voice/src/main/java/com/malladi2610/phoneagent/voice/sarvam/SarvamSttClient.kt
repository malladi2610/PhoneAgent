package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.voice.SpeechInput
import com.malladi2610.phoneagent.voice.SttResult
import com.malladi2610.phoneagent.voice.audio.PcmSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One-shot speech-to-text over the Sarvam STT Realtime WebSocket (`saaras:v3-realtime`, VAD
 * endpointing). Streams 100 ms PCM chunks from [mic], sends `end` after `vad.speech_end` (or at the
 * [maxUtteranceMs] cap), and returns at `transcript.final`. With no speech within
 * [noSpeechTimeoutMs] it returns an empty result. Never logs the key.
 */
class SarvamSttClient(
  private val http: OkHttpClient,
  private val keyProvider: () -> String?,
  private val mic: PcmSource,
  private val wsBase: String = SarvamWire.WS_BASE,
  private val noSpeechTimeoutMs: Long = 6_000,
  private val maxUtteranceMs: Long = 20_000,
  /** How long to wait for `transcript.final` after sending `end`. */
  private val finalGraceMs: Long = 4_000,
  /** Runs after the socket starts connecting and before recording (earcon). */
  private val beforeRecording: suspend () -> Unit = {},
  private val onPartial: (String) -> Unit = {},
) : SpeechInput {

  private sealed interface Signal {
    data class Event(val event: SttEvent) : Signal

    data class Closed(val code: Int) : Signal

    data class Failed(val httpCode: Int?) : Signal
  }

  override suspend fun listenOnce(language: String): SttResult {
    val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw AgentError.MissingKey(SarvamWire.SERVICE)
    val signals = Channel<Signal>(Channel.UNLIMITED)
    val request =
      Request.Builder()
        .url(SarvamWire.realtimeUrl(wsBase, language))
        .header(SarvamWire.WS_KEY_HEADER, key)
        .build()
    val ws =
      http.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            signals.trySend(Signal.Event(SarvamWire.parseSttEvent(text)))
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            signals.trySend(Signal.Closed(code))
            webSocket.close(1000, null)
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            signals.trySend(Signal.Closed(code))
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            signals.trySend(Signal.Failed(response?.code))
            response?.close()
          }
        },
      )
    try {
      return coroutineScope {
        beforeRecording()
        // OkHttp queues frames sent before the handshake completes and flushes them on open.
        val pump = launch {
          mic.chunks().collect { chunk -> ws.send(SarvamWire.audioInputFrame(chunk, 0, chunk.size)) }
        }
        val result = awaitResult(signals, ws, pump, language)
        pump.cancel()
        result
      }
    } finally {
      ws.close(1000, null)
      signals.close()
    }
  }

  private suspend fun awaitResult(
    signals: Channel<Signal>,
    ws: WebSocket,
    pump: Job,
    language: String,
  ): SttResult {
    val fixedLanguage = language.takeIf { it != "auto" }
    val start = nowMs()
    var speech = false
    var endSentAt = -1L
    var lastPartial = ""
    var lastLanguage: String? = null
    fun partialResult() = SttResult(lastPartial.trim(), lastLanguage ?: fixedLanguage)
    fun sendEnd() {
      if (endSentAt >= 0) return
      pump.cancel() // stop the mic first
      ws.send(SarvamWire.END_FRAME)
      endSentAt = nowMs()
    }

    while (true) {
      val deadline =
        when {
          endSentAt >= 0 -> endSentAt + finalGraceMs
          !speech -> start + noSpeechTimeoutMs
          else -> start + maxUtteranceMs
        }
      val wait = deadline - nowMs()
      val signal = if (wait <= 0) null else withTimeoutOrNull(wait) { signals.receive() }
      when (signal) {
        null ->
          when {
            endSentAt >= 0 -> return partialResult()
            !speech -> return SttResult("", null)
            else -> sendEnd()
          }
        is Signal.Failed -> throw failureError(signal.httpCode)
        is Signal.Closed ->
          if (signal.code == 1000 || signal.code == 1001) return partialResult()
          else throw closeError(signal.code)
        is Signal.Event ->
          when (val e = signal.event) {
            SttEvent.SpeechStart -> speech = true
            is SttEvent.Partial -> {
              if (e.text.isNotBlank()) {
                speech = true
                lastPartial = e.text
                lastLanguage = e.language ?: lastLanguage
                onPartial(e.text)
              }
            }
            SttEvent.SpeechEnd -> if (speech) sendEnd()
            is SttEvent.Final -> {
              if (e.text.isNotBlank() || endSentAt >= 0) {
                val text = e.text.ifBlank { lastPartial }.trim()
                return SttResult(text, e.language ?: lastLanguage ?: fixedLanguage)
              }
            }
            is SttEvent.Error -> if (e.isFatal) throw SarvamWire.errorFor(e.statusCode, e.code)
            SttEvent.SessionEnd -> return partialResult()
            SttEvent.SessionBegin,
            is SttEvent.Other -> Unit
          }
      }
    }
  }

  private fun failureError(httpCode: Int?): AgentError =
    if (httpCode == null) AgentError.Network(SarvamWire.SERVICE) else SarvamWire.errorFor(httpCode, null)

  /** Close without a preceding error event (normally an `error` event arrives first). */
  private fun closeError(code: Int): AgentError = AgentError.Server(SarvamWire.SERVICE, code)

  private fun nowMs() = System.nanoTime() / 1_000_000
}
