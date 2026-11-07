package com.malladi2610.phoneagent.voice.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import com.malladi2610.phoneagent.voice.HeadlessVoiceTurn
import com.malladi2610.phoneagent.voice.PaVoice

/**
 * The default-assistant service. The system binds it while PhoneAgent holds the Assistant role;
 * [current] is non-null between onReady and onShutdown, which is when showSession works.
 */
class PaVoiceInteractionService : VoiceInteractionService() {

  override fun onReady() {
    super.onReady()
    PaVoice.install(applicationContext) // no-op if the app already installed it
    // We never read the screen: don't collect assist data or screenshots on a side-button press.
    setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    current = this
  }

  override fun onShutdown() {
    if (current === this) current = null
    super.onShutdown()
  }

  /** The lock-screen "voice assist" affordance: show our session (its window sits above the keyguard). */
  override fun onLaunchVoiceAssistFromKeyguard() {
    showSession(Bundle().apply { putString(PaVoice.EXTRA_TRIGGER, VoiceTrigger.ASSISTANT_BUTTON.name) }, 0)
  }

  /** The system couldn't show our session (e.g. binding failed): run the turn headless instead. */
  override fun onShowSessionFailed(args: Bundle) {
    val trigger =
      args.getString(PaVoice.EXTRA_TRIGGER)?.let { runCatching { VoiceTrigger.valueOf(it) }.getOrNull() }
        ?: VoiceTrigger.ASSISTANT_BUTTON
    HeadlessVoiceTurn.start(applicationContext, trigger)
  }

  companion object {
    @Volatile
    var current: PaVoiceInteractionService? = null
      private set
  }
}
