package com.malladi2610.phoneagent.wakeword

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible trampoline behind "Tap to resume listening": while this activity is in the foreground
 * the app may start its microphone foreground service. Falls back to the setup screen when the
 * service can't start (not enrolled, no permission, disabled).
 */
class WakeResumeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WakeNotifications.cancelResume(this)
    if (!WakeWord.start(this)) {
      startActivity(Intent(this, WakeEnrollmentActivity::class.java))
    }
    finish()
  }
}
