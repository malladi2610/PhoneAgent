package com.malladi2610.phoneagent.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.malladi2610.phoneagent.voice.SpeechOutput
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's built-in TextToSpeech. Only used to voice errors when Sarvam itself is failing
 * (missing/rejected key, no network), so the user is never left with a silent failure.
 */
class AndroidTtsOutput(private val context: Context) : SpeechOutput {
  private val lock = Mutex()
  private var tts: TextToSpeech? = null

  private suspend fun engine(): TextToSpeech? =
    lock.withLock {
      tts?.let { return@withLock it }
      val init = CompletableDeferred<Boolean>()
      val engine = TextToSpeech(context.applicationContext) { status -> init.complete(status == TextToSpeech.SUCCESS) }
      val ok = withTimeoutOrNull(5_000) { init.await() } == true
      if (!ok) {
        engine.shutdown()
        return@withLock null
      }
      engine.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      tts = engine
      engine
    }

  override suspend fun speak(text: String, language: String) {
    val engine = engine() ?: return
    engine.language = Locale.forLanguageTag(language)
    val id = UUID.randomUUID().toString()
    withTimeoutOrNull(20_000) {
      suspendCancellableCoroutine { cont ->
        engine.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
              if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
          }
        )
        cont.invokeOnCancellation { engine.stop() }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) {
          cont.resume(Unit)
        }
      }
    }
  }
}
