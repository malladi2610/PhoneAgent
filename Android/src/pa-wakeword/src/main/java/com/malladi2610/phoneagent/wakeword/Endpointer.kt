package com.malladi2610.phoneagent.wakeword

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Energy-based endpointing for enrollment takes, on 20 ms frames (pure; unit-tested).
 *
 * - Noise floor: the 10th percentile of the frame levels over the last 3 s (all-zero frames, e.g.
 *   the mic warming up, are ignored).
 * - Speech starts after [START_FRAMES] consecutive frames above `max(floor + 12, −55)` dBFS.
 * - It ends after [END_SILENCE_FRAMES] (0.3 s) of frames below `max(floor + 8, peak − 35)`, where
 *   peak is the loudest frame of the segment; the segment is reported then (so "when the user stops
 *   speaking"), spanning the first loud frame to the last voiced one.
 *
 * Sample positions count from the first sample ever fed in. Mirrored by the eval script's Python
 * port (see docs/research/wakeword-query-by-example.md).
 */
class Endpointer(private val sampleRate: Int = WakePipeline.SAMPLE_RATE) {
  enum class Kind {
    OK,
    TOO_SHORT,
    TOO_LONG,
  }

  /** A finished utterance: samples `[start, end)`; [peakDb] is its loudest 20 ms frame (dBFS). */
  data class Segment(val kind: Kind, val start: Long, val end: Long, val peakDb: Float) {
    val seconds: Float
      get() = (end - start) / WakePipeline.SAMPLE_RATE.toFloat()
  }

  private val frameSize = sampleRate / 50
  private val frame = FloatArray(frameSize)
  private var frameFill = 0
  private var frameIndex = 0L

  private val floorHistory = FloatArray(FLOOR_FRAMES)
  private var floorCount = 0
  private var floorNext = 0

  private var run = 0
  private var inSpeech = false
  private var startFrame = 0L
  private var lastVoiced = 0L
  private var peak = SILENCE_DB

  /** Level of the most recent frame (dBFS), for a live meter. */
  var lastLevelDb = SILENCE_DB
    private set

  val inSpeechNow: Boolean
    get() = inSpeech

  private val sorted = FloatArray(FLOOR_FRAMES)

  /** Current noise-floor estimate (dBFS): 10th percentile of the last 3 s of frame levels. */
  val floorDb: Float
    get() {
      if (floorCount == 0) return DEFAULT_FLOOR_DB
      System.arraycopy(floorHistory, 0, sorted, 0, floorCount)
      java.util.Arrays.sort(sorted, 0, floorCount)
      return sorted[floorCount / 10]
    }

  fun reset() {
    frameFill = 0
    frameIndex = 0
    floorCount = 0
    floorNext = 0
    run = 0
    inSpeech = false
    peak = SILENCE_DB
    lastLevelDb = SILENCE_DB
  }

  /** Feeds samples in [-1, 1]; returns the segments that ended within them. */
  fun accept(samples: FloatArray): List<Segment> {
    var out: MutableList<Segment>? = null
    for (x in samples) {
      frame[frameFill++] = x
      if (frameFill == frameSize) {
        frameFill = 0
        onFrame()?.let { (out ?: mutableListOf<Segment>().also { l -> out = l }).add(it) }
      }
    }
    return out ?: emptyList()
  }

  private fun onFrame(): Segment? {
    val i = frameIndex++
    var sum = 0.0
    for (x in frame) sum += x.toDouble() * x
    val ms = sum / frameSize
    val floor = floorDb
    val level: Float
    if (ms == 0.0) {
      level = SILENCE_DB
    } else {
      level = max(SILENCE_DB.toDouble(), 10.0 * log10(ms)).toFloat()
      floorHistory[floorNext] = level
      floorNext = (floorNext + 1) % FLOOR_FRAMES
      if (floorCount < FLOOR_FRAMES) floorCount++
    }
    lastLevelDb = level

    if (!inSpeech) {
      if (level > max(floor + START_MARGIN_DB, ABS_MIN_DB)) {
        run++
        peak = max(peak, level)
        if (run >= START_FRAMES) {
          inSpeech = true
          startFrame = i - START_FRAMES + 1
          lastVoiced = i
        }
      } else {
        run = 0
        peak = SILENCE_DB
      }
      return null
    }
    peak = max(peak, level)
    if (level >= max(floor + END_MARGIN_DB, peak - END_BELOW_PEAK_DB)) lastVoiced = i
    if (i - lastVoiced < END_SILENCE_FRAMES) return null
    inSpeech = false
    run = 0
    val start = startFrame * frameSize
    val end = (lastVoiced + 1) * frameSize
    val seconds = (end - start) / sampleRate.toFloat()
    val kind =
      when {
        seconds < MIN_SPEECH_SECONDS -> Kind.TOO_SHORT
        seconds > MAX_SPEECH_SECONDS -> Kind.TOO_LONG
        else -> Kind.OK
      }
    val segment = Segment(kind, start, end, peak)
    peak = SILENCE_DB
    return segment
  }

  companion object {
    const val START_MARGIN_DB = 12f
    const val END_MARGIN_DB = 8f
    const val END_BELOW_PEAK_DB = 35f
    const val ABS_MIN_DB = -55f
    const val START_FRAMES = 3
    const val END_SILENCE_FRAMES = 15
    const val FLOOR_FRAMES = 150
    const val MIN_SPEECH_SECONDS = 0.4f
    const val MAX_SPEECH_SECONDS = 2.5f
    /** A sample at or beyond ±this counts as clipped. */
    const val CLIP_LEVEL = 0.99f
    const val SILENCE_DB = -100f
    private const val DEFAULT_FLOOR_DB = -90f

    /** Share of samples in `[from, to)` at or beyond ±[CLIP_LEVEL]. */
    fun clippedFraction(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
      if (to <= from) return 0f
      var n = 0
      for (i in from until to) if (abs(samples[i]) >= CLIP_LEVEL) n++
      return n.toFloat() / (to - from)
    }

    /** Level of a block of samples in dBFS (RMS), floored at [SILENCE_DB]. */
    fun levelDb(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
      if (to <= from) return SILENCE_DB
      var sum = 0.0
      for (i in from until to) sum += samples[i].toDouble() * samples[i]
      val ms = sum / (to - from)
      return if (ms == 0.0) SILENCE_DB else max(SILENCE_DB.toDouble(), 10.0 * log10(ms)).toFloat()
    }
  }
}
