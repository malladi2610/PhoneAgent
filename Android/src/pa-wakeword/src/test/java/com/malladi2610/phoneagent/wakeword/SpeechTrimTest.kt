package com.malladi2610.phoneagent.wakeword

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTrimTest {
  private val rate = 16_000

  private fun tone(seconds: Float, amp: Float) =
    FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * 220 * it / rate)).toFloat() }

  private fun noise(seconds: Float, amp: Float): FloatArray {
    val r = java.util.Random(7)
    return FloatArray((seconds * rate).toInt()) { (r.nextGaussian() * amp).toFloat() }
  }

  @Test
  fun trimsSilenceAroundSpeech_keepingPadding() {
    val audio = noise(1.0f, 0.001f) + tone(0.8f, 0.3f) + noise(0.7f, 0.001f)
    val out = SpeechTrim.trim(audio, rate)
    // 0.8 s of speech + 0.3 s padding on both sides, within one 20 ms frame.
    assertEquals(1.4f, out.size / rate.toFloat(), 0.05f)
  }

  @Test
  fun shortSpeech_isExpandedToMinimumLength() {
    val audio = noise(1.0f, 0.001f) + tone(0.2f, 0.3f) + noise(1.0f, 0.001f)
    val out = SpeechTrim.trim(audio, rate)
    assertEquals(SpeechTrim.MIN_SECONDS, out.size / rate.toFloat(), 0.03f)
  }

  @Test
  fun allSilenceOrAllSpeech_isUnchanged() {
    val silence = FloatArray(rate * 2)
    assertEquals(silence.size, SpeechTrim.trim(silence, rate).size)
    val speech = tone(2f, 0.3f)
    assertEquals(speech.size, SpeechTrim.trim(speech, rate).size)
  }

  @Test
  fun shorterThanMinimum_isUnchanged() {
    val audio = tone(0.5f, 0.3f)
    assertTrue(SpeechTrim.trim(audio, rate).contentEquals(audio))
  }
}
