package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EnrollmentLogicTest {
  private val sr = 16_000
  private val rng = Random(3)

  private fun noise(seconds: Float, db: Double = -65.0): FloatArray {
    val sigma = Math.pow(10.0, db / 20)
    return FloatArray((seconds * sr).toInt()) { (rng.nextGaussian() * sigma).toFloat() }
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

  private fun scaled(x: FloatArray, gain: Float) = FloatArray(x.size) { (x[it] * gain).coerceIn(-1f, 1f) }

  /** The TTS "Hey Friday" of the parity fixture, without its padding. */
  private val phrase: FloatArray =
    TestAudio.readWav(TestAudio.resource("parity.wav")).first.let { it.copyOfRange(16_000, it.size - 8_000) }

  private fun record(audio: FloatArray): List<EnrollmentLogic.TakeResult> {
    val r = EnrollmentLogic.TakeRecorder()
    val out = mutableListOf<EnrollmentLogic.TakeResult>()
    var i = 0
    while (i < audio.size) {
      out += r.accept(audio.copyOfRange(i, minOf(audio.size, i + MicCapture.CHUNK_SAMPLES)))
      i += MicCapture.CHUNK_SAMPLES
    }
    return out
  }

  @Test
  fun phraseValidation_acceptsAnyWords() {
    assertNull(EnrollmentLogic.phraseError("Hey Friday"))
    assertNull(EnrollmentLogic.phraseError("Okay Zorblax")) // no dictionary any more
    assertNull(EnrollmentLogic.phraseError("नमस्ते दोस्त"))
    assertNotNull(EnrollmentLogic.phraseError("   "))
    assertNotNull(EnrollmentLogic.phraseError("a"))
    assertNotNull(EnrollmentLogic.phraseError("?!"))
    assertNotNull(EnrollmentLogic.phraseError("x".repeat(41)))
  }

  @Test
  fun recorder_cutsEachTake_withPreRoll() {
    val results = record(cat(noise(1.5f), phrase, noise(1f), phrase, noise(0.6f)))
    val takes = results.filterIsInstance<EnrollmentLogic.TakeResult.Accepted>().map { it.take }
    assertEquals(2, takes.size)
    for (t in takes) {
      // ~1 s before the speech, ~0.3 s after (the endpointer reports after 0.3 s of quiet).
      assertEquals(1.0f, t.speechStart / 16_000f, 0.05f)
      assertEquals(0.34f, (t.clip.size - t.speechEnd) / 16_000f, 0.05f) // + up to one 80 ms chunk
      assertTrue(t.peakDb > -35f)
    }
  }

  @Test
  fun recorder_rejectsQuietLoudLong_andIgnoresClicks() {
    val click = FloatArray(1600) { if (it % 2 == 0) 0.5f else -0.5f } // 100 ms
    val long = cat(phrase, phrase, phrase, phrase) // ~3.6 s, gaps shorter than 0.3 s
    val results =
      record(
        cat(noise(1f), click, noise(1f), scaled(phrase, 0.02f), noise(1f), scaled(phrase, 40f), noise(1f), long, noise(1f), scaled(phrase, 1.7f), noise(1f))
      )
    val kinds =
      results.map {
        when (it) {
          EnrollmentLogic.TakeResult.Ignored -> "ignored"
          is EnrollmentLogic.TakeResult.Rejected -> it.message.substringBefore('.')
          is EnrollmentLogic.TakeResult.Accepted -> "ok" + (it.hint?.let { "+hint" } ?: "")
        }
      }
    assertEquals(listOf("ignored", "Too quiet", "Too loud", "Too long", "ok+hint"), kinds)
  }

  private class FakeSpeaker : SpeakerEmbedder {
    var calls = 0

    override fun embed(samples: FloatArray): FloatArray? {
      calls++
      return floatArrayOf(1f, 0.1f * calls, 0f)
    }
  }

  private fun take(audio: FloatArray): EnrollmentLogic.TakeAudio =
    (record(cat(noise(1.5f), audio, noise(0.6f))).single() as EnrollmentLogic.TakeResult.Accepted).take

  @Test
  fun build_learnsTemplatesThresholdAndVoice() =
    TestAudio.onnxModels().use { models ->
      // Four takes of the same TTS phrase at slightly different levels: very consistent.
      val takes = listOf(1f, 0.8f, 1.2f, 0.9f).map { take(scaled(phrase, it)) }
      val speaker = FakeSpeaker()
      val outcome = EnrollmentLogic.build(" Hey Friday ", takes, models, speaker, allowRedo = true)
      outcome as EnrollmentLogic.Outcome.Done
      val e = outcome.enrollment
      assertEquals("Hey Friday", e.phrase)
      assertEquals(4, e.templates.size)
      assertEquals(4, outcome.keptTakes)
      assertTrue(outcome.loo.all { it < 0.05f })
      assertEquals(PhraseCalibration.MIN_THRESHOLD, e.threshold, 1e-6f)
      assertEquals(4, speaker.calls)
      // The templates detect the phrase again through the live path.
      val detector = QbeDetector(SpeechEmbeddingStream(models), PhraseMatcher(e.templates), e.threshold)
      val live = cat(noise(1.5f), scaled(phrase, 1.1f), noise(0.6f))
      var hits = 0
      var i = 0
      while (i < live.size) {
        if (detector.accept(live.copyOfRange(i, minOf(live.size, i + 1280)))) hits++
        i += 1280
      }
      assertEquals(1, hits)
      assertTrue(detector.lastCost < 0.05f)
    }

  @Test
  fun build_asksToRedoTheOddTakeOut_thenDropsIt() =
    TestAudio.onnxModels().use { models ->
      // Three takes of the phrase, one of something else (the phrase reversed).
      val odd = phrase.reversedArray()
      val takes = listOf(take(phrase), take(scaled(phrase, 0.8f)), take(odd), take(scaled(phrase, 1.2f)))
      val redo = EnrollmentLogic.build("Hey Friday", takes, models, FakeSpeaker(), allowRedo = true)
      redo as EnrollmentLogic.Outcome.Redo
      assertEquals(2, redo.index)
      val done = EnrollmentLogic.build("Hey Friday", takes, models, FakeSpeaker(), allowRedo = false)
      done as EnrollmentLogic.Outcome.Done
      assertEquals(3, done.keptTakes)
      assertEquals(3, done.enrollment.templates.size)
    }

  @Test
  fun build_failsWithoutAVoice() =
    TestAudio.onnxModels().use { models ->
      val takes = List(4) { take(phrase) }
      val outcome = EnrollmentLogic.build("Hey Friday", takes, models, { null }, allowRedo = true)
      assertTrue(outcome is EnrollmentLogic.Outcome.Failed)
    }
}
