package com.malladi2610.phoneagent.wakeword

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * The owner's voice: one CAM++ speaker embedding per enrollment take (stored L2-normalized).
 *
 * Verification scores are cosine similarity against the normalized mean of the takes, which is what
 * sherpa-onnx's `SpeakerEmbeddingManager.verify` computes; doing it here keeps it unit-testable.
 * Voice match reduces false wakes from other voices (TV, people nearby); it is not security.
 */
class VoiceProfile(embeddings: List<FloatArray>) {
  val embeddings: List<FloatArray> = embeddings.map { normalize(it) }
  val size: Int
    get() = embeddings.size

  private val centroid: FloatArray

  init {
    require(embeddings.isNotEmpty()) { "A voice profile needs at least one embedding" }
    val dim = embeddings.first().size
    require(dim > 0 && embeddings.all { it.size == dim }) { "Embeddings must share one dimension" }
    centroid = mean(this.embeddings)
  }

  /** Cosine similarity of [embedding] to this profile (1 = same direction). */
  fun score(embedding: FloatArray): Float = cosine(embedding, centroid)

  /** For each take: cosine to the mean of the other takes (leave-one-out). Empty for one take. */
  fun looScores(): List<Float> {
    if (size < 2) return emptyList()
    return embeddings.indices.map { i -> cosine(embeddings[i], mean(embeddings.filterIndexed { j, _ -> j != i })) }
  }

  /** Verification threshold: `clamp(min(0.6, minLOO − 0.05), 0.35, 0.7)`; 0.6 with a single take. */
  fun threshold(): Float {
    val loo = looScores()
    return if (loo.isEmpty()) MAX_THRESHOLD else thresholdFor(loo.min())
  }

  /**
   * Drops takes whose leave-one-out score is below `median − 0.15` (lowest first), never going below
   * [MIN_TAKES] takes.
   */
  fun withoutOutliers(): VoiceProfile {
    val loo = looScores()
    if (size <= MIN_TAKES || loo.isEmpty()) return this
    val cutoff = median(loo) - OUTLIER_MARGIN
    val drop = mutableSetOf<Int>()
    for (i in loo.indices.sortedBy { loo[it] }) {
      if (loo[i] >= cutoff || size - drop.size <= MIN_TAKES) break
      drop += i
    }
    return if (drop.isEmpty()) this else VoiceProfile(embeddings.filterIndexed { i, _ -> i !in drop })
  }

  /** Writes the profile atomically (temp file + rename). */
  fun save(file: File) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    DataOutputStream(tmp.outputStream().buffered()).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(VERSION)
      out.writeInt(size)
      out.writeInt(embeddings.first().size)
      for (e in embeddings) for (v in e) out.writeFloat(v)
    }
    if (!tmp.renameTo(file)) {
      tmp.delete()
      throw IOException("Could not write $file")
    }
  }

  companion object {
    const val MAX_THRESHOLD = 0.6f
    const val MIN_THRESHOLD = 0.35f
    const val CEILING_THRESHOLD = 0.7f
    const val LOO_MARGIN = 0.05f
    const val OUTLIER_MARGIN = 0.15f
    const val MIN_TAKES = 3

    private const val MAGIC = 0x50415650 // "PAVP"
    private const val VERSION = 1
    private const val MAX_COUNT = 100
    private const val MAX_DIM = 8192

    fun thresholdFor(minLoo: Float): Float =
      minOf(MAX_THRESHOLD, minLoo - LOO_MARGIN).coerceIn(MIN_THRESHOLD, CEILING_THRESHOLD)

    /** Reads a profile written by [save]; throws [IOException] if the file is missing or corrupt. */
    fun load(file: File): VoiceProfile {
      DataInputStream(file.inputStream().buffered()).use { input ->
        if (input.readInt() != MAGIC) throw IOException("Not a voice profile: $file")
        if (input.readInt() != VERSION) throw IOException("Unsupported voice profile version")
        val count = input.readInt()
        val dim = input.readInt()
        if (count !in 1..MAX_COUNT || dim !in 1..MAX_DIM) throw IOException("Corrupt voice profile")
        if (file.length() != 16L + 4L * count * dim) throw IOException("Corrupt voice profile")
        return VoiceProfile(List(count) { FloatArray(dim) { input.readFloat() } })
      }
    }

    fun normalize(v: FloatArray): FloatArray {
      var sum = 0.0
      for (x in v) sum += x.toDouble() * x
      val norm = sqrt(sum)
      if (norm == 0.0) return v.copyOf()
      return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
      require(a.size == b.size) { "Dimension mismatch: ${a.size} vs ${b.size}" }
      var dot = 0.0
      var na = 0.0
      var nb = 0.0
      for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        na += a[i].toDouble() * a[i]
        nb += b[i].toDouble() * b[i]
      }
      if (na == 0.0 || nb == 0.0) return 0f
      return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    private fun mean(vs: List<FloatArray>): FloatArray {
      val out = FloatArray(vs.first().size)
      for (v in vs) for (i in v.indices) out[i] += v[i]
      return normalize(out)
    }

    private fun median(xs: List<Float>): Float {
      val s = xs.sorted()
      val m = s.size / 2
      return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }
  }
}
