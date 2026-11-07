package com.malladi2610.phoneagent.wakeword

import java.io.Closeable
import kotlin.math.roundToInt

/**
 * The two openWakeWord feature models (Apache-2.0): `melspectrogram` (fixed torchaudio-style
 * log-mel, 32 bins, 10 ms hop) and `embedding_model` (Google's speech_embedding, 76×32 mel window
 * → 96-dim embedding). Implementations: [TfliteFeatureModels] on the device, an ONNX Runtime one in
 * the JVM unit tests. Not thread-safe.
 */
interface FeatureModels : Closeable {
  /**
   * Raw melspectrogram of int16-scale samples (values in [-32768, 32767]); returns
   * `samples.size / 160 - 3` frames of 32 bins, before the `x / 10 + 2` transform.
   */
  fun melspectrogram(samples: FloatArray): Array<FloatArray>

  /** Embeds one transformed 76×32 mel window; returns 96 floats. */
  fun embed(window: Array<FloatArray>): FloatArray
}

/**
 * Streaming speech embeddings, a line-by-line port of openWakeWord's
 * `AudioFeatures._streaming_features` (utils.py, v0.6.0):
 * - audio is consumed in 1280-sample (80 ms) chunks;
 * - each chunk, plus up to 480 samples of the previous audio, goes through the melspectrogram model
 *   (8 new frames; 5 for the very first chunk), transformed `x / 10 + 2`;
 * - the mel buffer starts as 76 rows of 1.0 (as in Python), and every chunk yields one embedding of
 *   the newest 76 mel frames.
 *
 * So embedding k covers audio up to sample `(k + 1) * 1280` since the last [reset]. The first
 * [WARMUP_FRAMES] embeddings still see the synthetic 1.0 rows and should not be matched.
 */
class SpeechEmbeddingStream(private val models: FeatureModels) {
  private val pending = FloatArray(CHUNK)
  private var pendingSize = 0
  private val history = FloatArray(HISTORY)
  private var historySize = 0
  private var mel = ArrayDeque<FloatArray>()

  /** Embeddings produced since the last [reset]. */
  var frames = 0L
    private set

  init {
    reset()
  }

  fun reset() {
    pendingSize = 0
    historySize = 0
    frames = 0
    mel = ArrayDeque<FloatArray>().apply { repeat(WINDOW) { add(FloatArray(MEL_BINS) { 1f }) } }
  }

  /** Feeds float samples in [-1, 1]; returns the (0 or more) new raw 96-dim embeddings. */
  fun accept(samples: FloatArray): List<FloatArray> {
    var out: MutableList<FloatArray>? = null
    var i = 0
    while (i < samples.size) {
      val n = minOf(CHUNK - pendingSize, samples.size - i)
      for (k in 0 until n) pending[pendingSize + k] = toInt16Scale(samples[i + k])
      pendingSize += n
      i += n
      if (pendingSize == CHUNK) {
        (out ?: mutableListOf<FloatArray>().also { out = it }).add(processChunk())
        pendingSize = 0
      }
    }
    return out ?: emptyList()
  }

  private fun processChunk(): FloatArray {
    val input = FloatArray(historySize + CHUNK)
    System.arraycopy(history, 0, input, 0, historySize)
    System.arraycopy(pending, 0, input, historySize, CHUNK)
    for (row in models.melspectrogram(input)) {
      mel.addLast(FloatArray(MEL_BINS) { row[it] / 10f + 2f })
      if (mel.size > WINDOW) mel.removeFirst()
    }
    // Keep the last 480 samples (input is always ≥ 1280 long).
    System.arraycopy(input, input.size - HISTORY, history, 0, HISTORY)
    historySize = HISTORY
    frames++
    return models.embed(mel.toTypedArray())
  }

  companion object {
    const val CHUNK = 1280
    const val HISTORY = 480
    const val WINDOW = 76
    const val MEL_BINS = 32
    const val DIM = 96
    const val FRAME_MS = 80L
    /**
     * The first embeddings after a reset whose window still includes the initial 1.0 rows
     * (5 + 8·(k − 1) < 76 mel frames for k ≤ 9).
     */
    const val WARMUP_FRAMES = 9

    /** Float sample → int16 scale exactly as recorded (mic floats are `short / 32768f`). */
    fun toInt16Scale(x: Float): Float = (x * 32768f).roundToInt().coerceIn(-32768, 32767).toFloat()
  }
}
