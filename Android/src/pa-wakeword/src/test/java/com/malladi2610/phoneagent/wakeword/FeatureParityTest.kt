package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Kotlin streaming feature pipeline ([SpeechEmbeddingStream]) against embeddings produced by
 * openWakeWord's own Python `AudioFeatures` streaming code for the same audio (fixture made by the
 * eval scripts: synthetic "Hey Friday", 1280-sample chunks, ONNX models). The device runs the
 * .tflite copies of the same models; their outputs match the ONNX ones to ~1e-5 (see
 * docs/research/wakeword-query-by-example.md).
 */
class FeatureParityTest {
  private val reference = TestAudio.floatRows(TestAudio.resource("parity_ref.f32"))
  private val audio = TestAudio.readWav(TestAudio.resource("parity.wav")).first

  private fun run(chunk: Int): List<FloatArray> =
    TestAudio.onnxModels().use { models ->
      val stream = SpeechEmbeddingStream(models)
      val out = mutableListOf<FloatArray>()
      var i = 0
      while (i < audio.size) {
        out += stream.accept(audio.copyOfRange(i, minOf(audio.size, i + chunk)))
        i += chunk
      }
      out
    }

  private fun assertMatches(actual: List<FloatArray>) {
    assertEquals(reference.size, actual.size)
    var maxErr = 0f
    var scale = 0f
    for (k in reference.indices) for (d in 0 until SpeechEmbeddingStream.DIM) {
      maxErr = maxOf(maxErr, abs(reference[k][d] - actual[k][d]))
      scale = maxOf(scale, abs(reference[k][d]))
    }
    println("parity: max abs error $maxErr (values up to $scale)")
    assertTrue("max abs error $maxErr (values up to $scale)", maxErr < 1e-3f)
  }

  @Test
  fun matchesOpenWakeWord_in80msChunks() = assertMatches(run(SpeechEmbeddingStream.CHUNK))

  @Test
  fun chunkSizeDoesNotMatter() {
    assertMatches(run(1600)) // MicCapture's old 100 ms chunks
    assertMatches(run(333))
  }

  @Test
  fun frameCounterAndReset() =
    TestAudio.onnxModels().use { models ->
      val stream = SpeechEmbeddingStream(models)
      stream.accept(audio.copyOfRange(0, 3000))
      assertEquals(2L, stream.frames)
      stream.reset()
      assertEquals(0L, stream.frames)
      val again = stream.accept(audio)
      assertMatches(again)
    }

  @Test
  fun int16Scaling() {
    assertEquals(-32768f, SpeechEmbeddingStream.toInt16Scale(-1f))
    assertEquals(32767f, SpeechEmbeddingStream.toInt16Scale(1f))
    assertEquals(1234f, SpeechEmbeddingStream.toInt16Scale(1234 / 32768f))
  }
}
