package com.malladi2610.phoneagent.voice

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.core.voice.VoiceTrigger

/**
 * "Talk to PhoneAgent": an invisible activity that starts a voice turn and closes. It is the target
 * for the launcher icon, Samsung lock-screen shortcuts, Good Lock back-tap / edge gestures,
 * Routines, and the Quick Settings tile. The turn runs in the same assistant session as the side
 * button, so the lock-screen rules (unlock / confirm) apply unchanged.
 */
class TalkActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val launcher = PhoneAgentBus.voiceTurnLauncher
    if (launcher == null) {
      Log.w(TAG, "Voice isn't installed in this process")
    } else {
      launcher.startVoiceTurn(VoiceTrigger.ASSISTANT_BUTTON)
    }
    finish()
    overridePendingTransition(0, 0)
  }

  private companion object {
    const val TAG = "PaTalk"
  }
}
