package com.malladi2610.phoneagent.voice

import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaVoiceLauncherTest {
  private var active = false
  private var sessionAvailable = true
  private val shown = mutableListOf<VoiceTrigger>()
  private val headless = mutableListOf<VoiceTrigger>()

  private val launcher =
    PaVoiceLauncher(
      isTurnActive = { active },
      showSession = { trigger -> if (sessionAvailable) shown.add(trigger) else false },
      runHeadless = { headless += it },
    )

  @Test
  fun boundAssistant_showsSession() {
    launcher.startVoiceTurn(VoiceTrigger.WAKE_PHRASE)

    assertEquals(listOf(VoiceTrigger.WAKE_PHRASE), shown)
    assertTrue(headless.isEmpty())
  }

  @Test
  fun noSession_runsHeadless() {
    sessionAvailable = false

    launcher.startVoiceTurn(VoiceTrigger.WAKE_PHRASE)

    assertEquals(listOf(VoiceTrigger.WAKE_PHRASE), headless)
  }

  @Test
  fun activeTurn_secondTriggerIgnored() {
    active = true

    launcher.startVoiceTurn(VoiceTrigger.WAKE_PHRASE)

    assertTrue(shown.isEmpty())
    assertTrue(headless.isEmpty())
  }
}
