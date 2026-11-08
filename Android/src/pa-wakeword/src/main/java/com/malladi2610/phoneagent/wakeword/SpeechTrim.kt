package com.malladi2610.phoneagent.wakeword

import kotlin.math.sqrt

/**
 * Cuts the audio window around a phrase hit down to the loud part ± [PAD_SECONDS], so the speaker
 * embedding is computed mostly on the phrase. Same function for enrollment and runtime, so both
 * embeddings see comparable windows. Conservative: when it can't find a clear speech region it
 * returns the input unchanged.
 */
object SpeechTrim {
  const val PAD_SECONDS = 0.3f
  const val MIN_SECONDS = 1.0f
  private const val FRAME_SECONDS = 0.02f
  private const val ABS_FLOOR = 0.003f

  fun trim(samples: FloatArray, sampleRate: Int): FloatArray {
    val minLen = (MIN_SECONDS * sampleRate).toInt()
    if (samples.size <= minLen) return samples
    val frame = (FRAME_SECONDS * sampleRate).toInt().coerceAtLeast(1)
    val frames = samples.size / frame
    if (frames < 2) return samples
    val rms = FloatArray(frames) { f ->
      var sum = 0.0
      for (i in f * frame until (f + 1) * frame) sum += samples[i].toDouble() * samples[i]
      sqrt(sum / frame).toFloat()
    }
    val max = rms.max()
    val noise = rms.sorted()[frames / 5]
    val threshold = maxOf(minOf(noise * 3f, max * 0.5f), max * 0.1f, ABS_FLOOR)
    val first = rms.indexOfFirst { it >= threshold }
    val last = rms.indexOfLast { it >= threshold }
    if (first < 0) return samples

    val pad = (PAD_SECONDS * sampleRate).toInt()
    var start = (first * frame - pad).coerceAtLeast(0)
    var end = ((last + 1) * frame + pad).coerceAtMost(samples.size)
    if (end - start < minLen) {
      val grow = minLen - (end - start)
      start = (start - grow / 2).coerceAtLeast(0)
      end = (start + minLen).coerceAtMost(samples.size)
      start = end - minLen
    }
    return if (start == 0 && end == samples.size) samples else samples.copyOfRange(start, end)
  }
}
