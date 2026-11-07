package com.malladi2610.phoneagent.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object WakeNotifications {
  private const val CHANNEL_ID = "phoneagent_wakeword"
  const val LISTENING_ID = 0x5741_0001
  private const val RESUME_ID = 0x5741_0002

  private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

  fun ensureChannel(context: Context) {
    val channel =
      NotificationChannel(CHANNEL_ID, "Wake phrase", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while PhoneAgent listens for your wake phrase"
        setShowBadge(false)
      }
    manager(context).createNotificationChannel(channel)
  }

  fun listening(context: Context, phrase: String): Notification {
    ensureChannel(context)
    val open =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, WakeEnrollmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val pause =
      PendingIntent.getService(
        context,
        1,
        Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_PAUSE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return Notification.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.pa_ic_wakeword)
      .setContentTitle("Listening for “$phrase”")
      .setContentText("Voice match is on")
      .setContentIntent(open)
      .setOngoing(true)
      .setCategory(Notification.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(Notification.Action.Builder(null, "Pause", pause).build())
      .build()
  }

  /**
   * "Tap to resume listening": the tap opens [WakeResumeActivity], which starts the microphone
   * service from the foreground (Android does not allow it from the background or from boot).
   */
  fun postResume(context: Context, title: String = "Wake phrase paused") {
    ensureChannel(context)
    val resume =
      PendingIntent.getActivity(
        context,
        2,
        Intent(context, WakeResumeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification =
      Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.pa_ic_wakeword)
        .setContentTitle(title)
        .setContentText("Tap to resume listening")
        .setContentIntent(resume)
        .setAutoCancel(true)
        .build()
    runCatching { manager(context).notify(RESUME_ID, notification) } // POST_NOTIFICATIONS may be denied
  }

  fun cancelResume(context: Context) = manager(context).cancel(RESUME_ID)
}
