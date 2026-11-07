package com.malladi2610.phoneagent.core.settings

import android.content.Context

/** Non-secret PhoneAgent preferences shared by all modules. */
class PaSettings(context: Context) {
  private val prefs = context.getSharedPreferences("phoneagent_settings", Context.MODE_PRIVATE)

  /** Ollama model used by voice turns (name exactly as in /api/tags). Null = not chosen yet. */
  var voiceModel: String?
    get() = prefs.getString(KEY_VOICE_MODEL, null)
    set(value) = prefs.edit().putString(KEY_VOICE_MODEL, value).apply()

  /** Sarvam STT language: "auto" (default) or a code like "en-IN", "hi-IN", "te-IN". */
  var sttLanguage: String
    get() = prefs.getString(KEY_STT_LANGUAGE, "auto") ?: "auto"
    set(value) = prefs.edit().putString(KEY_STT_LANGUAGE, value).apply()

  /** Sarvam TTS language for spoken replies. */
  var ttsLanguage: String
    get() = prefs.getString(KEY_TTS_LANGUAGE, "en-IN") ?: "en-IN"
    set(value) = prefs.edit().putString(KEY_TTS_LANGUAGE, value).apply()

  /** Sarvam bulbul:v3 speaker. */
  var ttsSpeaker: String
    get() = prefs.getString(KEY_TTS_SPEAKER, "ishita") ?: "ishita"
    set(value) = prefs.edit().putString(KEY_TTS_SPEAKER, value).apply()

  /** Whether the always-listening wake-phrase service should run. */
  var wakeWordEnabled: Boolean
    get() = prefs.getBoolean(KEY_WAKE_ENABLED, false)
    set(value) = prefs.edit().putBoolean(KEY_WAKE_ENABLED, value).apply()

  private companion object {
    const val KEY_VOICE_MODEL = "voice_model"
    const val KEY_STT_LANGUAGE = "stt_language"
    const val KEY_TTS_LANGUAGE = "tts_language"
    const val KEY_TTS_SPEAKER = "tts_speaker"
    const val KEY_WAKE_ENABLED = "wake_word_enabled"
  }
}
