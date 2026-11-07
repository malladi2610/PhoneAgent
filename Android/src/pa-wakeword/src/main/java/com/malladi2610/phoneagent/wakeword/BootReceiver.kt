package com.malladi2610.phoneagent.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.malladi2610.phoneagent.core.settings.PaSettings

/**
 * Android forbids starting a microphone foreground service from BOOT_COMPLETED (and after an app
 * update the service is gone too), so we only post "Tap to resume listening"; the tap starts the
 * service from the foreground via [WakeResumeActivity].
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED -> {
        if (PaSettings(context).wakeWordEnabled && WakeWord.isEnrolled(context) && !WakeWord.isRunning) {
          WakeNotifications.postResume(context, title = "PhoneAgent isn't listening yet")
        }
      }
    }
  }
}
