package com.malladi2610.phoneagent.voice

import com.malladi2610.phoneagent.core.agent.AgentError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceToolUiTest {
  private val output = FakeOutput()
  private var unlockResult = true
  private var unlockAsked = 0

  private fun ui(input: SpeechInput) =
    VoiceToolUi(
      input = input,
      output = output,
      sttLanguage = "auto",
      ttsLanguage = "en-IN",
      waitForUnlock = {
        unlockAsked++
        unlockResult
      },
      ui = {},
    )

  @Test
  fun confirm_speaksSummaryAndQuestion_yesAllows() = runTest {
    val allowed = ui(FakeInput(SttResult("yes send it", "en-IN"))).confirm("Send SMS to +49 151: 'Running late'")

    assertTrue(allowed)
    assertEquals("Send SMS to +49 151: 'Running late'. Shall I go ahead?", output.spoken.single().first)
  }

  @Test
  fun confirm_anythingElse_denies() = runTest {
    assertFalse(ui(FakeInput(SttResult("maybe later", "en-IN"))).confirm("Call +49 151"))
    assertFalse(ui(FakeInput(SttResult("", null))).confirm("Call +49 151"))
  }

  @Test
  fun confirm_listenFailure_denies() = runTest {
    val failing =
      object : SpeechInput {
        override suspend fun listenOnce(language: String): SttResult = throw AgentError.Network("Sarvam")
      }

    assertFalse(ui(failing).confirm("Call +49 151"))
  }

  @Test
  fun requestUnlock_speaksPrompt_thenWaits() = runTest {
    unlockResult = false

    val unlocked = ui(FakeInput()).requestUnlock()

    assertFalse(unlocked)
    assertEquals(1, unlockAsked)
    assertEquals("Please unlock your phone to continue.", output.spoken.single().first)
  }

  @Test
  fun requestRuntimePermission_isAlwaysFalse() = runTest {
    assertFalse(ui(FakeInput()).requestRuntimePermission("android.permission.SEND_SMS"))
  }
}
