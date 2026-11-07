package com.malladi2610.phoneagent.wakeword

import java.io.Closeable

/** Streaming keyword spotter for one phrase. Not thread-safe. */
interface KeywordDetector : Closeable {
  /** Feeds 16 kHz mono float samples; true when the phrase was detected (detector already reset). */
  fun accept(samples: FloatArray): Boolean

  /** Drops any partial decoding state. */
  fun reset()
}

/** Computes a speaker embedding for a window of 16 kHz mono float samples; null if too short. */
fun interface SpeakerEmbedder {
  fun embed(samples: FloatArray): FloatArray?
}

/**
 * Per-chunk wake pipeline: audio → [RingBuffer] + [KeywordDetector] → on a hit, [SpeechTrim] the
 * window, embed it and let the [WakeGate] decide. Owned by a single listening thread.
 */
class WakePipeline(
  private val detector: KeywordDetector,
  private val embedder: SpeakerEmbedder,
  private val profile: VoiceProfile,
  private val sampleRate: Int = SAMPLE_RATE,
  windowSeconds: Float = WINDOW_SECONDS,
  private val gate: WakeGate = WakeGate(profile.threshold()),
) {
  sealed interface Event {
    /** A hit that arrived while paused, handed off or cooling down. */
    data object Ignored : Event

    /** A hit whose voice did not match ([score] null when no embedding could be computed). */
    data class Rejected(val score: Float?) : Event

    /** The owner said the phrase: release the mic and start a voice turn. */
    data class Triggered(val score: Float) : Event
  }

  private val ring = RingBuffer((windowSeconds * sampleRate).toInt())

  val shouldListen: Boolean
    get() = gate.shouldListen

  val threshold: Float = profile.threshold()

  fun tick(nowMs: Long) = gate.onTick(nowMs)

  fun onAudio(chunk: FloatArray, nowMs: Long): Event? {
    gate.onTick(nowMs)
    ring.write(chunk)
    if (!detector.accept(chunk)) return null
    if (gate.onKeyword() != WakeGate.Action.VERIFY) return Event.Ignored
    val embedding = embedder.embed(SpeechTrim.trim(ring.snapshot(), sampleRate))
    if (embedding == null) {
      gate.onVerify(Float.NEGATIVE_INFINITY)
      return Event.Rejected(null)
    }
    val score = profile.score(embedding)
    return if (gate.onVerify(score) == WakeGate.Action.TRIGGER) {
      ring.clear()
      Event.Triggered(score)
    } else {
      Event.Rejected(score)
    }
  }

  fun onVoiceSessionActive(active: Boolean, nowMs: Long) {
    gate.onTick(nowMs)
    val wasListening = gate.shouldListen
    gate.onVoiceSessionActive(active)
    if (!wasListening && gate.shouldListen) resetAudio()
  }

  /** The voice turn could not be started. */
  fun onHandoffFailed(nowMs: Long) {
    gate.onTick(nowMs)
    gate.onHandoffFailed()
    if (gate.shouldListen) resetAudio()
  }

  /** Call after the mic was closed and reopened so stale audio is not decoded. */
  fun resetAudio() {
    ring.clear()
    detector.reset()
  }

  companion object {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_SECONDS = 2.5f
  }
}
