package com.malladi2610.phoneagent.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * A voice turn without the assistant window (PhoneAgent is not the default assistant). Progress is
 * shown in a heads-up notification with a Stop action.
 *
 * Note: Android only lets a background app record if it has a microphone foreground service; the
 * wake-phrase service provides that, so wake-phrase turns work headless. Other headless triggers
 * may get silence from the mic (reported as "didn't catch that").
 */
internal object HeadlessVoiceTurn {
  private const val CHANNEL = "pa_voice_turn"
  private const val NOTIFICATION_ID = 0x5A01
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observer: Job? = null

  fun start(context: Context, trigger: VoiceTrigger) {
    val app = context.applicationContext
    val nm = app.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "Voice turns", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Shows what PhoneAgent heard and is doing"
        setSound(null, null)
      }
    )
    observer?.cancel()
    observer =
      scope.launch {
        // drop(1): skip the replayed state of the previous turn.
        PaVoice.ui.drop(1).collect { state ->
          post(app, nm, state)
          if (state is VoiceUiState.Done || state is VoiceUiState.Error) {
            delay(if (state is VoiceUiState.Error) 8_000 else 3_000)
            nm.cancel(NOTIFICATION_ID)
          }
        }
      }
    if (!PaVoice.startTurn(trigger)) {
      observer?.cancel()
    }
  }

  private fun post(context: Context, nm: NotificationManager, state: VoiceUiState) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      return
    }
    val (title, text) =
      when (state) {
        VoiceUiState.Listening -> "Listening…" to ""
        is VoiceUiState.Heard -> "You said" to state.text
        VoiceUiState.Thinking -> "Working on it…" to ""
        is VoiceUiState.Speaking -> "PhoneAgent" to state.text
        VoiceUiState.Done -> "PhoneAgent" to "Done"
        is VoiceUiState.Error -> "PhoneAgent" to state.message
      }
    val stop =
      PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, StopVoiceTurnReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val ongoing = state !is VoiceUiState.Done && state !is VoiceUiState.Error
    val n =
      Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.pa_ic_mic)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setOngoing(ongoing)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_STATUS)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .apply {
          if (ongoing) addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        .build()
    nm.notify(NOTIFICATION_ID, n)
  }
}

/** "Stop" on the voice-turn notification. */
class StopVoiceTurnReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    PaVoice.cancelTurn()
    context.getSystemService(NotificationManager::class.java)?.cancel(0x5A01)
  }
}
