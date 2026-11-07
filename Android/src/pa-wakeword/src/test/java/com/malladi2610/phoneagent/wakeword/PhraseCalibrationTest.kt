package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseCalibrationTest {
  @Test
  fun templateRange_followsTheFrameTimingRule() {
    // Speech at 1.0–1.8 s: frames whose window ends in [1.2 s, 2.05 s] → k from ceil(19200/1280)−1 = 14
    // to floor(32800/1280)−1 = 24.
    assertEquals(14L..24L, PhraseCalibration.templateRange(16_000, 28_800, 100))
    // Clamped to the frames that exist…
    assertEquals(14L..20L, PhraseCalibration.templateRange(16_000, 28_800, 21))
    // …and to after the warm-up.
    assertEquals(9L..11L, PhraseCalibration.templateRange(0, 12_000, 100))
    // Too few frames left.
    assertNull(PhraseCalibration.templateRange(16_000, 28_800, 16))
  }

  @Test
  fun threshold_isWorstLooPlusMargin_clamped() {
    assertEquals(0.09f, PhraseCalibration.threshold(floatArrayOf(0.05f, 0.07f, 0.06f)), 1e-6f)
    assertEquals(PhraseCalibration.MIN_THRESHOLD, PhraseCalibration.threshold(floatArrayOf(0.01f, 0.02f)), 1e-6f)
    assertEquals(PhraseCalibration.MAX_THRESHOLD, PhraseCalibration.threshold(floatArrayOf(0.05f, 0.2f)), 1e-6f)
    // Non-comparable takes are ignored; nothing comparable → the strictest threshold.
    assertEquals(0.09f, PhraseCalibration.threshold(floatArrayOf(0.07f, Float.POSITIVE_INFINITY)), 1e-6f)
    assertEquals(PhraseCalibration.MIN_THRESHOLD, PhraseCalibration.threshold(floatArrayOf(Float.POSITIVE_INFINITY)), 1e-6f)
  }

  @Test
  fun outlier_needsToBeHighAndFarFromTheRest() {
    assertNull(PhraseCalibration.outlier(floatArrayOf(0.06f, 0.07f, 0.08f, 0.10f))) // under the absolute bar
    assertEquals(3, PhraseCalibration.outlier(floatArrayOf(0.06f, 0.07f, 0.08f, 0.15f)))
    assertNull(PhraseCalibration.outlier(floatArrayOf(0.11f, 0.12f, 0.13f, 0.14f))) // all similar: no single culprit
    assertEquals(1, PhraseCalibration.outlier(floatArrayOf(0.06f, Float.POSITIVE_INFINITY, 0.07f, 0.08f)))
    assertNull(PhraseCalibration.outlier(floatArrayOf(0.06f, 0.5f))) // too few takes to tell
  }

  private fun onehot(i: Int, dim: Int = 8) = FloatArray(dim).also { it[i] = 1f }

  @Test
  fun looCosts_leaveEachTakeOut() {
    val a = listOf(onehot(1), onehot(2), onehot(3))
    val b = listOf(onehot(1), onehot(2), onehot(2), onehot(3)) // same phrase, slower
    val c = listOf(onehot(5), onehot(6), onehot(7)) // something else
    fun take(t: List<FloatArray>) = PhraseCalibration.Take(List(9) { onehot(0) } + t, 9, 8 + t.size)
    val loo = PhraseCalibration.looCosts(listOf(take(a), take(b), take(c)))
    assertEquals(0f, loo[0], 1e-6f) // a matches b exactly (b just repeats a frame)
    assertEquals(0f, loo[1], 1e-6f)
    assertEquals(5f / 6f, loo[2], 1e-6f) // c matches neither: every cell costs 1 (diagonal weights 1+2+2 over n+m=6)
    assertEquals(2, PhraseCalibration.outlier(floatArrayOf(0.05f, 0.05f, loo[2])))
  }
}
