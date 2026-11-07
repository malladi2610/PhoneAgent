package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePipelineTest {
  private class FakeDetector : PhraseDetector {
    var hitNext = false
    var resets = 0
    var accepted = 0

    override fun accept(samples: FloatArray): Boolean {
      accepted += samples.size
      return hitNext.also { hitNext = false }
    }

    override fun reset() {
      resets++
    }

    override fun close() {}
  }

  private class FakeEmbedder(var result: FloatArray?) : SpeakerEmbedder {
    var lastWindow: FloatArray? = null

    override fun embed(samples: FloatArray): FloatArray? {
      lastWindow = samples
      return result
    }
  }

  private val owner = floatArrayOf(1f, 0f)
  private val profile = VoiceProfile(listOf(owner, owner, owner))
  private val detector = FakeDetector()
  private val embedder = FakeEmbedder(owner)
  private val pipeline =
    WakePipeline(detector, embedder, profile, sampleRate = 10, windowSeconds = 2f) // 20-sample ring

  private fun chunk(value: Float = 0.5f) = FloatArray(5) { value }

  @Test
  fun noKeyword_noEvent() {
    assertNull(pipeline.onAudio(chunk(), nowMs = 0))
    assertEquals(5, detector.accepted)
  }

  @Test
  fun ownerVoice_triggers_andWindowIsTheRingBuffer() {
    repeat(5) { pipeline.onAudio(chunk(0.5f + it * 0.1f), nowMs = it * 100L) }
    detector.hitNext = true
    val event = pipeline.onAudio(chunk(0.9f), nowMs = 600)
    assertTrue(event is WakePipeline.Event.Triggered)
    assertEquals(20, embedder.lastWindow!!.size) // last 2 s at 10 Hz
    // 6 chunks of 5 samples went in; the 20-sample ring holds the last 4 (0.7, 0.8, 0.9, 0.9).
    assertEquals(0.7f, embedder.lastWindow!!.first(), 1e-6f)
    assertEquals(0.9f, embedder.lastWindow!!.last(), 1e-6f)
    assertFalse(pipeline.shouldListen)
  }

  @Test
  fun otherVoice_rejected_keepsListening() {
    embedder.result = floatArrayOf(0f, 1f)
    detector.hitNext = true
    val event = pipeline.onAudio(chunk(), nowMs = 0)
    assertTrue(event is WakePipeline.Event.Rejected)
    assertEquals(0f, (event as WakePipeline.Event.Rejected).score!!, 1e-6f)
    assertTrue(pipeline.shouldListen)
  }

  @Test
  fun noEmbedding_rejected() {
    embedder.result = null
    detector.hitNext = true
    val event = pipeline.onAudio(chunk(), nowMs = 0)
    assertTrue(event is WakePipeline.Event.Rejected)
    assertNull((event as WakePipeline.Event.Rejected).score)
    assertTrue(pipeline.shouldListen)
  }

  @Test
  fun hitWhileVoiceSessionActive_ignored_withoutEmbedding() {
    pipeline.onVoiceSessionActive(true, nowMs = 0)
    assertFalse(pipeline.shouldListen)
    detector.hitNext = true
    assertTrue(pipeline.onAudio(chunk(), nowMs = 100) is WakePipeline.Event.Ignored)
    assertNull(embedder.lastWindow)
  }

  @Test
  fun sessionEnd_resetsDetectorAndAudio_thenCooldown() {
    detector.hitNext = true
    assertTrue(pipeline.onAudio(chunk(), nowMs = 0) is WakePipeline.Event.Triggered)
    pipeline.onVoiceSessionActive(true, nowMs = 100)
    pipeline.onVoiceSessionActive(false, nowMs = 4_000)
    assertTrue(pipeline.shouldListen)
    assertTrue(detector.resets >= 1)
    detector.hitNext = true
    assertTrue(pipeline.onAudio(chunk(), nowMs = 4_500) is WakePipeline.Event.Ignored) // cooldown
    detector.hitNext = true
    // Ring was cleared on resume, so the window only holds audio since then.
    assertTrue(pipeline.onAudio(chunk(), nowMs = 6_000) is WakePipeline.Event.Triggered)
    assertEquals(10, embedder.lastWindow!!.size)
  }
}
