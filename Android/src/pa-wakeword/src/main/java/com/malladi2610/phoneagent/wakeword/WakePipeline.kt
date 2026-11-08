package com.malladi2610.phoneagent.wakeword

import java.io.Closeable

/** Streaming detector for the enrolled phrase. Not thread-safe. */
interface PhraseDetector : Closeable {
  /** Feeds 16 kHz mono float samples; true when the phrase was detected (detector already reset). */
  fun accept(samples: FloatArray): Boolean

  /** Drops any buffered audio / frames (e.g. after the mic was reopened). */
  fun reset()
}

/**
 * Cuts the live stream into utterances for matching: an [Endpointer] on the audio plus the recent
 * embedding frames; when an utterance ends (0.4–2.5 s of speech, then 0.3 s of quiet) it returns
 * the frames given by [PhraseCalibration.templateRange], exactly as enrollment cuts its takes.
 */
class UtteranceCutter(private val sampleRate: Int = WakePipeline.SAMPLE_RATE) {
  class Utterance(val start: Long, val end: Long, val frames: List<FloatArray>)

  private val endpointer = Endpointer(sampleRate)
  private val frames = ArrayDeque<FloatArray>()
  private var firstFrame = 0L // stream frame index of frames.first()
  private var frameCount = 0L

  /**
   * [samples] is the audio just fed to the embedding stream and [newFrames] what the stream
   * returned for it.
   */
  fun accept(samples: FloatArray, newFrames: List<FloatArray>): List<Utterance> {
    for (e in newFrames) {
      frames.addLast(e)
      frameCount++
      if (frames.size > MAX_FRAMES) {
        frames.removeFirst()
        firstFrame++
      }
    }
    val segments = endpointer.accept(samples)
    if (segments.isEmpty()) return emptyList()
    val out = mutableListOf<Utterance>()
    for (segment in segments) {
      if (segment.kind != Endpointer.Kind.OK) continue
      val range = PhraseCalibration.templateRange(segment.start, segment.end, frameCount, sampleRate) ?: continue
      if (range.first < firstFrame) continue
      val base = (range.first - firstFrame).toInt()
      out += Utterance(segment.start, segment.end, List((range.last - range.first + 1).toInt()) { frames[base + it] })
    }
    return out
  }

  fun reset() {
    endpointer.reset()
    frames.clear()
    firstFrame = 0
    frameCount = 0
  }

  companion object {
    /** Embedding frames kept: 5.1 s, more than the longest utterance + tail. */
    private const val MAX_FRAMES = 64
  }
}

/**
 * Query-by-example detector: [SpeechEmbeddingStream] + [UtteranceCutter] → [PhraseMatcher]; a hit
 * when an utterance's cost is ≤ [threshold]. Utterances starting within [refractoryMs] of the end
 * of a hit are ignored.
 *
 * Detection needs a short pause after the phrase (the voice turn opens its own mic after the
 * hand-off anyway).
 */
class QbeDetector(
  private val stream: SpeechEmbeddingStream,
  private val matcher: PhraseMatcher,
  val threshold: Float,
  private val refractoryMs: Long = REFRACTORY_MS,
  private val sampleRate: Int = WakePipeline.SAMPLE_RATE,
) : PhraseDetector {
  private val cutter = UtteranceCutter(sampleRate)
  private var lastHitEnd = Long.MIN_VALUE

  /** Cost of the last utterance (+∞ if it wasn't comparable), for the Test screen and logs. */
  @Volatile
  var lastCost = Float.POSITIVE_INFINITY
    private set

  /** Utterances evaluated since the last [reset]. */
  @Volatile
  var utterances = 0
    private set

  override fun accept(samples: FloatArray): Boolean {
    var hit = false
    for (u in cutter.accept(samples, stream.accept(samples))) {
      if (lastHitEnd != Long.MIN_VALUE && u.start < lastHitEnd + refractoryMs * sampleRate / 1000) continue
      val cost = matcher.match(u.frames)
      lastCost = cost
      utterances++
      if (cost <= threshold) {
        hit = true
        lastHitEnd = u.end
      }
    }
    return hit
  }

  override fun reset() {
    stream.reset()
    cutter.reset()
    lastHitEnd = Long.MIN_VALUE
  }

  /** The feature models are owned (and closed) by whoever created them. */
  override fun close() = Unit

  companion object {
    const val REFRACTORY_MS = 1_000L
  }
}

/** Computes a speaker embedding for a window of 16 kHz mono float samples; null if too short. */
fun interface SpeakerEmbedder {
  fun embed(samples: FloatArray): FloatArray?
}

/**
 * Per-chunk wake pipeline: audio → [RingBuffer] + [PhraseDetector] → on a hit, [SpeechTrim] the
 * window, embed it and let the [WakeGate] decide. Owned by a single listening thread.
 */
class WakePipeline(
  private val detector: PhraseDetector,
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
