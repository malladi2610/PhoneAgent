package com.malladi2610.phoneagent.wakeword

import com.malladi2610.phoneagent.wakeword.WakeGate.Action.IGNORE
import com.malladi2610.phoneagent.wakeword.WakeGate.Action.TRIGGER
import com.malladi2610.phoneagent.wakeword.WakeGate.Action.VERIFY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGateTest {
  private val gate = WakeGate(threshold = 0.5f, cooldownMs = 2_000, handoffTimeoutMs = 15_000)

  private fun triggerAt(nowMs: Long) {
    gate.onTick(nowMs)
    assertEquals(VERIFY, gate.onKeyword())
    assertEquals(TRIGGER, gate.onVerify(0.8f))
  }

  @Test
  fun verifiedKeyword_triggers() {
    gate.onTick(0)
    assertTrue(gate.shouldListen)
    assertEquals(VERIFY, gate.onKeyword())
    assertEquals(TRIGGER, gate.onVerify(0.5f)) // threshold itself passes
    // The mic is handed to the voice turn.
    assertFalse(gate.shouldListen)
    assertEquals(IGNORE, gate.onKeyword())
  }

  @Test
  fun rejectedScore_ignored() {
    gate.onTick(0)
    assertEquals(VERIFY, gate.onKeyword())
    assertEquals(IGNORE, gate.onVerify(0.49f))
    assertTrue(gate.shouldListen)
    // No cooldown after a rejection: the owner can retry right away.
    gate.onTick(100)
    assertEquals(VERIFY, gate.onKeyword())
  }

  @Test
  fun verifyWithoutKeyword_ignored() {
    gate.onTick(0)
    assertEquals(IGNORE, gate.onVerify(0.99f))
  }

  @Test
  fun detector_pausedWhileVoiceSessionActive() {
    gate.onTick(0)
    gate.onVoiceSessionActive(true) // e.g. side-button session
    assertFalse(gate.shouldListen)
    assertEquals(IGNORE, gate.onKeyword())
    gate.onTick(60_000)
    assertFalse(gate.shouldListen) // no timeout while a session is active
    assertEquals(IGNORE, gate.onKeyword())
  }

  @Test
  fun sessionStartingDuringVerify_dropsTheHit() {
    gate.onTick(0)
    assertEquals(VERIFY, gate.onKeyword())
    gate.onVoiceSessionActive(true)
    assertEquals(IGNORE, gate.onVerify(0.9f))
    assertFalse(gate.shouldListen)
  }

  @Test
  fun cooldown2s_afterTrigger() {
    triggerAt(0)
    gate.onTick(100)
    gate.onVoiceSessionActive(true)
    gate.onTick(5_000)
    gate.onVoiceSessionActive(false)
    assertTrue(gate.shouldListen)
    assertEquals(IGNORE, gate.onKeyword())
    gate.onTick(6_999)
    assertEquals(IGNORE, gate.onKeyword())
    gate.onTick(7_000)
    assertEquals(VERIFY, gate.onKeyword())
  }

  @Test
  fun resumesWhenSessionEnds() {
    gate.onTick(0)
    gate.onVoiceSessionActive(true)
    gate.onTick(3_000)
    gate.onVoiceSessionActive(false)
    assertTrue(gate.shouldListen)
    gate.onTick(5_000)
    assertEquals(VERIFY, gate.onKeyword())
    assertEquals(TRIGGER, gate.onVerify(0.9f))
  }

  @Test
  fun repeatedSessionFlag_isIdempotent() {
    gate.onTick(0)
    gate.onVoiceSessionActive(false)
    assertEquals(VERIFY, gate.onKeyword()) // false while already listening starts no cooldown
  }

  @Test
  fun handoffWithoutSession_resumesAfterTimeout() {
    triggerAt(0)
    gate.onTick(14_999)
    assertFalse(gate.shouldListen)
    gate.onTick(15_000)
    assertTrue(gate.shouldListen)
    gate.onTick(17_000)
    assertEquals(VERIFY, gate.onKeyword())
  }

  @Test
  fun handoffFailed_resumesWithCooldown() {
    triggerAt(0)
    gate.onTick(10)
    gate.onHandoffFailed()
    assertTrue(gate.shouldListen)
    assertEquals(IGNORE, gate.onKeyword())
    gate.onTick(2_010)
    assertEquals(VERIFY, gate.onKeyword())
  }
}
