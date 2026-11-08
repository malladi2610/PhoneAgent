package com.malladi2610.phoneagent.voice.sarvam

import com.malladi2610.phoneagent.voice.SpeechOutput
import com.malladi2610.phoneagent.voice.audio.WavPlayer

/** [SpeechOutput] = Sarvam TTS (bulbul:v3, 24 kHz WAV) + AudioTrack playback. */
class SarvamSpeechOutput(
  private val tts: SarvamTtsClient,
  private val player: WavPlayer,
  private val speaker: () -> String,
) : SpeechOutput {
  override suspend fun speak(text: String, language: String) {
    if (text.isBlank()) return
    player.play(tts.synthesize(text, language, speaker()))
  }
}
