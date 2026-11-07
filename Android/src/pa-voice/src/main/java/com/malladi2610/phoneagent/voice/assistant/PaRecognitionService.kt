package com.malladi2610.phoneagent.voice.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.malladi2610.phoneagent.core.agent.AgentError
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.voice.MicUnavailableException
import com.malladi2610.phoneagent.voice.PaVoice
import com.malladi2610.phoneagent.voice.SpeechInput
import com.malladi2610.phoneagent.voice.audio.MicRecorder
import com.malladi2610.phoneagent.voice.sarvam.SarvamSttClient
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.secrets.SecretKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Required by the platform: a voice-interaction service without a `recognitionService` is rejected
 * (VoiceInteractionServiceInfo: "No recognitionService specified") and doesn't qualify for the
 * Assistant role. Becoming the assistant also makes this the system's default speech recognizer,
 * so it does a minimal real job: one utterance through Sarvam STT, returned as a single result.
 */
class PaRecognitionService : RecognitionService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var job: Job? = null
  private val http by lazy { OkHttpClient() }

  private val input: SpeechInput by lazy {
    SarvamSttClient(
      http = http,
      keyProvider = {
        if (PhoneAgentServices.isInstalled()) PhoneAgentServices.get().secrets.get(SecretKey.SARVAM_API_KEY) else null
      },
      mic = MicRecorder(this),
    )
  }

  override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
    if (job?.isActive == true || PhoneAgentBus.voiceSessionActive.value) {
      listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
      return
    }
    val language =
      recognizerIntent?.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE)?.takeIf { it.endsWith("-IN") }
        ?: if (PaVoice.isInstalled) PaVoice.settings().sttLanguage else "auto"
    job =
      scope.launch {
        PhoneAgentBus.setVoiceSessionActive(true)
        try {
          listener.readyForSpeech(Bundle())
          val result = input.listenOnce(language)
          listener.endOfSpeech()
          if (result.text.isBlank()) {
            listener.error(SpeechRecognizer.ERROR_NO_MATCH)
          } else {
            listener.results(
              Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result.text))
                putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
              }
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          listener.error(errorCode(e))
        } finally {
          PhoneAgentBus.setVoiceSessionActive(false)
        }
      }
  }

  override fun onStopListening(listener: Callback) {
    // Sarvam endpoints on silence; nothing to flush.
  }

  override fun onCancel(listener: Callback) {
    job?.cancel()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun errorCode(e: Exception): Int =
    when (e) {
      is MicUnavailableException -> SpeechRecognizer.ERROR_AUDIO
      is AgentError.Network -> SpeechRecognizer.ERROR_NETWORK
      is AgentError.MissingKey,
      is AgentError.Unauthorized -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
      is AgentError.RateLimited -> SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
      else -> SpeechRecognizer.ERROR_SERVER
    }
}
