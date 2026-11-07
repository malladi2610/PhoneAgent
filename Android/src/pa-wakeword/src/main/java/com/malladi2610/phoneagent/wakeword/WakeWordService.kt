package com.malladi2610.phoneagent.wakeword

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.malladi2610.phoneagent.core.settings.PaSettings
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type microphone) that listens for the enrolled wake phrase.
 *
 * Pipeline on one background thread: AudioRecord in 100 ms chunks → sherpa KWS → 2.5 s ring
 * buffer → on a hit, CAM++ embedding + voice match ([WakePipeline]/[WakeGate]). On a match the mic
 * is released first, then `PhoneAgentBus.voiceTurnLauncher.startVoiceTurn(WAKE_PHRASE)` runs on the
 * main thread. While `PhoneAgentBus.voiceSessionActive` is true the mic stays closed; listening
 * resumes 2 s after it goes back to false.
 */
class WakeWordService : Service() {
  private val main = Handler(Looper.getMainLooper())
  private var worker: ListenThread? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_PAUSE) {
      pause()
      return START_NOT_STICKY
    }
    if (worker != null) return START_STICKY

    val enrollment = WakeWordStore.load(this)
    val phrase = enrollment?.phrase ?: "your wake phrase"
    try {
      // Must come first: we were started with startForegroundService().
      startForeground(
        WakeNotifications.LISTENING_ID,
        WakeNotifications.listening(this, phrase),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
      )
    } catch (e: RuntimeException) {
      // SecurityException / ForegroundServiceStartNotAllowedException: a microphone service may
      // only start while the app is in the foreground (not from boot, not on a sticky restart).
      Log.i(TAG, "Microphone foreground service not allowed now: ${e.javaClass.simpleName}")
      WakeNotifications.postResume(this)
      stopSelf()
      return START_NOT_STICKY
    }
    if (enrollment == null || !WakeWord.hasMicPermission(this) || !PaSettings(this).wakeWordEnabled) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    WakeNotifications.cancelResume(this)
    isRunning = true
    worker = ListenThread(enrollment).also { it.start() }
    // Sticky: if the process is killed, the restart (from the background) is refused by Android at
    // startForeground above, which posts the "Tap to resume listening" notification.
    return START_STICKY
  }

  override fun onDestroy() {
    stopWorker()
    isRunning = false
    super.onDestroy()
  }

  private fun stopWorker() {
    worker?.let {
      it.stopped = true
      it.interrupt()
    }
    worker = null
  }

  /** Notification "Pause" action: stop listening until the user taps to resume. */
  private fun pause() {
    val wasRunning = worker != null
    stopWorker()
    isRunning = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    if (wasRunning) WakeNotifications.postResume(this)
    stopSelf()
  }

  private fun fail(reason: String) {
    Log.w(TAG, "Stopping: $reason")
    stopWorker()
    isRunning = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    WakeNotifications.postResume(this, title = reason)
    stopSelf()
  }

  /** Runs on the main thread. */
  private fun launchVoiceTurn(thread: ListenThread) {
    val launcher = PhoneAgentBus.voiceTurnLauncher
    if (launcher == null) {
      Log.w(TAG, "Wake phrase matched but no VoiceTurnLauncher is installed")
      thread.handoffFailed.set(true)
      return
    }
    try {
      launcher.startVoiceTurn(VoiceTrigger.WAKE_PHRASE)
    } catch (e: RuntimeException) {
      Log.w(TAG, "startVoiceTurn failed", e)
      thread.handoffFailed.set(true)
    }
  }

  private inner class ListenThread(private val enrollment: Enrollment) : Thread("pa-wakeword") {
    @Volatile var stopped = false
    val handoffFailed = AtomicBoolean(false)

    override fun run() {
      val engine =
        try {
          WakeWordEngine.create(this@WakeWordService)
        } catch (e: WakeWordUnavailableException) {
          Log.e(TAG, "Wake-word engine unavailable", e)
          if (!stopped) main.post { if (worker === this) fail("Wake phrase unavailable") }
          return
        }
      var detector: KeywordDetector? = null
      var mic: MicCapture? = null
      try {
        detector = engine.newDetector(enrollment.keywordLine)
        val pipeline = WakePipeline(detector, engine, enrollment.profile)
        var sessionActive = PhoneAgentBus.voiceSessionActive.value
        pipeline.onVoiceSessionActive(sessionActive, now())
        var micFailures = 0
        while (!stopped) {
          val active = PhoneAgentBus.voiceSessionActive.value
          if (active != sessionActive) {
            sessionActive = active
            pipeline.onVoiceSessionActive(active, now())
          }
          if (handoffFailed.getAndSet(false)) pipeline.onHandoffFailed(now())
          pipeline.tick(now())

          if (!pipeline.shouldListen) {
            mic?.close()
            mic = null
            sleep(IDLE_POLL_MS)
            continue
          }
          if (mic == null) {
            mic =
              try {
                MicCapture.open()
              } catch (e: IOException) {
                null
              }
            if (mic == null) {
              // Busy (e.g. a call) or transient error: retry for a while, then give up.
              if (++micFailures > MAX_MIC_FAILURES) {
                main.post { if (worker === this) fail("Microphone unavailable") }
                break
              }
              sleep(MIC_RETRY_MS)
              continue
            }
            pipeline.resetAudio()
          }
          val chunk = mic.read()
          if (chunk == null) {
            mic.close()
            mic = null
            sleep(MIC_RETRY_MS)
            continue
          }
          if (chunk.isEmpty()) continue
          micFailures = 0
          when (val event = pipeline.onAudio(chunk, now())) {
            is WakePipeline.Event.Triggered -> {
              Log.i(TAG, "Wake phrase matched (score ${fmt(event.score)} ≥ ${fmt(pipeline.threshold)})")
              mic.close() // release the mic before the voice turn opens its own
              mic = null
              main.post { if (worker === this) launchVoiceTurn(this) }
            }
            is WakePipeline.Event.Rejected ->
              Log.i(TAG, "Wake phrase heard, voice did not match (score ${event.score?.let(::fmt)})")
            else -> Unit
          }
        }
      } catch (e: InterruptedException) {
        // stopping
      } catch (e: SecurityException) {
        main.post { if (worker === this) fail("Microphone permission was removed") }
      } catch (e: RuntimeException) {
        Log.e(TAG, "Wake-word listener crashed", e)
        main.post { if (worker === this) fail("Wake phrase stopped after an error") }
      } finally {
        mic?.close()
        detector?.close()
        engine.close()
      }
    }

    private fun now() = SystemClock.elapsedRealtime()
  }

  companion object {
    private const val TAG = "PaWakeWord"
    const val ACTION_PAUSE = "com.malladi2610.phoneagent.wakeword.action.PAUSE"
    private const val IDLE_POLL_MS = 100L
    private const val MIC_RETRY_MS = 1_000L
    private const val MAX_MIC_FAILURES = 120

    @Volatile
    var isRunning = false
      private set

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)
  }
}
