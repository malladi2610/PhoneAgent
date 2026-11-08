package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class EndpointerTest {
  private val sr = 16_000
  private val rng = Random(7)

  private fun noise(seconds: Float, db: Float = -65f): FloatArray {
    val sigma = Math.pow(10.0, db / 20.0)
    return FloatArray((seconds * sr).toInt()) { (rng.nextGaussian() * sigma).toFloat() }
  }

  /** A 200 Hz tone with a little noise, RMS ≈ amplitude / √2. */
  private fun tone(seconds: Float, amplitude: Float = 0.2f): FloatArray {
    val n = noise(seconds)
    return FloatArray(n.size) { n[it] + amplitude * sin(2 * PI * 200 * it / sr).toFloat() }
  }

  private fun cat(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) {
      System.arraycopy(p, 0, out, o, p.size)
      o += p.size
    }
    return out
  }

  private fun feed(ep: Endpointer, audio: FloatArray, chunk: Int = 1280): List<Endpointer.Segment> {
    val out = mutableListOf<Endpointer.Segment>()
    var i = 0
    while (i < audio.size) {
      out += ep.accept(audio.copyOfRange(i, minOf(audio.size, i + chunk)))
      i += chunk
    }
    return out
  }

  @Test
  fun oneUtterance_isReportedAfterTheTrailingQuiet() {
    val ep = Endpointer()
    val audio = cat(noise(1f), tone(0.8f), noise(0.29f))
    assertTrue("not yet: needs 0.3 s of quiet", feed(ep, audio).isEmpty())
    val segments = feed(ep, noise(0.05f))
    assertEquals(1, segments.size)
    val s = segments[0]
    assertEquals(Endpointer.Kind.OK, s.kind)
    assertEquals(16_000.0, s.start.toDouble(), 320.0)
    assertEquals(28_800.0, s.end.toDouble(), 320.0)
    assertEquals(-17f, s.peakDb, 1f) // 0.2 amplitude sine
  }

  @Test
  fun shortGapInsidePhrase_doesNotSplitIt() {
    val ep = Endpointer()
    val segments = feed(ep, cat(noise(1f), tone(0.3f), noise(0.2f), tone(0.4f), noise(0.6f)))
    assertEquals(1, segments.size)
    assertEquals(0.9f, segments[0].seconds, 0.03f)
  }

  @Test
  fun tooShortAndTooLong() {
    val ep = Endpointer()
    val segments = feed(ep, cat(noise(1f), tone(0.2f), noise(1f), tone(3f), noise(1f), tone(1f), noise(0.5f)))
    assertEquals(listOf(Endpointer.Kind.TOO_SHORT, Endpointer.Kind.TOO_LONG, Endpointer.Kind.OK), segments.map { it.kind })
  }

  @Test
  fun quietSound_belowAbsoluteFloor_isIgnored() {
    val ep = Endpointer()
    // −60 dBFS tone over a −90 dBFS floor: above floor + 12 but below the −55 dBFS absolute minimum.
    val segments = feed(ep, cat(noise(1f, -90f), tone(0.8f, 0.0014f), noise(1f, -90f)))
    assertTrue(segments.isEmpty())
  }

  @Test
  fun digitalSilence_atStart_isFine() {
    val ep = Endpointer()
    val segments = feed(ep, cat(FloatArray(8000), noise(0.5f), tone(0.8f), noise(0.5f)))
    assertEquals(1, segments.size)
    assertEquals(Endpointer.Kind.OK, segments[0].kind)
  }

  @Test
  fun chunkSize_doesNotMatter_andMatchesPythonPort() {
    // Python port (qbe_stream.py) on the parity fixture: [('ok', 16320, 28160)].
    val audio = TestAudio.readWav(TestAudio.resource("parity.wav")).first
    for (chunk in listOf(1280, 1600, 77)) {
      val s = feed(Endpointer(), audio, chunk)
      assertEquals(1, s.size)
      assertEquals(16_320L, s[0].start)
      assertEquals(28_160L, s[0].end)
    }
  }

  @Test
  fun levelAndClipping() {
    assertEquals(Endpointer.SILENCE_DB, Endpointer.levelDb(FloatArray(10)))
    assertEquals(-6.02f, Endpointer.levelDb(FloatArray(10) { 0.5f }), 0.01f)
    assertEquals(0.5f, Endpointer.clippedFraction(floatArrayOf(1f, 0.1f, -0.995f, 0f)), 1e-6f)
  }
}
