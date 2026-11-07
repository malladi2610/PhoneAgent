package com.malladi2610.phoneagent.voice

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.secrets.SecretKey
import com.malladi2610.phoneagent.core.settings.PaSettings
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import com.malladi2610.phoneagent.voice.assistant.PaVoiceInteractionService
import com.malladi2610.phoneagent.voice.audio.AndroidTtsOutput
import com.malladi2610.phoneagent.voice.audio.Earcon
import com.malladi2610.phoneagent.voice.audio.MicRecorder
import com.malladi2610.phoneagent.voice.audio.WavPlayer
import com.malladi2610.phoneagent.voice.sarvam.SarvamSpeechOutput
import com.malladi2610.phoneagent.voice.sarvam.SarvamSttClient
import com.malladi2610.phoneagent.voice.sarvam.SarvamTtsClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * pa-voice entry point. The app calls [install] once from Application.onCreate (after
 * `PhoneAgentServices.install`); it builds the Sarvam voice pipeline and registers the
 * [PhoneAgentBus.voiceTurnLauncher].
 */
object PaVoice {
  private const val TAG = "PaVoice"
  const val EXTRA_TRIGGER = "com.malladi2610.phoneagent.voice.TRIGGER"

  private lateinit var app: Context
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var turnJob: Job? = null

  private val _ui = MutableSharedFlow<VoiceUiState>(replay = 1, extraBufferCapacity = 64)

  /** Every state of the current voice turn, for the assistant overlay and the notification. */
  val ui: SharedFlow<VoiceUiState> = _ui

  lateinit var controller: VoiceTurnController
    private set

  lateinit var unlockWaiter: UnlockWaiter
    private set

  lateinit var speechOutput: SpeechOutput
    private set

  lateinit var launcher: PaVoiceLauncher
    private set

  val isInstalled: Boolean
    get() = ::controller.isInitialized

  fun install(context: Context) {
    if (isInstalled) return
    app = context.applicationContext
    val http =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val sarvamKey = { sarvamKey() }
    val player = WavPlayer(app)
    val input =
      SarvamSttClient(
        http = http,
        keyProvider = sarvamKey,
        mic = MicRecorder(app),
        beforeRecording = { runCatching { Earcon.play(player) } },
      )
    speechOutput = SarvamSpeechOutput(SarvamTtsClient(http, sarvamKey), player, speaker = { settings().ttsSpeaker })
    unlockWaiter = UnlockWaiter(app)
    controller =
      VoiceTurnController(
        input = input,
        output = speechOutput,
        servicesProvider = { PhoneAgentServices.get() },
        keyguard = { unlockWaiter.isLocked() },
        ui = { _ui.tryEmit(it) },
        fallbackOutput = AndroidTtsOutput(app),
        waitForUnlock = { unlockWaiter.await(30_000) },
      )
    launcher =
      PaVoiceLauncher(
        isTurnActive = { controller.isActive },
        showSession = ::showAssistantSession,
        runHeadless = { HeadlessVoiceTurn.start(app, it) },
      )
    PhoneAgentBus.voiceTurnLauncher = launcher
  }

  /** Runs a turn in the process scope (survives the overlay closing). False if one is running. */
  fun startTurn(trigger: VoiceTrigger): Boolean {
    if (!isInstalled || controller.isActive) return false
    turnJob = scope.launch { controller.runTurn(trigger) }
    return true
  }

  fun cancelTurn() {
    turnJob?.cancel()
  }

  /** Settings even when the app graph isn't installed (e.g. the setup screen opened first). */
  fun settings(): PaSettings =
    if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().settings else PaSettings(app)

  private fun sarvamKey(): String? =
    if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().secrets.get(SecretKey.SARVAM_API_KEY) else null

  private fun showAssistantSession(trigger: VoiceTrigger): Boolean {
    val service = PaVoiceInteractionService.current ?: return false
    // Claim the mic now so the wake-phrase detector stays paused until the session's turn starts.
    PhoneAgentBus.setVoiceSessionActive(true)
    return try {
      service.showSession(Bundle().apply { putString(EXTRA_TRIGGER, trigger.name) }, 0)
      scope.launch {
        kotlinx.coroutines.delay(SESSION_START_WATCHDOG_MS)
        if (!controller.isActive) PhoneAgentBus.setVoiceSessionActive(false)
      }
      true
    } catch (e: Exception) {
      Log.w(TAG, "showSession failed: ${e.javaClass.simpleName}")
      PhoneAgentBus.setVoiceSessionActive(false)
      false
    }
  }

  private const val SESSION_START_WATCHDOG_MS = 5_000L
}
