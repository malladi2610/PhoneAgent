package com.malladi2610.phoneagent.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What started a voice turn. */
enum class VoiceTrigger {
  WAKE_PHRASE,
  ASSISTANT_BUTTON,
  IN_APP_MIC,
}

/**
 * Entry point into a voice turn (owned by pa-voice). The wake-phrase service (pa-wakeword) calls
 * [startVoiceTurn] when the owner's wake phrase is detected; the assistant-role session calls it on
 * a side-button press.
 */
interface VoiceTurnLauncher {
  fun startVoiceTurn(trigger: VoiceTrigger)
}

/**
 * Process-wide coordination between modules. pa-voice sets [voiceSessionActive] while it owns the
 * microphone; pa-wakeword pauses its listening while this is true (only one module records at a
 * time).
 */
object PhoneAgentBus {
  private val _voiceSessionActive = MutableStateFlow(false)
  val voiceSessionActive: StateFlow<Boolean> = _voiceSessionActive.asStateFlow()

  fun setVoiceSessionActive(active: Boolean) {
    _voiceSessionActive.value = active
  }

  /** Set once by the app at startup (Application.onCreate). */
  @Volatile var voiceTurnLauncher: VoiceTurnLauncher? = null
}
