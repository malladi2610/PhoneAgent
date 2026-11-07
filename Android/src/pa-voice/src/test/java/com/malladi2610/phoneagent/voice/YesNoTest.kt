package com.malladi2610.phoneagent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YesNoTest {
  private val yes =
    listOf("yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "do it", "send it", "haan", "ha", "ji")

  @Test
  fun everyYesWord_isYes() {
    for (w in yes) assertTrue("'$w' should be yes", YesNo.isYes(w))
  }

  @Test
  fun yesWords_withCaseAndPunctuation_areYes() {
    assertTrue(YesNo.isYes("Yes."))
    assertTrue(YesNo.isYes("  OKAY! "))
    assertTrue(YesNo.isYes("Go ahead, please"))
    assertTrue(YesNo.isYes("yes please"))
    assertTrue(YesNo.isYes("Haan ji"))
  }

  @Test
  fun noAndUnclear_areNo() {
    for (w in listOf("no", "cancel", "stop", "nahi", "don't", "", "maybe later")) {
      assertFalse("'$w' should be no", YesNo.isYes(w))
    }
  }

  @Test
  fun negatedYes_isNo() {
    assertFalse(YesNo.isYes("no don't send it"))
    assertFalse(YesNo.isYes("don't do it"))
    assertFalse(YesNo.isYes("okay no"))
    assertFalse(YesNo.isYes("yes, wait, no"))
  }

  @Test
  fun devanagariTranscripts() { // Sarvam returns native script for hi-IN
    assertTrue(YesNo.isYes("हाँ"))
    assertTrue(YesNo.isYes("हां जी"))
    assertFalse(YesNo.isYes("नहीं"))
  }

  @Test
  fun yesInsideOtherWords_isNotYes() {
    assertFalse(YesNo.isYes("hamburger")) // contains "ha"
    assertFalse(YesNo.isYes("jill"))
    assertFalse(YesNo.isYes("the weather"))
  }
}
