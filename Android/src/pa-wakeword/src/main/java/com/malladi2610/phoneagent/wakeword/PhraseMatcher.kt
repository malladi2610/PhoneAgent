package com.malladi2610.phoneagent.wakeword

import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Query-by-example phrase matching on speech-embedding frames (pure; unit-tested).
 *
 * Templates are the embedding sequences of the enrolled takes. A live utterance (cut by the same
 * [Endpointer] and the same [PhraseCalibration.templateRange] rule) is compared with every
 * template by DTW anchored at both ends:
 * - local cost `1 − cos` between L2-normalised frames;
 * - steps diagonal (weight 2), template-only and live-only (weight 1), "symmetric2";
 * - total cost divided by `n + m`, i.e. a weighted mean, so takes of different lengths compare on
 *   one scale;
 * - sequences whose lengths differ by more than 2× don't match (+∞).
 *
 * Anchoring both ends at the utterance boundaries matters: in the offline eval a subsequence
 * search (free start/end) let template edges match the tails of other phrases ("…day") and
 * separated the owner's takes from other phrases far worse (AUC 0.85 vs 0.98). See
 * docs/research/wakeword-query-by-example.md, which also has the Python port used for the eval.
 */
class PhraseMatcher(templates: List<List<FloatArray>>) {
  val templates: List<Array<FloatArray>> = templates.map { t -> Array(t.size) { normalize(t[it]) } }

  init {
    require(this.templates.isNotEmpty() && this.templates.all { it.isNotEmpty() }) { "Need non-empty templates" }
  }

  /** Lowest cost of [utterance] (raw frames) against the templates; +∞ if none is comparable. */
  fun match(utterance: List<FloatArray>): Float {
    if (utterance.isEmpty()) return Float.POSITIVE_INFINITY
    val q = Array(utterance.size) { normalize(utterance[it]) }
    var best = Float.POSITIVE_INFINITY
    for (t in templates) best = minOf(best, cost(t, q))
    return best
  }

  companion object {
    const val MAX_LENGTH_RATIO = 2.0

    /** Anchored symmetric2 DTW mean cost of normalised sequences; +∞ if lengths differ > 2×. */
    fun cost(template: Array<FloatArray>, query: Array<FloatArray>): Float {
      val n = template.size
      val m = query.size
      if (n == 0 || m == 0 || n > MAX_LENGTH_RATIO * m || m > MAX_LENGTH_RATIO * n) return Float.POSITIVE_INFINITY
      var prev = DoubleArray(m)
      var cur = DoubleArray(m)
      for (i in 0 until n) {
        for (j in 0 until m) {
          val d = distance(template[i], query[j])
          cur[j] =
            when {
              i == 0 && j == 0 -> d
              i == 0 -> cur[j - 1] + d
              j == 0 -> prev[0] + d
              else -> minOf(prev[j - 1] + 2 * d, minOf(prev[j], cur[j - 1]) + d)
            }
        }
        val t = prev
        prev = cur
        cur = t
      }
      return (prev[m - 1] / (n + m)).toFloat()
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
      var dot = 0.0
      for (k in a.indices) dot += a[k].toDouble() * b[k]
      return 1.0 - dot
    }

    fun normalize(v: FloatArray): FloatArray {
      var sum = 0.0
      for (x in v) sum += x.toDouble() * x
      val norm = sqrt(sum)
      if (norm == 0.0) return v.copyOf()
      return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }
  }
}

/**
 * Enrollment-side maths (pure; unit-tested): which frames represent an utterance, leave-one-out
 * costs, the per-user threshold and the take-consistency check.
 */
object PhraseCalibration {
  /** Utterance frames = embeddings whose window ends in [speechStart + 0.2 s, speechEnd + 0.25 s]. */
  const val TEMPLATE_START_SECONDS = 0.2f
  const val TEMPLATE_END_SECONDS = 0.25f
  private const val MIN_FRAMES = 3

  /** Threshold = worst LOO cost + margin, clamped to a band derived from the offline eval. */
  const val THRESHOLD_MARGIN = 0.02f
  const val MIN_THRESHOLD = 0.08f
  const val MAX_THRESHOLD = 0.11f

  /** A take is an outlier when its LOO cost is above this and ≥ ratio × the median of the rest. */
  const val OUTLIER_ABSOLUTE = 0.11f
  const val OUTLIER_RATIO = 1.4f

  /**
   * One enrollment take: the raw embeddings of its clip (from a fresh [SpeechEmbeddingStream],
   * warm-up frames included) and the utterance's frame range within them.
   */
  class Take(val frames: List<FloatArray>, val templateStart: Int, val templateEnd: Int) {
    init {
      require(templateStart in frames.indices && templateEnd in templateStart until frames.size) {
        "Template range $templateStart..$templateEnd outside ${frames.size} frames"
      }
    }

    val template: List<FloatArray>
      get() = frames.subList(templateStart, templateEnd + 1)
  }

  /**
   * Frame range for speech at samples `[speechStart, speechEnd)` of a stream that produced
   * [frameCount] embeddings since its reset (embedding k covers audio up to sample (k + 1)·1280).
   * Warm-up frames are excluded; null if fewer than 3 frames remain.
   */
  fun templateRange(speechStart: Long, speechEnd: Long, frameCount: Long, sampleRate: Int = WakePipeline.SAMPLE_RATE): LongRange? {
    val hop = SpeechEmbeddingStream.CHUNK.toLong()
    val from = speechStart + (TEMPLATE_START_SECONDS * sampleRate).roundToLong()
    val to = speechEnd + (TEMPLATE_END_SECONDS * sampleRate).roundToLong()
    val k0 = Math.floorDiv(from + hop - 1, hop) - 1 // ceil(from / hop) − 1
    val k1 = Math.floorDiv(to, hop) - 1
    val start = maxOf(k0, SpeechEmbeddingStream.WARMUP_FRAMES.toLong())
    val end = minOf(k1, frameCount - 1)
    return if (end - start + 1 >= MIN_FRAMES) start..end else null
  }

  /** For each take: its cost against the templates of all the other takes. */
  fun looCosts(takes: List<Take>): FloatArray {
    require(takes.size >= 2) { "Need at least two takes" }
    return FloatArray(takes.size) { i ->
      PhraseMatcher(takes.filterIndexed { j, _ -> j != i }.map { it.template }).match(takes[i].template)
    }
  }

  /** Detection threshold from the LOO costs: `clamp(max + 0.02, 0.08, 0.11)`. */
  fun threshold(loo: FloatArray): Float {
    val worst = loo.filter { it.isFinite() }.maxOrNull() ?: return MIN_THRESHOLD
    return (worst + THRESHOLD_MARGIN).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
  }

  /**
   * The take that doesn't sound like the others, or null: the worst LOO cost, if it is not
   * comparable at all, or above [OUTLIER_ABSOLUTE] and ≥ [OUTLIER_RATIO]× the median of the rest.
   */
  fun outlier(loo: FloatArray): Int? {
    if (loo.size < 3) return null
    val worst = loo.indices.maxByOrNull { if (loo[it].isFinite()) loo[it] else Float.MAX_VALUE } ?: return null
    val v = loo[worst]
    if (v.isFinite() && v <= OUTLIER_ABSOLUTE) return null
    val rest = loo.filterIndexed { i, _ -> i != worst }.sorted()
    val median = if (rest.size % 2 == 1) rest[rest.size / 2] else (rest[rest.size / 2 - 1] + rest[rest.size / 2]) / 2f
    return if (!v.isFinite() || v >= OUTLIER_RATIO * median) worst else null
  }
}
