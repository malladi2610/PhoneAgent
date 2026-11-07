package com.malladi2610.phoneagent.voice

import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import com.malladi2610.phoneagent.core.voice.VoiceTurnLauncher

/**
 * Starts a voice turn from any trigger. If PhoneAgent is the bound default assistant, the turn
 * runs inside a visible assistant session (works over the lock screen and allows activity launches
 * from tools); otherwise it runs headless with a heads-up notification.
 */
class PaVoiceLauncher(
  private val isTurnActive: () -> Boolean,
  /** Returns true if an assistant session was requested. */
  private val showSession: (VoiceTrigger) -> Boolean,
  private val runHeadless: (VoiceTrigger) -> Unit,
) : VoiceTurnLauncher {
  override fun startVoiceTurn(trigger: VoiceTrigger) {
    if (isTurnActive()) return
    if (!showSession(trigger)) runHeadless(trigger)
  }
}
