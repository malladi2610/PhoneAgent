package com.malladi2610.phoneagent.wakeword

import java.io.File
import java.io.IOException
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceProfileTest {
  private fun angle(rad: Double, scale: Float = 1f) =
    floatArrayOf((cos(rad) * scale).toFloat(), (sin(rad) * scale).toFloat())

  @Test
  fun cosine_ofIdenticalNormalizedVectors_isOne() {
    val v = VoiceProfile.normalize(floatArrayOf(0.3f, -1.2f, 4f, 0.01f))
    assertEquals(1f, VoiceProfile.cosine(v, v), 1e-6f)
    // Scale does not matter, opposite directions give -1.
    assertEquals(1f, VoiceProfile.cosine(floatArrayOf(1f, 2f), floatArrayOf(3f, 6f)), 1e-6f)
    assertEquals(-1f, VoiceProfile.cosine(floatArrayOf(1f, 0f), floatArrayOf(-2f, 0f)), 1e-6f)
  }

  @Test
  fun threshold_isMinLooMinus005_clampedTo035_070() {
    // Pure rule: T = clamp(min(0.6, minLoo - 0.05), 0.35, 0.7).
    assertEquals(0.6f, VoiceProfile.thresholdFor(0.95f), 1e-6f)
    assertEquals(0.5f, VoiceProfile.thresholdFor(0.55f), 1e-6f)
    assertEquals(0.35f, VoiceProfile.thresholdFor(0.2f), 1e-6f)

    // Three takes at -θ, 0, +θ: the LOO of an edge take is cos(1.5θ). Pick cos(1.5θ) = 0.6.
    val theta = acos(0.6) / 1.5
    val profile = VoiceProfile(listOf(angle(-theta), angle(0.0, 3f), angle(theta, 0.5f)))
    assertEquals(0.6f, profile.looScores().min(), 1e-4f)
    assertEquals(0.55f, profile.threshold(), 1e-4f)

    // Identical takes: LOO = 1, so the 0.6 cap applies.
    assertEquals(0.6f, VoiceProfile(List(5) { angle(0.2) }).threshold(), 1e-6f)
  }

  @Test
  fun withoutOutliers_dropsLowestLooWhenBelowMedianMinus015() {
    val good = listOf(angle(0.0), angle(0.05), angle(-0.05), angle(0.1))
    val outlier = angle(1.4)
    val profile = VoiceProfile(good + listOf(outlier))
    val cleaned = profile.withoutOutliers()
    assertEquals(4, cleaned.size)
    for (e in cleaned.embeddings) assertTrue(VoiceProfile.cosine(e, outlier) < 0.5f)
    assertTrue(cleaned.threshold() >= profile.threshold())
  }

  @Test
  fun withoutOutliers_keepsConsistentTakes() {
    val profile = VoiceProfile(listOf(angle(0.0), angle(0.1), angle(-0.1), angle(0.2), angle(-0.2)))
    assertEquals(5, profile.withoutOutliers().size)
  }

  @Test
  fun withoutOutliers_neverDropsBelowThreeTakes() {
    // Two clusters: whichever side is "outlying", at least three takes remain.
    val profile =
      VoiceProfile(listOf(angle(0.0), angle(0.01), angle(1.5), angle(1.51), angle(3.0)))
    assertTrue(profile.withoutOutliers().size >= 3)
  }

  @Test
  fun score_isCosineToCentroid() {
    val profile = VoiceProfile(listOf(angle(0.1), angle(-0.1)))
    assertEquals(1f, profile.score(floatArrayOf(5f, 0f)), 1e-5f)
    assertEquals(0f, profile.score(floatArrayOf(0f, 2f)), 1e-5f)
  }

  @Test
  fun saveLoad_roundTrip() {
    val file = File.createTempFile("profile", ".bin")
    try {
      val profile = VoiceProfile(listOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(-1f, 0.5f, 2f)))
      profile.save(file)
      val loaded = VoiceProfile.load(file)
      assertEquals(profile.size, loaded.size)
      for (i in 0 until profile.size) {
        assertArrayEquals(profile.embeddings[i], loaded.embeddings[i], 0f)
      }
      assertEquals(profile.threshold(), loaded.threshold(), 0f)
    } finally {
      file.delete()
    }
  }

  @Test
  fun load_rejectsCorruptFile() {
    val file = File.createTempFile("profile", ".bin")
    try {
      file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
      try {
        VoiceProfile.load(file)
        fail("expected IOException")
      } catch (expected: IOException) {}
    } finally {
      file.delete()
    }
  }
}
